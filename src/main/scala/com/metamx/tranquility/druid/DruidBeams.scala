/*
 * Tranquility.
 * Copyright (C) 2013, 2014  Metamarkets Group Inc.
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package com.metamx.tranquility.druid

import com.fasterxml.jackson.core.JsonGenerator
import com.metamx.common.Granularity
import com.metamx.common.lifecycle.Lifecycle
import com.metamx.common.logger.Logger
import com.metamx.common.scala.Jackson
import com.metamx.common.scala.net.curator.{DiscoConfig, Disco}
import com.metamx.common.scala.timekeeper.{SystemTimekeeper, Timekeeper}
import com.metamx.common.scala.untyped.Dict
import com.metamx.emitter.core.LoggingEmitter
import com.metamx.emitter.service.ServiceEmitter
import com.metamx.tranquility.beam.{HashPartitionBeam, ClusteredBeam, ClusteredBeamTuning, Beam}
import com.metamx.tranquility.finagle.{BeamService, FinagleRegistryConfig, FinagleRegistry}
import com.metamx.tranquility.typeclass.{JavaObjectWriter, JsonWriter, ObjectWriter, Timestamper}
import com.twitter.finagle.Service
import io.druid.data.input.impl.TimestampSpec
import java.{lang => jl, util => ju}
import org.apache.curator.framework.CuratorFramework
import org.joda.time.{DateTime, Interval}
import org.scala_tools.time.Implicits._
import scala.collection.JavaConverters._

/**
 * Builds Beams or Finagle services that send events to the Druid indexing service.
 *
 * {{{
 * val curator = CuratorFrameworkFactory.newClient("localhost:2181", new BoundedExponentialBackoffRetry(100, 30000, 30))
 * curator.start()
 * val dataSource = "foo"
 * val dimensions = Seq("bar")
 * val aggregators = Seq(new LongSumAggregatorFactory("baz", "baz"))
 * val service = DruidBeams
 *   .builder[Map[String, Any]](eventMap => new DateTime(eventMap("timestamp")))
 *   .curator(curator)
 *   .discoveryPath("/test/discovery")
 *   .location(DruidLocation(new DruidEnvironment("druid:local:indexer", "druid:local:firehose:%s"), dataSource))
 *   .rollup(DruidRollup(dimensions, aggregators, QueryGranularity.MINUTE))
 *   .tuning(new ClusteredBeamTuning(Granularity.HOUR, 10.minutes, 1, 1))
 *   .buildService()
 * val future = service(Seq(Map("timestamp" -> "2010-01-02T03:04:05.678Z", "bar" -> "hey", "baz" -> 3)))
 * println("result = %s" format Await.result(future))
 * }}}
 *
 * Your event type (in this case, {{{Map[String, Any]}}} must be serializable via Jackson to JSON that Druid can
 * understand. If Jackson is not an appropriate choice, you can provide an ObjectWriter via {{{.objectWriter(...)}}}.
 */
object DruidBeams
{
  val DefaultTimestampSpec = new TimestampSpec("timestamp", "iso")

  def builder[EventType](timeFn: EventType => DateTime) = {
    new Builder[EventType](
      new BuilderConfig(
        _timestamper = Some(
          new Timestamper[EventType]
          {
            override def timestamp(a: EventType) = timeFn(a)
          }
        )
      )
    )
  }

  def builder[EventType]()(implicit timestamper: Timestamper[EventType]) = {
    new Builder[EventType](new BuilderConfig(_timestamper = Some(timestamper)))
  }

  class Builder[EventType] private[druid](config: BuilderConfig[EventType])
  {
    def curator(curator: CuratorFramework) = new Builder[EventType](config.copy(_curator = Some(curator)))

    def discoveryPath(path: String) = new Builder[EventType](config.copy(_discoveryPath = Some(path)))

    def tuning(tuning: ClusteredBeamTuning) = new Builder[EventType](config.copy(_tuning = Some(tuning)))

    def druidTuning(druidTuning: DruidTuning) = new Builder[EventType](config.copy(_druidTuning = Some(druidTuning)))

    def location(location: DruidLocation) = new Builder[EventType](config.copy(_location = Some(location)))

    def rollup(rollup: DruidRollup) = new Builder[EventType](config.copy(_rollup = Some(rollup)))

    def timestampSpec(timestampSpec: TimestampSpec) = new Builder[EventType](config.copy(_timestampSpec = Some(timestampSpec)))

    def clusteredBeamZkBasePath(path: String) = new Builder[EventType](config.copy(_clusteredBeamZkBasePath = Some(path)))

    def clusteredBeamIdent(ident: String) = new Builder[EventType](config.copy(_clusteredBeamIdent = Some(ident)))

    def druidBeamConfig(beamConfig: DruidBeamConfig) = new Builder[EventType](config.copy(_druidBeamConfig = Some(beamConfig)))

    def emitter(emitter: ServiceEmitter) = new Builder[EventType](config.copy(_emitter = Some(emitter)))

    def finagleRegistry(registry: FinagleRegistry) = new Builder[EventType](config.copy(_finagleRegistry = Some(registry)))

    def timekeeper(timekeeper: Timekeeper) = new Builder[EventType](config.copy(_timekeeper = Some(timekeeper)))

    def beamDecorateFn(f: (Interval, Int) => Beam[EventType] => Beam[EventType]) = new
        Builder(config.copy(_beamDecorateFn = Some(f)))

    def beamMergeFn(f: Seq[Beam[EventType]] => Beam[EventType]) = new Builder[EventType](config.copy(_beamMergeFn = Some(f)))

    def alertMap(d: Dict) = new Builder[EventType](config.copy(_alertMap = Some(d)))

    @deprecated("use .objectWriter(...)", "0.2.21")
    def eventWriter(writer: ObjectWriter[EventType]) = new Builder[EventType](config.copy(_objectWriter = Some(writer)))

    def objectWriter(writer: ObjectWriter[EventType]) = new Builder[EventType](config.copy(_objectWriter = Some(writer)))

    def objectWriter(writer: JavaObjectWriter[EventType]) = {
      new Builder[EventType](config.copy(_objectWriter = Some(ObjectWriter.wrap(writer))))
    }

    def eventTimestamped(timeFn: EventType => DateTime) = new Builder[EventType](
      config.copy(
        _timestamper = Some(
          new Timestamper[EventType]
          {
            def timestamp(a: EventType) = timeFn(a)
          }
        )
      )
    )

    def buildBeam(): Beam[EventType] = {
      val things = config.buildAll()
      implicit val eventTimestamped = things.timestamper getOrElse {
        throw new IllegalArgumentException("WTF?! Should have had a Timestamperable event...")
      }
      val lifecycle = new Lifecycle
      val indexService = new IndexService(
        things.location.environment,
        things.druidBeamConfig,
        things.finagleRegistry,
        things.druidObjectMapper,
        lifecycle
      )
      val druidBeamMaker = new DruidBeamMaker[EventType](
        things.druidBeamConfig,
        things.location,
        things.tuning,
        things.druidTuning,
        things.rollup,
        things.timestampSpec,
        things.finagleRegistry,
        indexService,
        things.emitter,
        things.timekeeper,
        things.objectWriter
      )
      val clusteredBeam = new ClusteredBeam(
        things.clusteredBeamZkBasePath,
        things.clusteredBeamIdent,
        things.tuning,
        things.curator,
        things.emitter,
        things.timekeeper,
        things.scalaObjectMapper,
        druidBeamMaker,
        things.beamDecorateFn,
        things.beamMergeFn,
        things.alertMap
      )
      new Beam[EventType]
      {
        def propagate(events: Seq[EventType]) = clusteredBeam.propagate(events)

        def close() = clusteredBeam.close() map (_ => lifecycle.stop())

        override def toString = clusteredBeam.toString
      }
    }

    def buildService(): Service[Seq[EventType], Int] = {
      new BeamService(buildBeam())
    }

    def buildJavaService(): Service[ju.List[EventType], jl.Integer] = {
      val delegate = buildService()
      Service.mk((xs: ju.List[EventType]) => delegate(xs.asScala).map(Int.box))
    }
  }

  private case class BuilderConfig[EventType](
    _curator: Option[CuratorFramework] = None,
    _discoveryPath: Option[String] = None,
    _tuning: Option[ClusteredBeamTuning] = None,
    _druidTuning: Option[DruidTuning] = None,
    _location: Option[DruidLocation] = None,
    _rollup: Option[DruidRollup] = None,
    _timestampSpec: Option[TimestampSpec] = None,
    _clusteredBeamZkBasePath: Option[String] = None,
    _clusteredBeamIdent: Option[String] = None,
    _druidBeamConfig: Option[DruidBeamConfig] = None,
    _emitter: Option[ServiceEmitter] = None,
    _finagleRegistry: Option[FinagleRegistry] = None,
    _timekeeper: Option[Timekeeper] = None,
    _beamDecorateFn: Option[(Interval, Int) => Beam[EventType] => Beam[EventType]] = None,
    _beamMergeFn: Option[Seq[Beam[EventType]] => Beam[EventType]] = None,
    _alertMap: Option[Dict] = None,
    _objectWriter: Option[ObjectWriter[EventType]] = None,
    _timestamper: Option[Timestamper[EventType]] = None
  )
  {
    def buildAll() = new {
      val scalaObjectMapper       = Jackson.newObjectMapper()
      val druidObjectMapper       = DruidGuicer.objectMapper
      val curator                 = _curator getOrElse {
        throw new IllegalArgumentException("Missing 'curator'")
      }
      val discoveryPath           = _discoveryPath getOrElse "/druid/discovery"
      val disco                   = new Disco(
        curator,
        new DiscoConfig
        {
          def discoAnnounce = None

          def discoPath = discoveryPath
        }
      )
      val tuning                  = _tuning getOrElse {
        ClusteredBeamTuning()
      }
      val druidTuning             = _druidTuning getOrElse {
        new DruidTuning(75000, 10.minutes, 1)
      }
      val location                = _location getOrElse {
        throw new IllegalArgumentException("Missing 'location'")
      }
      val rollup                  = _rollup getOrElse {
        throw new IllegalArgumentException("Missing 'rollup'")
      }
      val timestampSpec           = _timestampSpec getOrElse {
        DefaultTimestampSpec
      }
      val clusteredBeamZkBasePath = _clusteredBeamZkBasePath getOrElse "/tranquility/beams"
      val clusteredBeamIdent      = _clusteredBeamIdent getOrElse {
        "%s/%s" format(location.environment.indexService, location.dataSource)
      }
      val druidBeamConfig         = _druidBeamConfig getOrElse {
        new DruidBeamConfig
        {
          override def firehoseGracePeriod = 5.minutes

          override def firehoseQuietPeriod = 1.minute

          override def firehoseRetryPeriod = 1.minute

          override def firehoseChunkSize = 1000

          override def indexRetryPeriod = 1.minute
        }
      }
      val emitter                 = _emitter getOrElse {
        val em = new ServiceEmitter(
          "tranquility",
          "localhost",
          new LoggingEmitter(new Logger(classOf[LoggingEmitter]), LoggingEmitter.Level.INFO, scalaObjectMapper)
        )
        em.start()
        em
      }
      val finagleRegistry         = _finagleRegistry getOrElse {
        new FinagleRegistry(
          new FinagleRegistryConfig
          {
            override def finagleHttpTimeout = 90.seconds

            override def finagleHttpConnectionsPerHost = 2
          },
          disco
        )
      }
      val timekeeper              = _timekeeper getOrElse new SystemTimekeeper
      val beamDecorateFn          = _beamDecorateFn getOrElse {
        (interval: Interval, partition: Int) => (beam: Beam[EventType]) => beam
      }
      val beamMergeFn             = _beamMergeFn getOrElse {
        (beams: Seq[Beam[EventType]]) => new HashPartitionBeam[EventType](beams.toIndexedSeq)
      }
      val alertMap                = _alertMap getOrElse Map.empty
      val objectWriter            = _objectWriter getOrElse {
        new JsonWriter[EventType]
        {
          protected def viaJsonGenerator(a: EventType, jg: JsonGenerator) {
            scalaObjectMapper.writeValue(jg, a)
          }
        }
      }
      val timestamper             = _timestamper
    }
  }

}

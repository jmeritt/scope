package com.tjd.api

import akka.actor.ActorRef
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.language.postfixOps

import com.tjd.video.CamActor
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory

import akka.actor.ActorSystem
import akka.actor.Props
import akka.event.Logging
import akka.event.LoggingAdapter
import akka.event.Logging.InfoLevel
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.model.ws.Message
import akka.http.scaladsl.model.ws.TextMessage
import akka.http.scaladsl.server.Directives._
import akka.pattern.ask
import akka.stream.ActorMaterializer
import akka.stream.io.OutputStreamSink
import akka.stream.scaladsl.Broadcast
import akka.stream.scaladsl.Flow
import akka.stream.scaladsl.BidiFlow
import akka.stream.scaladsl.FlowGraph
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString
import akka.util.Timeout
import spray.json.DefaultJsonProtocol

import java.util.UUID

package object Messages {
  case class Person(id: String, firstName: String, lastName: String, short: String, email: String)
  case class CamStreamMeta(name: String, description: String, thumb: String)
  case class Uplink(protocol: String, address: String, port: Int)
  case class CamStream(id: String, synopsis: CamStreamMeta, producer: Person, uplink: Uplink)

  case class Enter(person: Person, actor: ActorRef)
  case class Leave(person: Person)
  case class Shout(person: Person, message: String)
  case class Contribute(person: Person, amount: Int)
  case class ContributeAnnounce(contrib: Contribute, total: Int)
}

trait Protocols extends DefaultJsonProtocol with SprayJsonSupport with ScalaXmlSupport {
  import Messages._
  implicit val personFormat = jsonFormat5(Person)
  implicit val camMetaFormat = jsonFormat3(CamStreamMeta)
  implicit val uplinkFormat = jsonFormat3(Uplink)
  implicit val camFormat = jsonFormat4(CamStream)
  implicit val shoutFormat = jsonFormat2(Shout)
}

trait Service extends Protocols {
  implicit val system: ActorSystem
  implicit val executor: ExecutionContext
  val log: LoggingAdapter
  implicit val materializer: ActorMaterializer
  implicit val config: Config
  implicit val interface: String

  import scala.concurrent.duration._
  import scala.language.postfixOps
  implicit val timeout = Timeout(5 seconds)

  //  val people: TableQuery[People]
  //  val db: Database

  val routes = pathPrefix("cams") {
    get {
      pathEndOrSingleSlash {
        complete("OK")
      } ~
        path(JavaUUID) {
          id =>
            handleWebsocketMessages(redirectToActor(id)) ~
              complete {
                <html>
                  <head>
                    <title>HTTP Live Streaming Example</title>
                  </head>
                  <body>
                    <video controls="controls" autoplay="autoplay" src={ "http://localhost:8080/media/" + id.toString + "/" + "cam.m3u8" } height="300" width="400"/>
                  </body>
                </html>
              }
        }
    } ~
      post {
        entity(as[Messages.CamStreamMeta]) {
          request =>
            handleWith {
              newCamActor
            }
        }
      }
  } ~
    pathPrefix("media") {
      getFromBrowseableDirectory(config.getString("live.contentRoot"))
    }

  def redirectToActor(id: UUID) = Flow() { implicit b =>
    import FlowGraph.Implicits._

    // prepare graph elements
    val broadcast = b.add(Broadcast[Message](2))
    val jsonator = BidiFlow[Message, Messages.Shout, Messages.Shout, Message](outbound = { msg: Message =>
      Messages.Shout(Messages.Person("JAIME", "JAIME", "JAIME", "JAIME", "JAIME"), "WASSSUP")
    },
      inbound = { shout: Messages.Shout =>
        TextMessage.Strict("WASSSSSUP")
      })

    val sink = b.add(Sink.ignore)
    val zero = b.add(Flow[Message].filter { _ => false })

    // connect the graph
    broadcast.out(0) ~> sink
    broadcast.out(1) ~> zero.inlet

    // expose ports
    (broadcast.in, zero.outlet)
  }

  import Messages._
  def newCamActor(request: CamStreamMeta) = {
    (system.actorOf(Props[CamActor]) ? request).mapTo[CamStream]
  }

  def redirectToStream(out: java.io.OutputStream) = Flow() { implicit b =>
    import FlowGraph.Implicits._

    // prepare graph elements
    val broadcast = b.add(Broadcast[ByteString](2))
    val sink = b.add(OutputStreamSink(() => out))
    val zero = b.add(Flow[ByteString].map { _ => ByteString.empty })

    // connect the graph
    broadcast.out(0) ~> sink
    broadcast.out(1) ~> zero.inlet

    // expose ports
    (broadcast.in, zero.outlet)
  }
}

object LiveMicroservice extends App with Service {

  implicit val system = ActorSystem("pornoscope-sys")
  val log: LoggingAdapter = Logging(system, getClass)
  implicit val executor: ExecutionContext = system.dispatcher
  implicit val materializer: ActorMaterializer = ActorMaterializer()

  implicit val config: Config = ConfigFactory.load()
  private val liveConfig = config.getConfig("live")
  implicit val interface = liveConfig.getString("interface")
  val port = liveConfig.getInt("httpPort")

  log.info(s"Starting server on http://$interface:$port")
  Http().bindAndHandle(handler = logRequestResult("log", InfoLevel)(routes),
    interface = interface,
    port = port)

  scala.io.StdIn.readLine()
  system.shutdown()

}
package com.tjd.video

import akka.actor.{ Actor, ActorLogging, ActorRef }
import com.tjd.api.{ CamStream, CamStreamMeta, Uplink, Person }

import java.util.UUID
import scala.concurrent.{ Promise, Future }

import akka.stream.scaladsl.{ Tcp, Flow, Broadcast, FlowGraph, Sink, Source }
import akka.stream.io.OutputStreamSink
import akka.stream.ActorMaterializer
import akka.util.ByteString

/**
 * @author jmeritt
 */
object CamActor {
  
  case class Join(person : Person, actor: ActorRef)
  
    
}

class CamActor extends Actor with ActorLogging {
  
  implicit val executionContext = context.dispatcher 
  implicit val materializer = ActorMaterializer.create(context) 
  
  var patrons = Set.empty[CamActor.Join]
  

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

  def receive = {

    case request: CamStreamMeta =>
      val id = UUID.randomUUID().toString;
      log.info("Sender in the beginning is {}", sender)
      val source = Tcp(context.system).bind("localhost", 0)
      val shutdownPromise = Promise[Tcp.ServerBinding]
      val replyTo = sender

      val handleTcpConnection = Flow[Tcp.IncomingConnection].map {
        conn =>
          val in = new java.io.PipedInputStream
          val out = new java.io.PipedOutputStream(in)
          conn.handleWith(redirectToStream(out))

          shutdownPromise.future.map { shutdown =>
            val xcoder = Transcoder(s"/Users/jmeritt/tmp/$id", in)
            log.info("Transcoder for new cam {} exited with value:{}", id, xcoder.run)
            shutdown.unbind()
            log.info("Unbound cam {} from port {}", id, shutdown.localAddress.getPort)
          }

      }

      val bindingFuture = source.via(handleTcpConnection).to(Sink.ignore).run
      bindingFuture.map { binding =>
        log.info("Listening on tcp://{}:{}", binding.localAddress.getHostName, binding.localAddress.getPort)
        shutdownPromise.success(binding)
        log.info("Sender before I use it is {}", sender)
        replyTo ! CamStream(
          id,
          request,
          Person(id, "Jaime", "Meritt", "jmeritt", "jaime.meritt@gmail.com"),
          Uplink("tcp", binding.localAddress.getHostName, binding.localAddress.getPort))
      }

  }
}
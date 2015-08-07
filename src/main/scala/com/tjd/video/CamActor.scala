package com.tjd.video

import akka.actor.{ Actor, ActorLogging, ActorRef }

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

}

class CamActor extends Actor with ActorLogging {

  implicit val executionContext = context.dispatcher
  implicit val materializer = ActorMaterializer.create(context)

  
  var patrons = Set.empty[Messages.Person]
  var actors = Map.empty[Messages.Person, ActorRef]
  var pointsContributed = 0

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
    case request: Messages.CamStreamMeta =>
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
        replyTo ! Messages.CamStream(
          id,
          request,
          Messages.Person(id, "Jaime", "Meritt", "jmeritt", "jaime.meritt@gmail.com"),
          Messages.Uplink("tcp", binding.localAddress.getHostName, binding.localAddress.getPort))
      }

    case Messages.Enter(person, actor) =>
      patrons += person
      actors += (person -> actor)

    case Messages.Leave(person) =>
      patrons -= person
      actors -= person

    case shout: Messages.Shout =>
      actors.values.foreach { actor => actor ! shout }

    case contrib: Messages.Contribute =>
      pointsContributed += contrib.amount
      val announce = Messages.ContributeAnnounce(contrib, pointsContributed)
      actors.values.foreach { actor => actor ! announce }
  }
}
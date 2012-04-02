package org.asyncmongo

import akka.actor.{Actor, ActorRef}
import akka.actor.ActorSystem
import akka.actor.Props

import protocol._

package actors {
  import java.nio.ByteOrder
  import org.jboss.netty.bootstrap._
  import org.jboss.netty.channel._
  import org.jboss.netty.buffer._
  import org.jboss.netty.channel.socket.nio._


  class ChannelActor extends Actor {
    println("creating channelActor")
    val channel = ChannelFactory.create()
    def receive = {
      case message :WritableMessage => {
        println("will send WritableMessage " + message)
        val f = channel.write(message)
        f.addListener(new ChannelFutureListener() {
          override def operationComplete(fut: ChannelFuture) {
            println("actor: operation complete with fut="+fut)
          }
        })
        f.await
        println("sent")
      }
      case "toto" => println("toto")
      case s:String => println("received string=" + s)
      case _ => println("something else")
    }
  }

  class MongoActor extends Actor {
    import scala.collection.mutable.ListMap
    private val channelActor = context.actorOf(Props[ChannelActor], name = "mongoconnection1")
    private val map = ListMap[Int, String]()

    private val m = ListMap[Int, ActorRef]()

    override def receive = {
      case message: WritableMessage => {
        map += ((message.requestID, "mongoconnection1"))
        m += ((message.requestID, sender))
        println("now map is " + map)
        channelActor forward message
      }
      case message: ReadReply => {
        m.get(message.header.responseTo) match {
          case Some(_sender) => {
            println("ok, will send message="+message + " to sender " + _sender)
            _sender ! message
          }
          case None => println("oups. " + message.header.responseTo + " not found!")
        }
      }
      case _ => println("not supported")
    }
  }

  object ChannelFactory {
    import java.net.InetSocketAddress
    import java.util.concurrent.Executors

    val factory = new NioClientSocketChannelFactory(
      Executors.newCachedThreadPool,
      Executors.newCachedThreadPool
    )

    def create(host: String = "localhost", port: Int = 27017) = {
      val bootstrap = new ClientBootstrap(factory)

      bootstrap.setPipelineFactory(new ChannelPipelineFactory() {
        override def getPipeline :ChannelPipeline = {
          //Channels.pipeline(new MongoDecoder(), new MongoEncoder(), new MongoHandler())
          Channels.pipeline(new WritableMessageEncoder(), new ReplyDecoder(), new MongoHandler())
        }
      })

      bootstrap.setOption("tcpNoDelay", true)
      bootstrap.setOption("bufferFactory", new HeapChannelBufferFactory(java.nio.ByteOrder.LITTLE_ENDIAN))
      bootstrap.connect(new InetSocketAddress(host, port)).await.getChannel
    }
  }
}

object MongoSystem {
  val system = ActorSystem("mongosystem")
  val actor = system.actorOf(Props[actors.MongoActor], name = "router")

  def send(message: WritableMessage) = {
    import akka.dispatch.Await
    import akka.pattern.ask
    import akka.util.Timeout
    import akka.util.duration._

    implicit val timeout = Timeout(5 seconds)

    val future = actor ? message
    println("FUTURE is "+ future)
    val response = Await.result(future, timeout.duration).asInstanceOf[ReadReply]
    println("hey, got response! " + response)
      //println("find conn " + actor + " for responseTo " + readReply.header.responseTo)
    future
  }
}

object Client {
  def test {
    MongoSystem send message
  }

  def main(args: Array[String]) {
    test
  }

  val message = {
    import java.io._
    import de.undercouch.bson4jackson._
    import de.undercouch.bson4jackson.io._

    val factory = new BsonFactory()
 
    //serialize data
    val baos = new ByteArrayOutputStream();
    val gen = factory.createJsonGenerator(baos);
    gen.writeStartObject();
    gen.writeEndObject()
    gen.close()

    WritableMessage(109, 0, Query(0, "plugin.acoll", 0, 0), baos.toByteArray)
  }
}


package controllers

import play.api._
import play.api.mvc._
import play.api.data.Forms._
import play.api.data._
import play.api.Play.current
import play.api.libs._
import play.api.libs.iteratee._
import play.api.libs.concurrent._
import java.util.concurrent._
import scala.concurrent.stm._
import akka.util.duration._
import play.api.cache._
import play.api.libs.json._
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import java.util._
import collection.JavaConversions._

object Application extends Controller {

    val eventSource = Enumeratee.map[String] { msg => "data: " + msg + "\n\n" }

    def index() = Action {
        val uid = UUID.randomUUID().toString()
        val user = User.join(uid, "Anonymous", "")
        user.waiting()
        val state = restartUser(user.id)
        Ok( views.html.index( uid, state ) )
    }

    def feed(id: String) = Action { implicit request =>
        Option( User.users.get( id ) ).map { user =>
            Ok.feed( user.feedEnumerator.through( eventSource ) ).as( "text/event-stream" )
        }.getOrElse {
          NotFound("User not found with id " + id)
        }
    }

    def websocket(id: String) = WebSocket.async[Array[Byte]] { request =>
        Option( User.users.get( id ) ).map { user =>
          Promise.pure( ( user.inputCameraIteratee, user.outputBroadcastEnumerator.getPatchCord() ) )
        }.getOrElse {
          Promise.pure( ( Iteratee.ignore, Enumerator.eof ) )
        }
    }

    def next(id: String) = Action {
        User.findChat( id ).map { chat =>
            chat.stop()
            restartUser( chat.user1.id )
            restartUser( chat.user2.id )
            Ok("")
        }.getOrElse( NotFound("Chat not found for user with id " + id) )
    }

    def restartUser( id: String ) = {
        Option( User.users.get( id ) ).map { user =>
            user.waiting()
            if (User.waitingUsers.isEmpty()) {
              User.waitingUsers.offer(user)
              user.informWaiting()
              "waiting"
            } else {
              val otherChatter = User.waitingUsers.poll()
              val chat = Chat.register( user, otherChatter )
              chat.start()
              user.informNewChat(chat.id)
              otherChatter.informNewChat(chat.id)
              chat.id
            }
        }.get
    }
}

case class User(id: String, name: String = "Anonymous", description: String = "") {

    private val optionnalConsumer: AtomicReference[Option[User]] = new AtomicReference(None)

    val inputCameraIteratee = Iteratee.foreach[Array[Byte]] ( _ match {
        case message : Array[Byte] => {
            optionnalConsumer.get().foreach { consumer =>
                consumer.pushFrame( message )
            }
        }
    }).mapDone({ in =>
        User.removeUser( id )
        optionnalConsumer.get().foreach { consumer =>
            Application.restartUser( consumer.id )
        }
        outputEnumerator.close()
        outputBroadcastEnumerator.close()
    })

    val feedEnumerator = Enumerator.imperative[String]() 

    private val outputEnumerator = Enumerator.imperative[Array[Byte]]() 

    val outputBroadcastEnumerator = Concurrent.hub[Array[Byte]]( outputEnumerator )

    def pushFrame(frame: Array[Byte]) = outputEnumerator.push( frame )

    def waiting() = optionnalConsumer.set( None )

    def connectToNewChatter( user: User ) = optionnalConsumer.set( Some( user ) )

    def informWaiting() = feedEnumerator.push( "waiting" )

    def informNewChat(id: String) = feedEnumerator.push( id )
}

object User {

    val users = new ConcurrentHashMap[String, User]()

    val waitingUsers = new ConcurrentLinkedQueue[User]()

    def join(uid: String, name: String, desc: String) = {
        val user = User(uid, name, desc)
        if ( !users.containsKey( uid ) ) {
            users.putIfAbsent( uid, user )
        }
        user
    }

    def removeUser(id: String) = {
        if ( users.containsKey( id ) ) {
            users.remove( id )
        }
    }

    def findChat(id: String): Option[Chat] = {
        for (chat <- Chat.chats.values()) {
            if(chat.user1.id.equals(id)) return Some(chat)
            if(chat.user2.id.equals(id)) return Some(chat)
        }
        None
    }
}

case class Chat(id: String, user1: User, user2: User) {
    def start() {
        user1.connectToNewChatter( user2 )
        user2.connectToNewChatter( user1 )
    }
    def stop() = Chat.remove(id)
}

object Chat {  

    val chats = new ConcurrentHashMap[String, Chat]()

    def register(user1: User, user2: User) = {
        val uid = UUID.randomUUID().toString()
        val chat = Chat(uid, user1, user2)
        if (!chats.containsKey(uid)) {
            chats.putIfAbsent(uid, chat)
        }
        chat
    }
    def remove(id: String) = if (chats.containsKey(id)) chats.remove(id)
}
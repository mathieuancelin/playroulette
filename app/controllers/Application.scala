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
import scala.collection.mutable._
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong
import java.util.UUID

object Application extends Controller {

    val eventSource = Enumeratee.map[String] { msg => "data: " + msg + "\n\n" }

    def index() = Action {
        val uid = UUID.randomUUID().toString()
        val user = User.join( uid, "Anonymous-" + User.userNumber.incrementAndGet(), "An anonymous user" )
        user.waiting( )
        val state = User.restart( user.id )
        Ok( views.html.index( uid, state ) )
    }

    def feed(id: String) = Action {
        User.users.get( id ).map { user =>
            Ok.feed( user.feedEnumerator.through( eventSource ) ).as( "text/event-stream" )
        }.getOrElse {
            NotFound( "User not found with id " + id )
        }
    }

    def websocket( id: String ) = WebSocket.async[Array[Byte]] { implicit request =>
        User.users.get( id ).map { user =>
            Promise.pure( ( user.inputCameraIteratee, user.outputBroadcastEnumerator.getPatchCord() ) )
        }.getOrElse {
            Promise.pure( ( Iteratee.ignore, Enumerator.eof ) )
        }
    }

    def next( id: String ) = Action {
        Chat.findChat( id ).map { chat =>
            chat.stop()
            User.restart( chat.user1.id )
            User.restart( chat.user2.id )
            Ok( "" )
        }.getOrElse( NotFound( "Chat not found for user with id " + id ) )
    }

    def userList() = Action {
        Ok( views.html.users( User.users.map { user => user._2 }.toList ) )
    }

    def user( id: String ) = Action {
        User.users.get( id ).map { user =>
            Ok( views.html.user( user ) )
        }.getOrElse {
            NotFound( "User not found with id " + id )
        }
    }

    def userCam( id: String ) = WebSocket.async[Array[Byte]] { implicit request =>
        User.users.get( id ).map { user =>
            Promise.pure( ( Iteratee.ignore[Array[Byte]], user.outputUserBroadcastEnumerator.getPatchCord() ) )
        }.getOrElse {
            Promise.pure( ( Iteratee.ignore, Enumerator.eof ) )
        }
    }
}

case class User(id: String, name: String = "Anonymous", description: String = "") {

    private val optionnalConsumer: AtomicReference[Option[User]] = new AtomicReference(None)

    val inputCameraIteratee = Iteratee.foreach[Array[Byte]] ( _ match {
        case message : Array[Byte] => {
            optionnalConsumer.get().map { consumer =>
                consumer.pushFrame( message )
            }
            outputUserEnumerator.push( message )
        }
    }).mapDone({ in =>
        User.removeUser( id )
        outputEnumerator.close()
        outputBroadcastEnumerator.close()
        outputUserEnumerator.close()
        outputUserBroadcastEnumerator.close()
        optionnalConsumer.get().map { consumer =>
            User.restart( consumer.id )
        }
    })

    val feedEnumerator = Enumerator.imperative[String]() 

    private val outputUserEnumerator = Enumerator.imperative[Array[Byte]]() 
    val outputUserBroadcastEnumerator = Concurrent.hub[Array[Byte]]( outputUserEnumerator )

    private val outputEnumerator = Enumerator.imperative[Array[Byte]]() 
    val outputBroadcastEnumerator = Concurrent.hub[Array[Byte]]( outputEnumerator )

    def pushFrame(frame: Array[Byte]) = outputEnumerator.push( frame )

    def waiting() = optionnalConsumer.set( None )

    def connectToNewChatter( user: User ) = optionnalConsumer.set( Some( user ) )

    def informWaiting() = feedEnumerator.push( "waiting" )

    def informNewChat(id: String) = feedEnumerator.push( id )
}

object User {

    val userNumber = new AtomicLong( 0 )
    val users = HashMap.empty[String, User]
    val waitingUsers = new SynchronizedQueue[User]()

    def join( uid: String, name: String, desc: String ) = {
        val user = User( uid, name, desc )
        users.put( uid, user )
        user
    }

    def removeUser(id: String) = users.remove( id )

    def restart( id: String ) = {
        users.get( id ).map { user =>
            user.waiting()
            if ( waitingUsers.isEmpty ) {
                waitingUsers += user
                user.informWaiting()
                "waiting"
            } else {
                val chat = Chat.register( user, waitingUsers.dequeue() )
                chat.start()
                chat.user1.informNewChat( chat.id )
                chat.user2.informNewChat( chat.id )
                chat.id 
            }
        }.getOrElse( "waiting" ) 
    }
}

case class Chat( id: String, user1: User, user2: User ) {
    def start() {
        user1.connectToNewChatter( user2 )
        user2.connectToNewChatter( user1 )
    }
    def stop() = Chat.remove( id) 
}

object Chat {  
    val chats = HashMap.empty[String, Chat]

    def register( user1: User, user2: User ) = {
        val chat = Chat( UUID.randomUUID().toString(), user1, user2 )
        chats.put( chat.id, chat )
        chat
    }
    def remove( id: String ) = chats.remove( id ) 

    def findChat( id: String ): Option[Chat] = {
        Chat.chats.values.filter { chat => ( chat.user1.id == id ) || ( chat.user2.id == id ) }.headOption
    }
}
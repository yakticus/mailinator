package yakticus.mailinator

import akka.actor.{ ActorRef, ActorSystem }
import akka.event.Logging
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.server.directives.MethodDirectives.{ delete, get, post }
import akka.http.scaladsl.server.directives.PathDirectives.path
import akka.http.scaladsl.server.directives.RouteDirectives.complete
import akka.pattern.ask
import akka.util.Timeout
import yakticus.mailinator.EmailRegistryActor._
import yakticus.mailinator.MailboxActor._

import scala.concurrent.duration._

/**
  * Contains the routes for the HTTP server. Namely:
  *
  * POST /mailboxes: Create a new, random email address.
  * POST /mailboxes/{email address}/messages: Create a new message for a specific email address.
  * GET /mailboxes/{email address}/messages: Retrieve an index of messages sent to an email address, including sender,
  *     subject, and id, in recency order. Support cursor-based pagination through the index.
  * GET /mailboxes/{email address}/messages/{message id}: Retrieve a specific message by id.
  * DELETE /mailboxes/{email address}: Delete a specific email address and any associated messages.
  * DELETE /mailboxes/{email address}/messages/{message id}: Delete a specific message by id.
  */
trait EmailApiRoutes extends JsonSupport {

  implicit def system: ActorSystem

  lazy val log = Logging(system, classOf[EmailApiRoutes])

  // other dependencies that UserRoutes use
  def emailRegistryActor: ActorRef

  // Required by the `ask` (?) method below
  // TODO: obtain the timeout from the system's configuration
  implicit lazy val timeout: Timeout = Timeout(5.seconds)

  /**
    * The routes for the entire server. Breaks down into individual routes per request
    */
  lazy val userRoutes: Route =
    pathPrefix("mailboxes") {
      pathEnd {
        createMailbox
      } ~
        pathPrefix(Segment) { email =>
          val address = Address(email)
          pathEnd {
            deleteMailbox(address)
          } ~
            pathPrefix("messages") {
              pathEnd {
                getMessageListing(address) ~
                  createMessage(address) ~
                  path(Segment) {
                    messageId =>
                        getMessage(address, messageId) ~
                          deleteMessage(address, messageId)
                  }
              }
            }
        }
    }


  /**
    * Implements the route
    * POST /mailboxes: Create a new, random email address
    */
  val createMailbox: Route = post {
    // make new email user
    val userCreated = (emailRegistryActor ? CreateEmail).mapTo[Address]
    onSuccess(userCreated) {
      created =>
        log.info(s"Created email: ${created.address}")
        complete((StatusCodes.Created, created))
    }
  }

  /**
    * Implements the route
    * DELETE /mailboxes/{email address}: Delete a specific email address and any associated messages
    *
    * @param address the address of the mailbox to delete
    * @return the route to delete the mailbox
    */
  def deleteMailbox(address: Address): Route = //
    delete {
      val emailDeleted = (emailRegistryActor ? DeleteEmail(address)).mapTo[EmailDeleted.type]
      onSuccess(emailDeleted) { _ =>
        log.info(s"Deleted email ${address.address}")
        complete(StatusCodes.OK)
      }
    }
  /**
    * Implements the route
    * GET /mailboxes/{email address}/messages: Retrieve an index of messages sent to an email address,
    * including sender, subject, and id, in recency order.
    *
    * Supports cursor-based pagination through the index.
    * @param address the address to get the mailbox listing for
    * @return the route to get the listing for the mailbox
    */
  def getMessageListing(address: Address): Route =
    get {
      parameters('cursor.?, 'size.?) {
        (maybeCursor, maybeSize) =>
          // TODO: cast to Int for size param could cause an exception
          val message = MailboxMessage(address, GetMessageListing(maybeCursor, maybeSize.map(_.toInt)))
          onSuccess(emailRegistryActor ? message) {
            case messageListing: MessageListing =>
              complete(StatusCodes.OK, messageListing)
            case NoSuchMailbox =>
              complete(StatusCodes.NotFound)
          }
      }
    }
  /**
    * Implements the route
    * POST /mailboxes/{email address}/messages: Create a new message for a specific email address
    *
    * @param address the recipient of the message
    * @return the route to create the message
    */
  def createMessage(address: Address): Route = post {
      entity(as[CreateMessage]) {
        newEmail =>
          val actorMessage = MailboxMessage(address, newEmail)
          onSuccess(emailRegistryActor ? actorMessage) {
            case created: MessageCreated =>
              complete(StatusCodes.Created, created)
            case NoSuchMailbox =>
              complete(StatusCodes.NotFound)
          }
      }
    }

  /**
    * Implements the route
    * GET /mailboxes/{email address}/messages/{message id}: Retrieve a specific message by id
    *
    * @param address address to get message for
    * @param id ID to get message for
    * @return the route to get the message
    */
  def getMessage(address: Address, id: String): Route = get {
    // GET /mailboxes/{email address}/messages/{message id}: Retrieve a specific message by id
    val actorMessage = MailboxMessage(address, GetMessage(id))
    onSuccess(emailRegistryActor ? actorMessage) {
      case Some(msg: Message) =>
        complete(StatusCodes.OK, msg)
      case None =>
        complete(StatusCodes.NotFound)
      case NoSuchMailbox =>
        complete(StatusCodes.NotFound)
    }
  }

  /**
    * Implements the route
    * DELETE /mailboxes/{email address}/messages/{message id}: Delete a specific message by id
    *
    * @param address the address to delete message for
    * @param id the ID of the message to delete
    * @return the route to delete the message
    */
  def deleteMessage(address: Address, id: String): Route = delete {
    //
    val actorMessage = MailboxMessage(address, DeleteMessage(id))
    // note: we're swallowing NoSuchMailbox message,so there's no error at all
    onSuccess(emailRegistryActor ? actorMessage) { _ => complete(StatusCodes.OK) }
  }
}

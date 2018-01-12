package yakticus.mailinator

import akka.actor.{Actor, ActorLogging, ActorRef, Props, Terminated}

final case class Address(address: String)

/**
  * Contains message objects to send to the actor, plus factory method for it
  */
object EmailRegistryActor {
  /**
    * Create a new, random email address
    */
  case object CreateEmail

  /**
    * Delete a specific email address and any associated messages
    *
    * @param address the address to delete
    */
  case class DeleteEmail(address: Address)
  /**
    * generic response when an email deleted request is sent
    */
  case object EmailDeleted

  /**
    *
    * any message to be forwarded to a specific mailbox
    * @param mailbox the address of the mailbox to send the message to
    * @param message the message to send
    */
  case class MailboxMessage(mailbox: Address, message: Any)
  /**
    * Respond with this if a MailboxMessage is received but no corresponding mailbox is found
    */
  case object NoSuchMailbox

  def props(domain: String): Props = Props(classOf[EmailRegistryActor], domain)

}

/**
  * Registry of all mailboxes. Doubles as supervisor for mailbox actors.
  *
  * @param domain the suffix for email addresses (e.g., @mailinator.com)
  */
class EmailRegistryActor(domain: String) extends Actor with ActorLogging {
  import EmailRegistryActor._

  var mailboxActors = Map.empty[Address, ActorRef]

  /**
    * Increment each time a new mailbox is created. It is the unique part of the email address
    */
  var mailboxCounter = 0l

  private def forward(to: Address, message: Any): Unit = {
    mailboxActors.get(to) match {
      case Some(ref) =>
        // forwards the request to the mailbox actor along with original sender
        ref.forward(message)
      case _ =>
        sender() ! NoSuchMailbox
    }
  }

  override def receive: Receive = {
    case CreateEmail =>

      //      val randomUsername = UUID.randomUUID().toString.replace('-', '_')
      val randomUsername = s"mailbox_$mailboxCounter"
      mailboxCounter += 1
      val address = Address(randomUsername + domain)

      // now, create a new actor for the mailbox
      // its name = the email address
      val mailboxActor = context.actorOf(MailboxActor.props, address.address)
      context.watch(mailboxActor)
      mailboxActors = mailboxActors + (address -> mailboxActor)
      sender() ! address

    case DeleteEmail(address) =>
      // tell the mailbox actor (child) to stop
      // this (registry) actor is listening for termination
      mailboxActors.get(address).foreach {
        ref => context.stop(ref)
      }
      mailboxActors -= address
      // note: when the mailbox actor finally terminates, a Terminated message
      // will be received, at which point the ActorRef for the mailbox is removed
      // from the registry
      sender() ! EmailDeleted
    case t: Terminated =>
      // in case a mailbox actor is terminated (something went wrong)
      // remove it from the map.
      val email = t.actor.path.name
      mailboxActors -= Address(email)

    case MailboxMessage(to, msg) =>
      forward(to, msg)
  }
}


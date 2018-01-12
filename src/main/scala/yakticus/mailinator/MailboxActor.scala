package yakticus.mailinator

import akka.actor.{ Actor, ActorLogging, Props }

import scala.collection.immutable.TreeMap

object MailboxActor {
  // Retrieve an index of messages sent to this mailbox, including sender, subject, and id, in recency order
  case class GetMessageListing(maybeCursor: Option[String], maybeSize: Option[Int])
  case class MessageListing(cursor: Option[String], messages: Seq[MessageSummary])
  final case class MessageSummary(id: String, from: Address, subject: String, timestamp: Long)

  // Create a new message for this mailbox
  case class CreateMessage(from: Address, subject: String, body: String)
  case class MessageCreated(id: String)

  // Retrieve a specific message by id
  // 404 if no such message
  case class GetMessage(id: String)
  sealed trait MessageResponse
  case class Message(summary: MessageSummary, body: String) extends MessageResponse
  case object MessageNotFound extends MessageResponse

  // Delete a specific message by id
  case class DeleteMessage(id: String)
  case object MessageDeleted

  // default settings: page size = 10, use system time
  def props: Props = Props(classOf[MailboxActor], 10, { () => System.nanoTime() })
  def props(defaultPageSize: Int, timeInNs: () => Long): Props = Props(classOf[MailboxActor], timeInNs)
}

/**
 * Manages the mailbox for a single email address
 *
 * @param defaultPageSize number of messages to return in a single of response, unless otherwise specified
 *                        in the request
 * @param timeInNs function to get current time in nanoseconds (can be injected as a mock clock for testing
 *                 purposes)
 */
class MailboxActor(
  defaultPageSize: Int,
    timeInNs: () => Long
) extends Actor with ActorLogging {
  import MailboxActor._
  require(defaultPageSize > 0, s"expected page size > 0; was: $defaultPageSize")

  // requirements for mailbox data structure:
  // messages ordered most recent first
  // easily seek to a specific message ID, then:
  //  -- get the following N messages OR
  //  -- delete the message
  // create message -- add it first
  // delete all messages older than duration X

  // assumption: message ID reverse lexical order corresponds to most recent message first
  // create a set of messages, organized as a B-tree, sorted by message ID (most recent first)
  private[this] var mailbox = TreeMap.empty[String, Message](Ordering.String.reverse)

  override def receive: Receive = {
    // TODO: purge task with TTL (duration). Remove all messages older than some duration (e.g., 30 days)

    case CreateMessage(from, subject, body) =>
      // O(log N)
      val timestampNs = timeInNs()
      // received timestamp in nanoseconds since epoch doubles as message ID
      // while timestamp is not GUARANTEED to be unique, we assume that odds of processing two incoming emails
      // in the same nanosecond is vanishingly small
      // we could also use UUID or similar, but the standard impl employs a synchronized block
      // which would hurt performance with concurrent requests
      val id = timestampNs.toString
      // convert timestamp to seconds
      val timestampSec = timestampNs / 1000000
      val message = Message(MessageSummary(id, from, subject, timestampSec), body)
      mailbox += (id -> message)
    case GetMessageListing(maybeCursor, maybeSize) =>
      // O(S + log N), where S = number of elements to get
      val size = maybeSize.map {
        case s if s <= 0 => defaultPageSize
        case s => s
      }.getOrElse(defaultPageSize)

      val iter = maybeCursor match {
        case Some(cursor) =>
          // remove the first one because it's already been seen
          mailbox.iteratorFrom(cursor).drop(1)
        case None =>
          mailbox.iterator
      }
      val messages = iter.take(size).map(_._2.summary).toSeq
      val nextCursor = if (messages.size < size) None else Some(messages.last.id)

      sender() ! MessageListing(nextCursor, messages)
    case GetMessage(id) =>
      // O(log N)
      val message = mailbox.get(id)
      sender() ! message
    case DeleteMessage(id) =>
      // O(log N)
      mailbox -= id
      sender() ! MessageDeleted
  }
}

package yakticus.mailinator

import akka.actor.ActorRef
import akka.http.scaladsl.model._
import akka.http.scaladsl.server.Route
import akka.http.scaladsl.testkit.ScalatestRouteTest
import akka.pattern.ask
import org.scalacheck._
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.{ Matchers, WordSpec }
import yakticus.mailinator.EmailRegistryActor.{ CreateEmail, MailboxMessage }
import yakticus.mailinator.MailboxActor.{ CreateMessage, Message, MessageListing, MessageSummary }

import scala.concurrent.Future

class EmailApiRoutesSpec extends WordSpec with Matchers with ScalaFutures with ScalatestRouteTest
    with EmailApiRoutes {

  // Here we need to implement all the abstract members of EmailApiRoutes.
  // We use the real EmailRegistryActor to test it while we hit the Routes,
  // but we could "mock" it by implementing it in-place or by using a TestProbe()
  val domain = "mailinator.org"
  override val emailRegistryActor: ActorRef =
    system.actorOf(EmailRegistryActor.props(domain), "emailRegistry")

  lazy val routes: Route = userRoutes

  def doOnNewMailbox(f: Address => Unit): Unit = {
    // is this allowed from outside the actor system?
    val future = (emailRegistryActor ? CreateEmail).mapTo[Address]
    future.map {
      address =>
        f(address)
    }
  }

  def doOnNewMailboxOfSize(size: Int)(f: (Address, List[CreateMessage]) => Unit): Unit = {
    doOnNewMailbox {
      address =>

        // first, create messages, then call f
        // totally not how this should be used
        val messages = Gen.listOfN(size, Generators.createMessageGen).sample.get

        val results = messages.map {
          message =>
            emailRegistryActor ? MailboxMessage(address, message)
        }

        // combine all the futures into one so future completes when they all do
        // ignore failure of individual futures for now
        val seq = Future.sequence(results)

        seq.map {
          _ => f(address, messages)
        }
    }
  }

  def assertMessageRecencyOrder(messages: Seq[MessageSummary]): Unit = {
    messages.sliding(2).foreach {
      case Seq(newer, older) =>
        newer.timestamp should be >= older.timestamp
      case _ =>
    }

  }

  "EmailApiRoutes" should {
    "return 404 if no such email (GET /mailboxes/dummy@example.com)" in {
      val request = HttpRequest(uri = "/mailboxes/dummy@example.com/messages")

      request ~> routes ~> check {
        status should ===(StatusCodes.NotFound)
      }
    }

    "create new email (POST /mailboxes)" in {
      // note that there's no need for the host part in the uri:
      val request = HttpRequest(uri = "/mailboxes", method = HttpMethods.POST)

      request ~> routes ~> check {
        status should ===(StatusCodes.Created)

        // we expect the response to be json:
        contentType should ===(ContentTypes.`application/json`)

        // there should be some email address in there
        val body = entityAs[String]
        body should startWith("""{"address":""")
      }
    }

    "check empty mailbox (GET /mailboxes/{email}/messages)" in {
      doOnNewMailbox {
        address =>
          val getListing = HttpRequest(uri = s"/mailboxes/${address.address}/messages")
          getListing ~> routes ~> check {
            status should ===(StatusCodes.OK)
            val listing = entityAs[MessageListing]
            listing.cursor should be(empty)
            listing.messages should be(empty)
          }
      }
    }

    "be able to remove mailbox (DELETE /mailboxes/{email})" in {
      doOnNewMailbox {
        address =>
          val deleteMailbox = HttpRequest(uri = s"/mailboxes/${address.address}", method = HttpMethods.DELETE)
          deleteMailbox ~> routes ~> check {
            status should ===(StatusCodes.OK)
            val getListing = HttpRequest(uri = s"/mailboxes/${address.address}/messages")
            getListing ~> routes ~> check {
              status should ===(StatusCodes.NotFound)
            }
          }
      }
    }

    // TODO: property-based testing for different sizes/types of mailboxes
    "be able to get single-page listing" in {
      val mailboxSize = 5
      doOnNewMailboxOfSize(mailboxSize) {
        case (address, _) =>
          val pageSize = 10
          // when ALL messages are created, then check that we can retrieve the message listing
          val getListing = Get(s"/mailboxes/${address.address}/messages?size=$pageSize")
          getListing ~> routes ~> check {
            status should be(StatusCodes.OK)
            val listing = entityAs[MessageListing]
            listing.messages.size should be(mailboxSize)
            listing.cursor should be(empty)
            // confirm that each message is older than the last
            assertMessageRecencyOrder(listing.messages)
          }
      }
    }

    "be able to get multi-page listing" in {
      val mailboxSize = 48
      doOnNewMailboxOfSize(mailboxSize) {
        case (address, _) =>
          val pageSize = 5
          val getUri = s"/mailboxes/${address.address}/messages?size=$pageSize"

          Get(getUri) ~> routes ~> check {
            status should be(StatusCodes.OK)
            val firstListing = entityAs[MessageListing]
            firstListing.messages.size should be(pageSize)
            firstListing.cursor should not be empty

            Get(getUri + s"&cursor=${firstListing.cursor}") ~> routes ~> check {
              status should be(StatusCodes.OK)
              val nextListing = entityAs[MessageListing]
              nextListing.messages.size should be(pageSize)
              nextListing.cursor should not be empty
              // test for uniqueness
              val bothPages = firstListing.messages ++ nextListing.messages
              assertMessageRecencyOrder(bothPages)
              val allIds = bothPages.map(_.id)
              allIds.size should equal(allIds.toSet.size)
            }
          }
      }
    }

    "be able to get specific message" in {
      val mailboxSize = 123
      doOnNewMailboxOfSize(mailboxSize) {
        case (address, createMessageRequests) =>
          val pageSize = 50
          val getUri = s"/mailboxes/${address.address}/messages"

          Get(getUri + s"?size=$pageSize") ~> routes ~> check {
            status should be(StatusCodes.OK)
            val listing = entityAs[MessageListing]
            listing.messages.size should be(pageSize)
            val summary = listing.messages(pageSize / 2)
            // the message selected should be one from the original list
            val originalRequest = createMessageRequests.find(_.subject == summary.subject)
            originalRequest should not be empty

            Get(getUri + s"/${summary.id}") ~> routes ~> check {
              status should be(StatusCodes.OK)
              val message = entityAs[Message]
              message.body should be(originalRequest.get.body)
              message.summary.from should be(originalRequest.get.from)
            }
          }
      }
    }

    "try and fail to get non-existent message" in {
      doOnNewMailboxOfSize(5) {
        case (address, _) =>
          val getUri = s"/mailboxes/${address.address}/messages/some_bogus_id"
          Get(getUri) ~> routes ~> check {
            status should ===(StatusCodes.NotFound)
          }

      }
    }

  }
}

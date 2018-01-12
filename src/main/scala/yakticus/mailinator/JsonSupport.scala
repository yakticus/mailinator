package yakticus.mailinator

import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
import spray.json.{ DefaultJsonProtocol, RootJsonFormat }
import yakticus.mailinator.MailboxActor._

trait JsonSupport extends SprayJsonSupport {
  // import the default encoders for primitive types (Int, String, Lists etc)
  import DefaultJsonProtocol._

  //  implicit val userJsonFormat = jsonFormat3(User)
  //  implicit val usersJsonFormat = jsonFormat1(Users)

  implicit val addressJsonFormat: RootJsonFormat[Address] = jsonFormat1(Address)
  implicit val messageSummaryJsonFormat: RootJsonFormat[MessageSummary] = jsonFormat4(MessageSummary)
  implicit val messageListingJsonFormat: RootJsonFormat[MessageListing] = jsonFormat2(MessageListing)
  implicit val messageJsonFormat: RootJsonFormat[Message] = jsonFormat2(Message)
  implicit val createmessageJsonFormat: RootJsonFormat[CreateMessage] = jsonFormat3(CreateMessage)
  implicit val messageCreatedJsonFormat: RootJsonFormat[MessageCreated] = jsonFormat1(MessageCreated)
}

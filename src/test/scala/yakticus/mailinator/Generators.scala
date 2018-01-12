package yakticus.mailinator

import org.scalacheck._
import yakticus.mailinator.MailboxActor.CreateMessage

object Generators {

  val domains = List("gmail.com", "yahoo.com", "outlook.com", "ymail.com", "mailinator.com", "microsoft.com")
  // generate some random email addresses
  val emailAddressGen: Gen[String] = Gen.zip(Gen.alphaNumStr, Gen.oneOf(domains)).map {
    case (username, domain) => s"$username@$domain"
  }

  // generates create message requests
  val createMessageGen: Gen[CreateMessage] = for {
    from <- emailAddressGen
    subject <- Gen.alphaNumStr
    body <- Gen.alphaLowerStr
  } yield CreateMessage(Address(from), subject, body)

  // generate arbitrary number of messages
  val createMessagesListGen: Gen[List[CreateMessage]] = Gen.listOf(createMessageGen)
}

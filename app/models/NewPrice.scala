package models

import play.api.libs.json.Json

final case class NewPrice(
    tokenId: Option[String],
    amount: Long
)

object NewPrice {
  implicit val json = Json.format[NewPrice]
}

package models

import play.api.libs.json.Json

final case class AddressList(
    addresses: Seq[String]
)

object AddressList {
  implicit val json = Json.format[AddressList]
}

package models

import play.api.libs.json.Json

final case class UserAuthRequest(
    address: String
)

object UserAuthRequest {
  implicit val json = Json.format[UserAuthRequest]
}

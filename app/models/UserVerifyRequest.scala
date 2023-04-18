package models

import play.api.libs.json.Json

final case class UserVerifyRequest(
    signedMessage: String,
    proof: String
)

object UserVerifyRequest {
  implicit val json = Json.format[UserVerifyRequest]
}

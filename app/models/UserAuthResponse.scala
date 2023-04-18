package models

import play.api.libs.json.Json

final case class UserAuthResponse(
    address: String,
    signingMessage: String,
    verificationId: String,
    verificationUrl: String
)

object UserAuthResponse {
  implicit val json = Json.format[UserAuthResponse]
}

package models

import play.api.libs.json.Json

final case class UserVerifyResponse(
    address: String,
    isVerified: Boolean,
    verificationToken: Option[String]
)

object UserVerifyResponse {
  implicit val json = Json.format[UserVerifyResponse]
}

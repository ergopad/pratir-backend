package models

import java.util.UUID
import play.api.libs.json.Json

final case class AuthRequest(
    id: UUID,
    address: String,
    signingMessage: String,
    verificationToken: Option[String]
)

object AuthRequest {
  implicit val json = Json.format[AuthRequest]
}

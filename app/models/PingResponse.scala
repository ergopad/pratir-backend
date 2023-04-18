package models

import play.api.libs.json.Json

final case class PingResponse(
    status: String,
    message: String
)

object PingResponse {
  implicit val json = Json.format[PingResponse]
}

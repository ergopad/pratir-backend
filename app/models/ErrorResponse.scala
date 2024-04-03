package models

import play.api.libs.json.Json

final case class ErrorResponse(
    code: Int,
    error: String,
    message: String
)

object ErrorResponse {
  implicit val json = Json.format[ErrorResponse]
}

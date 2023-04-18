package models

import play.api.libs.json.Json

final case class FileUploadResponse(
    status: String,
    message: String,
    url: Option[String]
)

object FileUploadResponse {
  implicit val json = Json.format[FileUploadResponse]
}

package models

final case class FileUploadResponse(
    status: String,
    message: String,
    url: Option[String]
)

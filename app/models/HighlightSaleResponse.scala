package models

import java.util.UUID
import play.api.libs.json.Json

final case class HighlightSaleResponse(
    status: String,
    message: String,
    id: Option[UUID]
)

object HighlightSaleResponse {
  implicit val json = Json.format[HighlightSaleResponse]
}

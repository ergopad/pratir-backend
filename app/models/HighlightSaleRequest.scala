package models

import java.util.UUID
import play.api.libs.json.Json

final case class HighlightSaleRequest(
    saleId: UUID,
    verificationToken: String
)

object HighlightSaleRequest {
  implicit val json = Json.format[HighlightSaleRequest]
}

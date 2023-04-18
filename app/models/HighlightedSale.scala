package models

import java.util.UUID
import play.api.libs.json.Json

final case class HighlightedSale(
    id: UUID,
    saleId: UUID
)

object HighlightedSale {
  implicit val json = Json.format[HighlightedSale]
}

package models

import java.util.UUID
import play.api.libs.json.Json

final case class BuySaleResponse(
    saleId: UUID,
    packId: UUID,
    orderId: UUID
)

object BuySaleResponse {
  implicit val json = Json.format[BuySaleResponse]
}

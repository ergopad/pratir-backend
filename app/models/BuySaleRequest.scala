package models

import java.util.UUID
import play.api.libs.json.Json

final case class BuySaleRequest(
    saleId: UUID,
    packRequests: Array[BuyPackRequest]
)

object BuySaleRequest {
  implicit val json = Json.format[BuySaleRequest]
}

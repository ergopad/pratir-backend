package models

import java.util.UUID
import play.api.libs.json.Json

final case class BuyPackRequest(
    packId: UUID,
    currencyTokenId: String,
    count: Int
)

object BuyPackRequest {
  implicit val json = Json.format[BuyPackRequest]
}

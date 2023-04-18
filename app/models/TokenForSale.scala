package models

import java.util.UUID
import play.api.libs.json.Json

final case class TokenForSale(
    id: UUID,
    tokenId: String,
    amount: Int,
    originalAmount: Int,
    rarity: String,
    saleId: UUID
)

object TokenForSale {
  implicit val json = Json.format[TokenForSale]
}

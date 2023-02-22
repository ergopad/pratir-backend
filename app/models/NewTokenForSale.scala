package models

import play.api.libs.json.Json

final case class NewTokenForSale(
    tokenId: String,
    amount: Int,
    rarity: String
)

object NewTokenForSale {
    implicit val newTokenForSaleJson = Json.format[NewTokenForSale]
}

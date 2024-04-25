package models

import play.api.libs.json.Json

final case class TokenSaleStats(
    tokenId: String,
    sold: Int,
    remaining: Int
)

object TokenSaleStats {
  implicit val json = Json.format[TokenSaleStats]
}

final case class SaleStats(
    sold: Int,
    remaining: Int,
    tokenStats: Seq[TokenSaleStats]
)

object SaleStats {
  implicit val json = Json.format[SaleStats]
}

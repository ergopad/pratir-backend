package models

import play.api.libs.json.Json

final case class BuyResponse(
    unsigned: MUnsignedTransactionResponse,
    orders: Seq[BuySaleResponse]
)

object BuyResponse {
  implicit val json = Json.format[BuyResponse]
}

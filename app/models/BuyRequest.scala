package models

import play.api.libs.json.Json

final case class BuyRequest(
    userWallet: Array[String],
    targetAddress: String,
    requests: Array[BuySaleRequest],
    txType: String
)

object BuyRequest {
  implicit val json = Json.format[BuyRequest]
}

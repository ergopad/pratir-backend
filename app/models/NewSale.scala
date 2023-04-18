package models

import java.time.Instant
import play.api.libs.json.Json

final case class NewSale(
    name: String,
    description: String,
    startTime: Instant,
    endTime: Instant,
    sellerWallet: String,
    password: String,
    packs: Array[NewPack],
    tokens: Array[NewTokenForSale],
    sourceAddresses: Array[String]
)

object NewSale {
  implicit val json = Json.format[NewSale]
}

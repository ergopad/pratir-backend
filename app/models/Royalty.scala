package models

import play.api.libs.json.Json

final case class Royalty(
    address: String,
    royaltyPct: Int
) 

object Royalty {
    implicit val json = Json.format[Royalty]
}

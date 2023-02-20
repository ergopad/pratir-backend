package models

import play.api.libs.json.Json

final case class PackRarity(
    rarity: String,
    odds: Double
)

object PackRarity {
    implicit val json = Json.format[PackRarity]
}
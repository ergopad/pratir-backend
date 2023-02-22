package models

import play.api.libs.json.Json

final case class PackRarity(
    rarity: String,
    odds: Double
)

object PackRarity {
    implicit val json = Json.format[PackRarity]
}

final case class AvailableRarity(
    rarity: String,
    description: String,
    image: String
)

object AvailableRarity {
    implicit val json = Json.format[AvailableRarity]
}
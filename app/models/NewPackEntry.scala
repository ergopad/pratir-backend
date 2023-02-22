package models

import play.api.libs.json.JsValue
import play.api.libs.json.Json

final case class NewPackEntry(
    rarity: Seq[PackRarity],
    amount: Int
)

object NewPackEntry {
    implicit val json = Json.format[NewPackEntry]
}

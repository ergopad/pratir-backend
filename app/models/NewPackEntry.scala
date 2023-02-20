package models

import play.api.libs.json.JsValue

final case class NewPackEntry(
    rarity: JsValue,
    amount: Int
)

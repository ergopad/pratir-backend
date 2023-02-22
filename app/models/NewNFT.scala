package models

import java.util.UUID
import play.api.libs.json.JsValue
import play.api.libs.json.Json

final case class NewNFT(
    collectionId: UUID,
    amount: Long,
    name: String,
    image: String,
    description: String,
    traits: Seq[Trait],
    rarity: String,
    explicit: Boolean,
    royalty: Seq[Royalty]
)

object NewNFT {
    implicit val newNFTJson = Json.format[NewNFT]
}

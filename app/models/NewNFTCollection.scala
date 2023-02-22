package models

import java.util.UUID
import play.api.libs.json.JsValue
import play.api.libs.json.Json

final case class NewNFTCollection(
    artistId: UUID,
    name: String,
    description: String,
    bannerImageUrl: String,
    featuredImageUrl: String,
    collectionLogoUrl: String,
    category: String,
    mintingExpiry: Long, //unix timestamp of last date of expiry. If no expiry, must be -1. May not be undefined
    rarities: Seq[AvailableRarity],
    availableTraits: Seq[AvailableTrait],
    saleId: Option[UUID]
)

object NewNFTCollection {
    implicit val newCollectionJson = Json.format[NewNFTCollection]
}

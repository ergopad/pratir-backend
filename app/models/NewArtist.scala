package models

import play.api.libs.json.JsValue
import play.api.libs.json.Json

final case class NewArtist(
    address: String,
    name: String,
    website: String,
    tagline: String,
    avatarUrl: String,
    bannerUrl: String,
    social: JsValue
)

object NewArtist {
    implicit val newArtistJson = Json.format[NewArtist]
}

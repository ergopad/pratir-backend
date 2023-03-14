package models

import java.util.UUID
import play.api.libs.json.JsValue

final case class User(
    id: UUID,
    address: String,
    name: String,
    pfpUrl: String,
    bannerUrl: String,
    tagline: String,
    website: String,
    socials: JsValue
)

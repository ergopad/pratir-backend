package models

import play.api.libs.json.JsValue

final case class UpdateUser(
    id: String,
    address: String,
    name: String,
    pfpUrl: String,
    bannerUrl: String,
    tagline: String,
    website: String,
    socials: JsValue,
    verificationToken: String
)

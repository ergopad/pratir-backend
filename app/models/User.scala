package models

import java.util.UUID
import play.api.libs.json.JsValue
import play.api.libs.json.Json

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

object User {
  implicit val json = Json.format[User]
}

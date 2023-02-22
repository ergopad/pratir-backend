package models

import play.api.libs.json.Json

final case class Social(
    socialNetwork: String,
    url: String
)

object Social {
    implicit val json = Json.format[Social]
}

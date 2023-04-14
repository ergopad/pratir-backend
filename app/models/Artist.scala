package models

import java.util.UUID
import play.api.libs.json.JsValue
import java.time.Instant
import play.api.libs.json.Json

final case class Artist(
    id: UUID,
    address: String,
    name: String,
    website: String,
    tagline: String,
    avatarUrl: String,
    bannerUrl: String,
    social: JsValue,
    createdAt: Instant,
    updatedAt: Instant
) {
  def getSocials: Seq[Social] = {
    val socials: Option[Seq[Social]] =
      Json.fromJson[Seq[Social]](social).asOpt

    socials match {
      case None            => Array[Social]()
      case Some(mySocials) => mySocials
    }
  }
}

object Artist {
  implicit val json = Json.format[Artist]
}

package models

import java.time.Instant
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
    socials: JsValue,
    createdAt: Instant,
    updatedAt: Instant
) {
  def getSocials: Seq[Social] = {
    val socialsList: Option[Seq[Social]] =
      Json.fromJson[Seq[Social]](socials).asOpt

    socialsList match {
      case None            => Array[Social]()
      case Some(mySocials) => mySocials
    }
  }
}

object User {
  implicit val json = Json.format[User]
}

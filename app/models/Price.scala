package models

import java.util.UUID
import play.api.libs.json.Json

final case class Price(
    id: UUID,
    tokenId: String,
    amount: Long,
    packId: UUID
)

object Price {
  implicit val json = Json.format[Price]
}

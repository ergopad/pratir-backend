package models

import java.util.UUID
import play.api.libs.json.Json

final case class Pack(
    id: UUID,
    name: String,
    image: String,
    saleId: UUID
)

object Pack {
  implicit val json = Json.format[Pack]
}

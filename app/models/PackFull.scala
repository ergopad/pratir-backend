package models

import java.util.UUID
import play.api.libs.json.Json

final case class PackFull(
    id: UUID,
    name: String,
    image: String,
    price: Array[Price],
    content: Array[PackEntry]
)

object PackFull {
  implicit val json = Json.format[PackFull]
}

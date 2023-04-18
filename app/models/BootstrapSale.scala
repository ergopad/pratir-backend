package models

import java.util.UUID
import play.api.libs.json.Json

final case class BootstrapSale(
    sourceAddresses: Array[String],
    saleId: UUID
)

object BootstrapSale {
  implicit val json = Json.format[BootstrapSale]
}

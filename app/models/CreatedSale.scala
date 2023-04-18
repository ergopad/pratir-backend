package models

import play.api.libs.json.Json

final case class CreatedSale(
    sale: SaleFull,
    bootStrapTx: Option[MUnsignedTransaction]
)

object CreatedSale {
  implicit val json = Json.format[CreatedSale]
}

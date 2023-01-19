package models

import java.util.UUID

final case class BootstrapSale(
    sourceAddresses: Array[String],
    saleId: UUID
)

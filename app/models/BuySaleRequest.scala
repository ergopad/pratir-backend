package models

import java.util.UUID

final case class BuySaleRequest(
    saleId: UUID,
    packRequests: Array[BuyPackRequest]
)

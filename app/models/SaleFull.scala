package models

import java.time.Instant
import java.util.UUID

final case class SaleFull(
    id: UUID,
    name: String, 
    description: String, 
    startTime: Instant, 
    endTime: Instant, 
    sellerWallet: String,
    saleWallet: String,
    packs: Array[PackFull],
    tokens: Array[TokenForSale],
    initialNanoErgFee: Long,
    saleFeePct: Int
)

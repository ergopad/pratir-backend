package models

import java.util.UUID

final case class TokenForSale(
    id: UUID,
    tokenId: String,
    amount: Int,
    originalAmount: Int,
    rarity: String,
    saleId: UUID
)

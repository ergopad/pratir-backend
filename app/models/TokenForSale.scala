package models

import java.util.UUID

final case class TokenForSale(
    id: UUID,
    tokenId: String,
    amount: Int,
    rarity: Double,
    category: String,
    saleId: UUID
)

package models

final case class NewTokenForSale(
    tokenId: String,
    amount: Int,
    rarity: Double,
    category: String
)

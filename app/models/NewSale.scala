package models

import java.time.Instant

final case class NewSale(
    name: String, 
    description: String, 
    startTime: Instant, 
    endTime: Instant, 
    sellerWallet: String,
    packs: Array[NewPack],
    tokens: Array[NewTokenForSale],
    sourceAddresses: Array[String]
)

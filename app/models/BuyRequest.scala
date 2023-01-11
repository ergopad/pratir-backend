package models

final case class BuyRequest(
    userWallet: Array[String],
    targetAddress: String,
    requests: Array[BuySaleRequest],
    txType: String
)

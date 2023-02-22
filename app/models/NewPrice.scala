package models

final case class NewPrice(
    tokenId: Option[String],
    amount: Long
)

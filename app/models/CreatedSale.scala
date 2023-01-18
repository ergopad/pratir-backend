package models

final case class CreatedSale(
    sale: SaleFull,
    bootStrapTx: Option[MUnsignedTransaction]
)

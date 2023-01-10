package models

final case class NewPack(
    name: String,
    price: Array[NewPrice],
    content: Array[NewPackEntry]
)

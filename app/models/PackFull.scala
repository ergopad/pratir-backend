package models

import java.util.UUID

final case class PackFull(
    id: UUID,
    name: String,
    image: String, 
    price: Array[Price],
    content: Array[PackEntry]
)

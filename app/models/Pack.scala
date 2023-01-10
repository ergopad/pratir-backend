package models

import java.util.UUID

final case class Pack(
    id: UUID,
    name: String,
    saleId: UUID
)

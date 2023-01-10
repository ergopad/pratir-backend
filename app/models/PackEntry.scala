package models

import java.util.UUID

final case class PackEntry(
    id: UUID,
    category: String,
    amount: Int,
    packId: UUID
)

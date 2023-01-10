package models

import java.util.UUID

final case class Price(
    id: UUID,
    tokenId: String,
    amount: Long,
    packId: UUID
)

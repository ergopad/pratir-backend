package models

import java.util.UUID

final case class BuyPackRequest(
    packId: UUID,
    count: Int
)

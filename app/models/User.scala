package models

import java.util.UUID

final case class User(
    id: UUID,
    address: String,
    name: String,
    pfpUrl: String,
    tagline: String
)

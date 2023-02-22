package models

import java.util.UUID

final case class AuthRequest(
    id: UUID,
    address: String,
    signingMessage: String,
    verificationToken: Option[String]
)

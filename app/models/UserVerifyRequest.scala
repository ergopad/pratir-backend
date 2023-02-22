package models

final case class UserVerifyRequest(
    signedMessage: String,
    proof: String
)

package models

final case class UserAuthResponse(
    address: String,
    signingMessage: String,
    verificationId: String,
    verificationUrl: String
)

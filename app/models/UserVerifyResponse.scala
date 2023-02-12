package models

final case class UserVerifyResponse(
    address: String,
    isVerified: Boolean,
    verificationToken: Option[String]
)

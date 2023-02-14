package models

final case class UpdateUser(
    id: String,
    address: String,
    name: String,
    pfpUrl: String,
    tagline: String,
    verificationToken: String
)

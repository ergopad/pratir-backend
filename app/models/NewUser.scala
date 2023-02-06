package models

final case class NewUser(
    address: String,
    name: String,
    pfpUrl: String,
    tagline: String
)

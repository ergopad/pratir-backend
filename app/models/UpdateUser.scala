package models

// todo: add ergoauth temp token
final case class UpdateUser(
    id: String,
    address: String,
    name: String,
    pfpUrl: String,
    tagline: String
)

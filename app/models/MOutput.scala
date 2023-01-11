package models

final case class MOutput(
    value: String,
    ergoTree: String,
    assets: Array[MToken],
    additionalRegisters: Map[String, String],
    creationHeight: Int
)

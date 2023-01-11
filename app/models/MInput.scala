package models

import scala.collection.mutable.HashMap

final case class MInput(
  extension: Map[String, String],
  boxId: String,
  value: String,
  ergoTree: String,
  assets: Array[MToken],
  additionalRegisters: Map[String, String],
  creationHeight: Int,
  transactionId: String,
  index: Short
)

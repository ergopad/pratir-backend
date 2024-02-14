package models

import play.api.libs.json.Json

final case class MUnsignedTransactionResponse(
    unsignedTransaction: MUnsignedTransaction,
    reducedTransaction: String
)

object MUnsignedTransactionResponse {
  implicit val json =
    Json.format[MUnsignedTransactionResponse]
}

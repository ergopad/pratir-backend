package models

import play.api.libs.json.Json
import java.util.UUID

final case class GetPackTokensResponse(
    saleId: UUID,
    packId: UUID,
    packToken: String,
    amount: Long
)

object GetPackTokensResponse {
  implicit val json = Json.format[GetPackTokensResponse]
}

package models

import play.api.libs.json.Json
import java.util.UUID

final case class GetPackTokensRequest(
    addresses: Seq[String],
    sales: Option[Seq[UUID]]
)

object GetPackTokensRequest {
  implicit val json = Json.format[GetPackTokensRequest]
}

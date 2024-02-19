package models

import play.api.libs.json.Json
import java.util.UUID

final case class GetBuyOrdersRequest(
    addresses: Seq[String],
    sales: Option[Seq[UUID]]
)

object GetBuyOrdersRequest {
  implicit val json = Json.format[GetBuyOrdersRequest]
}

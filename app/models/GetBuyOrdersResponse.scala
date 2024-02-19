package models

import play.api.libs.json.Json
import java.util.UUID
import java.time.Instant

final case class GetBuyOrdersResponse(
    id: UUID,
    userAddress: String,
    saleId: UUID,
    packId: UUID,
    orderBoxId: String,
    followUpTxId: String,
    tokensBought: Seq[(String, Long)],
    status: TokenOrderStatus.Value,
    created_at: Instant,
    updated_at: Instant
)

object GetBuyOrdersResponse {
  implicit val json = Json.format[GetBuyOrdersResponse]

  def fromTokenOrder(tokenOrder: TokenOrder): GetBuyOrdersResponse = {
    GetBuyOrdersResponse(
      id = tokenOrder.id,
      userAddress = tokenOrder.userAddress,
      saleId = tokenOrder.saleId,
      packId = tokenOrder.packId,
      orderBoxId = tokenOrder.orderBoxId,
      followUpTxId = tokenOrder.followUpTxId,
      tokensBought = Seq(),
      status = tokenOrder.status,
      created_at = tokenOrder.created_at,
      updated_at = tokenOrder.updated_at
    )
  }
}

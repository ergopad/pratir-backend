package models

import play.api.libs.json.Json
import java.util.UUID
import java.time.Instant
import util.CruxClient

final case class GetBuyOrdersResponse(
    id: UUID,
    userAddress: String,
    saleId: UUID,
    packId: UUID,
    packToken: Option[String],
    orderBoxId: String,
    followUpTxId: String,
    tokensBought: Seq[(String, Long)],
    status: TokenOrderStatus.Value,
    created_at: Instant,
    updated_at: Instant
)

object GetBuyOrdersResponse {
  implicit val json = Json.format[GetBuyOrdersResponse]

  def fromTokenOrder(
      tokenOrder: TokenOrder,
      packToken: Option[String],
      cruxClient: CruxClient
  ): GetBuyOrdersResponse = {
    val tokensBought = if (
      tokenOrder.status == TokenOrderStatus.FULLFILLING || tokenOrder.status == TokenOrderStatus.FULLFILLED
    ) {
      cruxClient.getTokensFromFollowUp(
        tokenOrder.orderBoxId,
        tokenOrder.followUpTxId
      )
    } else {
      Seq()
    }
    GetBuyOrdersResponse(
      id = tokenOrder.id,
      userAddress = tokenOrder.userAddress,
      saleId = tokenOrder.saleId,
      packId = tokenOrder.packId,
      packToken = packToken,
      orderBoxId = tokenOrder.orderBoxId,
      followUpTxId = tokenOrder.followUpTxId,
      tokensBought = tokensBought,
      status = tokenOrder.status,
      created_at = tokenOrder.created_at,
      updated_at = tokenOrder.updated_at
    )
  }
}

final case class GetAllBuyOrderResponse(
    total: Int,
    items: Seq[GetBuyOrdersResponse]
)

object GetAllBuyOrderResponse {
  implicit val json = Json.format[GetAllBuyOrderResponse]
}

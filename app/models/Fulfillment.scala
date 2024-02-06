package models

import org.ergoplatform.appkit.InputBox
import java.util.UUID
import org.ergoplatform.appkit.OutBox
import play.api.libs.json.Json

final case class Fulfillment(
    saleId: UUID,
    orderId: UUID,
    orderBox: InputBox,
    nftBox: OutBox,
    sellerBox: OutBox,
    profitShareBoxes: Array[OutBox]
)

package models

import org.ergoplatform.appkit.InputBox
import java.util.UUID
import org.ergoplatform.appkit.OutBox

final case class Fulfillment(
    saleId: UUID,
    orderId: UUID,
    orderBox: InputBox,
    nftBox: OutBox,
    sellerBox: OutBox,
    feeBox: OutBox
)

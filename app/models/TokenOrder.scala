package models

import java.util.UUID
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import slick.jdbc.PostgresProfile.api._

object TokenOrderStatus extends Enumeration {
    type TokenOrderStatus = Value
    val INITIALIZED, CONFIRMING, CONFIRMED, FULLFILLING, REFUNDING, FULLFILLED, REFUNDED, FAILED = Value

    implicit val readsTokenOrderStatus = Reads.enumNameReads(TokenOrderStatus)
    implicit val writesTokenOrderStatus = Writes.enumNameWrites
    implicit val statusMapper = MappedColumnType.base[TokenOrderStatus, String](
        e => e.toString,
        s => TokenOrderStatus.withName(s)
    )
}

final case class TokenOrder(
    id: UUID,
    userAddress: String,
    saleId: UUID,
    packId: UUID,
    orderBoxId: UUID,
    followUpTxId: String,
    status: TokenOrderStatus.Value
)

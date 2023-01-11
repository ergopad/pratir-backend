package models

import java.time.Instant
import java.util.UUID
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import slick.jdbc.PostgresProfile.api._
import contracts.SaleBox

object SaleStatus extends Enumeration {
    type SaleStatus = Value
    val PENDING, WAITING, LIVE, FINISHED = Value

    implicit val readsSaleStatus = Reads.enumNameReads(SaleStatus)
    implicit val writesSaleStatus = Writes.enumNameWrites
    implicit val statusMapper = MappedColumnType.base[SaleStatus, String](
        e => e.toString,
        s => SaleStatus.withName(s)
    )
}

final case class Sale(
    id: UUID,
    name: String, 
    description: String, 
    startTime: Instant, 
    endTime: Instant, 
    sellerWallet: String,
    status: SaleStatus.Value
)
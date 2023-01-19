package models

import java.time.Instant
import java.util.UUID

final case class SaleLite(
    id: UUID,
    name: String, 
    description: String, 
    startTime: Instant, 
    endTime: Instant, 
    status: SaleStatus.Value,
    saleWallet: String
)

object SaleLite {
    def fromSale(sale: Sale) = SaleLite(sale.id, sale.name, sale.description, sale.startTime, sale.endTime, sale.status, sale.getSaleAddress.toString())
}

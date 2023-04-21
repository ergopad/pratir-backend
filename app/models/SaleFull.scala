package models

import java.time.Instant
import java.util.UUID
import database.SalesDAO
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import play.api.libs.json.Json

final case class SaleFull(
    id: UUID,
    name: String,
    description: String,
    startTime: Instant,
    endTime: Instant,
    sellerWallet: String,
    saleWallet: String,
    packs: Array[PackFull],
    tokens: Array[TokenForSale],
    initialNanoErgFee: Long,
    saleFeePct: Int,
    collection: Option[NFTCollection],
    artist: Option[Artist]
)

object SaleFull {

  implicit val json = Json.format[SaleFull]

  def fromSaleId(_saleId: String, salesdao: SalesDAO) = {
    val saleId = UUID.fromString(_saleId)
    val saleCollArtist = Await.result(salesdao.getSale(saleId), Duration.Inf)
    val sale = saleCollArtist._1._1
    val tokens = Await.result(salesdao.getTokensForSale(saleId), Duration.Inf)
    val packs = Await
      .result(salesdao.getPacks(saleId), Duration.Inf)
      .map(p => {
        PackFull(p, salesdao)
      })
    SaleFull(
      sale.id,
      sale.name,
      sale.description,
      sale.startTime,
      sale.endTime,
      sale.sellerWallet,
      sale.getSaleAddress.toString(),
      packs.toArray,
      tokens.toArray,
      sale.initialNanoErgFee,
      sale.saleFeePct,
      saleCollArtist._1._2,
      saleCollArtist._2
    )
  }
}

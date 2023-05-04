package models

import java.time.Instant
import java.util.UUID
import database.SalesDAO
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import play.api.libs.json.Json
import play.api.Logging
import com.github.nscala_time.time.Imports._

final case class SaleFull(
    id: UUID,
    name: String,
    description: String,
    startTime: Instant,
    endTime: Instant,
    status: SaleStatus.Value,
    sellerWallet: String,
    saleWallet: String,
    packs: Array[PackFull],
    tokens: Array[TokenForSale],
    initialNanoErgFee: Long,
    saleFeePct: Int,
    collection: Option[NFTCollection],
    artist: Option[User]
)

object SaleFull extends Logging {

  implicit val json = Json.format[SaleFull]

  def fromSaleId(_saleId: String, salesdao: SalesDAO) = {
    val start = DateTime.now()
    val saleId = UUID.fromString(_saleId)
    val saleCollArtist = Await.result(salesdao.getSale(saleId), Duration.Inf)
    val getSaleTime = DateTime.now()
    logger.info("Time to get sale: " + (start to getSaleTime).millis.toString)
    val sale = saleCollArtist._1._1
    val tokens = Await.result(salesdao.getTokensForSale(saleId), Duration.Inf)
    val getTokensTime = DateTime.now()
    logger.info(
      "Time to get tokens: " + (getSaleTime to getTokensTime).millis.toString
    )
    val packs = salesdao.getPacksFull(saleCollArtist._1._1.id)
    val getPacksTime = DateTime.now()
    logger.info(
      "Time to get packs: " + (getTokensTime to getPacksTime).millis.toString
    )
    SaleFull(
      sale.id,
      sale.name,
      sale.description,
      sale.startTime,
      sale.endTime,
      sale.status,
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

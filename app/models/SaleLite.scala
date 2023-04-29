package models

import java.time.Instant
import java.util.UUID
import play.api.libs.json.Json
import database.SalesDAO
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import play.api.Logging
import com.github.nscala_time.time.Imports._

final case class SaleLite(
    id: UUID,
    name: String,
    description: String,
    startTime: Instant,
    endTime: Instant,
    status: SaleStatus.Value,
    sellerWallet: String,
    saleWallet: String,
    packs: Array[PackFull],
    tokens: Int,
    tokensTotal: Int,
    startingTokensTotal: Int,
    collection: Option[NFTCollection],
    artist: Option[User]
)

object SaleLite extends Logging {

  implicit val json = Json.format[SaleLite]

  def fromSale(
      sale: ((Sale, Option[NFTCollection]), Option[User]),
      salesdao: SalesDAO
  ) = {
    val start = DateTime.now()
    val packs = salesdao.getPacksFull(sale._1._1.id)
    val getPacksTime = DateTime.now()
    logger.info(
      "Time to get packs: " + (start to getPacksTime).millis.toString
    )
    val tokens = Await
      .result(salesdao.getTokensForSale(sale._1._1.id), Duration.Inf)
      .filter(!_.rarity.contains("_pt_"))
    val getTokensTime = DateTime.now()
    logger.info(
      "Time to get tokens: " + (getPacksTime to getTokensTime).millis.toString
    )
    SaleLite(
      sale._1._1.id,
      sale._1._1.name,
      sale._1._1.description,
      sale._1._1.startTime,
      sale._1._1.endTime,
      sale._1._1.status,
      sale._1._1.sellerWallet,
      sale._1._1.getSaleAddress.toString(),
      packs.toArray,
      tokens.size,
      tokens.foldLeft(0)((z: Int, t: TokenForSale) => z + t.amount),
      tokens.foldLeft(0)((z: Int, t: TokenForSale) => z + t.originalAmount),
      sale._1._2,
      sale._2
    )
  }
}

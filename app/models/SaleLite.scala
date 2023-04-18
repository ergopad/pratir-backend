package models

import java.time.Instant
import java.util.UUID
import play.api.libs.json.Json
import database.SalesDAO
import scala.concurrent.Await
import scala.concurrent.duration.Duration

final case class SaleLite(
    id: UUID,
    name: String,
    description: String,
    startTime: Instant,
    endTime: Instant,
    status: SaleStatus.Value,
    sellerWaller: String,
    saleWallet: String,
    packs: Array[PackFull],
    tokens: Int,
    tokensTotal: Int,
    startingTokensTotal: Int,
    collection: Option[NFTCollection],
    artist: Option[Artist]
)

object SaleLite {

  implicit val json = Json.format[SaleLite]

  def fromSale(
      sale: ((Sale, Option[NFTCollection]), Option[Artist]),
      salesdao: SalesDAO
  ) = {
    val packs = Await
      .result(salesdao.getPacks(saleId), Duration.Inf)
      .map(p => {
        val price = Await.result(salesdao.getPrice(p.id), Duration.Inf)
        val content = Await.result(salesdao.getPackEntries(p.id), Duration.Inf)
        PackFull(p.id, p.name, p.image, price.toArray, content.toArray)
      })
    val tokens = Await
      .result(salesdao.getTokensForSale(sale._1._1.id), Duration.Inf)
      .filter(!_.rarity.contains("_pt_"))
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

package models

import java.util.UUID
import play.api.libs.json.Json
import javax.inject._
import database.SalesDAO
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.ergoplatform.appkit.BlockchainDataSource

final case class PackFull(
    id: UUID,
    name: String,
    image: String,
    price: Array[Price],
    derivedPrice: Option[Array[Array[DerivedPrice]]],
    content: Array[PackEntry],
    soldOut: Boolean
)

object PackFull {
  implicit val json = Json.format[PackFull]

  def apply(
      p: Pack,
      salesdao: SalesDAO,
      height: Int = -1,
      dataSource: BlockchainDataSource = null
  ): PackFull = {
    val price = Await.result(salesdao.getPrice(p.id), Duration.Inf)
    val content = Await.result(salesdao.getPackEntries(p.id), Duration.Inf)
    val notSoldOut =
      content.forall(pe =>
        Await
          .result(
            salesdao
              .tokensLeft(p.saleId, pe.pickRarity(salesdao, p.saleId).rarity),
            Duration.Inf
          )
          .getOrElse(0) > 0
      ) && price.forall(pr =>
        pr.amount > 0 || Await
          .result(salesdao.getTokenForSale(pr.tokenId, p.saleId), Duration.Inf)
          .amount > -1 * pr.amount
      )
    val derivedPrices =
      if (height >= 0)
        DerivedPrice.fromPrice(price, height, dataSource, salesdao.cruxClient)
      else new Array(0)
    PackFull(
      p.id,
      p.name,
      p.image,
      price.toArray,
      Some(derivedPrices.toArray),
      content.toArray,
      !notSoldOut
    )
  }
}

package models

import java.util.UUID
import play.api.libs.json.Json
import javax.inject._
import database.SalesDAO
import scala.concurrent.Await
import scala.concurrent.duration.Duration

final case class PackFull(
    id: UUID,
    name: String,
    image: String,
    price: Array[Price],
    derivedPrice: Array[DerivedPrice],
    content: Array[PackEntry],
    soldOut: Boolean
)

object PackFull {
  implicit val json = Json.using[Json.WithDefaultValues].format[PackFull]

  def apply(p: Pack, salesdao: SalesDAO): PackFull = {
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
    val derivedPrices = price.flatMap(p => DerivedPrice.fromPrice(p)).flatten
    PackFull(
      p.id,
      p.name,
      p.image,
      price.toArray,
      derivedPrices.toArray,
      content.toArray,
      !notSoldOut
    )
  }
}

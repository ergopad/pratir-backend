package models

import java.util.UUID
import play.api.libs.json.JsValue
import database.SalesDAO
import play.api.libs.json.Json
import play.api.libs.json.JsSuccess
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import scala.util.Random

final case class PackEntry(
    id: UUID,
    rarity: JsValue,
    amount: Int,
    packId: UUID
) {
  def pickRarity(salesdao: SalesDAO, saleId: UUID): PackRarity = {
    val packRarity = getRarity
    val totalOdds =
      packRarity.foldLeft(0.0)((z: Double, p: PackRarity) => z + p.odds)
    val randomPick = new Random().nextDouble() * totalOdds
    var cumulativeOdds = 0.0
    packRarity.foreach(pr => {
      cumulativeOdds += pr.odds
      if (cumulativeOdds > randomPick) {
        return pr
      }
    })
    packRarity.last
  }

  def getRarity = Json
    .fromJson[Seq[PackRarity]](rarity)
    .asInstanceOf[JsSuccess[Seq[PackRarity]]]
    .value
}

object PackEntry {
  implicit val json = Json.format[PackEntry]
}

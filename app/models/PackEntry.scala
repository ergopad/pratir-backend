package models

import java.util.UUID
import play.api.libs.json.JsValue
import database.SalesDAO
import play.api.libs.json.Json
import play.api.libs.json.JsSuccess
import scala.concurrent.Await
import scala.concurrent.duration.Duration

final case class PackEntry(
    id: UUID,
    rarity: JsValue,
    amount: Int,
    packId: UUID
) {
  def pickRarity(salesdao: SalesDAO, saleId: UUID) = {
    getRarity
      .map(pr => (salesdao.rarityOdds(saleId, pr), pr))
      .sortBy(-1 * _._1)
      .head
      ._2
  }

  def getRarity = Json
    .fromJson[Seq[PackRarity]](rarity)
    .asInstanceOf[JsSuccess[Seq[PackRarity]]]
    .value
}

object PackEntry {
  implicit val json = Json.format[PackEntry]
}

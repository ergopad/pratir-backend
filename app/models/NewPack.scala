package models

import play.api.libs.json._
import slick.jdbc.PostgresProfile.api._
import java.util.UUID
import database.Packs
import database.PackEntries
import database.Prices

object NewPackType extends Enumeration {
  type NewPackType = Value
  val DIRECT, PACK_TOKEN_ONLY, BUY_AND_OPEN_ONLY, COMBINED = Value

  implicit val readsNewPackType = Reads.enumNameReads(NewPackType)
  implicit val writesNewPackType = Writes.enumNameWrites
  implicit val statusMapper = MappedColumnType.base[NewPackType, String](
    e => e.toString,
    s => NewPackType.withName(s)
  )
}

final case class NewPack(
    name: String,
    image: String,
    price: Array[NewPrice],
    content: Array[NewPackEntry],
    tpe: NewPackType.Value,
    count: Option[Long]
) {
  def directPack(saleId: UUID) = {
    val packId = UUID.randomUUID()
    Seq[DBIOAction[Any, slick.dbio.NoStream, ?]](
      Packs.packs += Pack(packId, name, image, saleId),
      PackEntries.packEntries ++=
        content.map(entry =>
          PackEntry(
            UUID.randomUUID(),
            Json.toJson(entry.rarity),
            entry.amount,
            packId
          )
        ),
      Prices.prices ++=
        price.map(p =>
          Price(
            UUID.randomUUID(),
            p.tokenId.getOrElse("0" * 64),
            p.amount,
            packId
          )
        )
    )
  }

  def packTokenPack(saleId: UUID) = {
    val packId = UUID.randomUUID()
    Seq[DBIOAction[Any, slick.dbio.NoStream, ?]](
      Packs.packs += Pack(packId, name, image, saleId),
      PackEntries.packEntries ++=
        content.map(entry =>
          PackEntry(
            UUID.randomUUID(),
            Json.toJson[Seq[PackRarity]](
              Array(PackRarity(s"_pt_rarity_${name}", 100))
            ),
            1,
            packId
          )
        ),
      Prices.prices ++=
        price.map(p =>
          Price(
            UUID.randomUUID(),
            p.tokenId.getOrElse("0" * 64),
            p.amount,
            packId
          )
        )
    )
  }

  def openPackTokenPack(saleId: UUID) = {
    val derivedPackId = UUID.randomUUID()
    Seq[DBIOAction[Any, slick.dbio.NoStream, ?]](
      Packs.packs += Pack(derivedPackId, name, image, saleId),
      PackEntries.packEntries ++=
        content.map(entry =>
          PackEntry(
            UUID.randomUUID(),
            Json.toJson(entry.rarity),
            entry.amount,
            derivedPackId
          )
        ),
      Prices.prices ++=
        price.map(p =>
          Price(
            UUID.randomUUID(),
            s"_pt_${count.get.toString}_${name}",
            1,
            derivedPackId
          )
        )
    )
  }

  def buyAndOpenPack(saleId: UUID) = {
    val packId = UUID.randomUUID()
    Seq[DBIOAction[Any, slick.dbio.NoStream, ?]](
      Packs.packs += Pack(packId, name, image, saleId),
      PackEntries.packEntries ++=
        content.map(entry =>
          PackEntry(
            UUID.randomUUID(),
            Json.toJson(entry.rarity),
            entry.amount,
            packId
          )
        ),
      Prices.prices ++=
        price.map(p =>
          Price(
            UUID.randomUUID(),
            p.tokenId.getOrElse("0" * 64),
            p.amount,
            packId
          )
        ) ++ Array(
          Price(
            UUID.randomUUID(),
            s"_pt_${count.get.toString}_${name}",
            -1,
            packId
          )
        )
    )
  }

  def toPacks(saleId: UUID) = {
    tpe match {
      case NewPackType.DIRECT =>
        directPack(saleId)
      case NewPackType.PACK_TOKEN_ONLY =>
        packTokenPack(saleId) ++ openPackTokenPack(saleId)
      case NewPackType.BUY_AND_OPEN_ONLY =>
        buyAndOpenPack(saleId)
      case NewPackType.COMBINED =>
        packTokenPack(saleId) ++ openPackTokenPack(saleId) ++ buyAndOpenPack(
          saleId
        )
    }
  }
}

object NewPack {
  implicit val json = Json.format[NewPack]
}

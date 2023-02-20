package models

import slick.memory.MemoryProfile
import play.api.libs.json.Writes
import play.api.libs.json.Reads
import slick.jdbc.PostgresProfile.api._
import play.api.libs.json.Json


object TraitType extends Enumeration {
    type TraitType = Value
    val PROPERTY, LEVEL, STAT = Value

    implicit val readsTraitType = Reads.enumNameReads(TraitType)
    implicit val writesTraitType = Writes.enumNameWrites
    implicit val statusMapper = MappedColumnType.base[TraitType, String](
        e => e.toString,
        s => TraitType.withName(s)
    )
}

final case class Trait(
    name: String,
    tpe: TraitType.Value,
    valueString: Option[String],
    valueInt: Option[Int]
)

object Trait {
    implicit val json = Json.format[Trait]
}

final case class AvailableTrait(
    name: String,
    tpe: TraitType.Value,
    description: String,
    image: String,
    max: Option[Int]
)

object AvailableTrait {
    implicit val json = Json.format[AvailableTrait]
}

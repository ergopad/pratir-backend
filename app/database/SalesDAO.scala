package database

import javax.inject._
import play.api.db.slick.DatabaseConfigProvider
import scala.concurrent.ExecutionContext
import play.api.db.slick.HasDatabaseConfigProvider
import slick.jdbc.JdbcProfile
import scala.concurrent.Future
import models._
import slick.jdbc.PostgresProfile.api._
import java.time._
import java.util.UUID
import com.google.common.collect


@Singleton
class SalesDAO @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile] {
    import profile.api._

    def updateSaleStatus(saleId: UUID, newStatus: SaleStatus.Value) = {
        val query = Sales.sales.filter(_.id === saleId).map(sale => sale.status).update(newStatus)
        db.run(query)
    }

    def getAll : Future[Seq[Sale]] = {
        val query = Sales.sales.result
        db.run(query)        
    }

    def getAllActive : Future[Seq[Sale]] = {
        val query = Sales.sales.filter(_.status =!= SaleStatus.FINISHED).result
        db.run(query)        
    }

    def getSale(saleId: UUID): Future[Sale] = {
        val query = Sales.sales.filter(_.id === saleId).result.head
        db.run(query)
    }

    def getPacks(saleId: UUID): Future[Seq[Pack]] = {
        val query = Packs.packs.filter(_.saleId === saleId).result
        db.run(query)
    }

    def getPack(packId: UUID): Future[Pack] = {
        val query = Packs.packs.filter(_.id === packId).result.head
        db.run(query)
    }

    def getPrice(packId: UUID): Future[Seq[Price]] = {
        val query = Prices.prices.filter(_.packId === packId).result
        db.run(query)
    }

    def getPackEntries(packId: UUID): Future[Seq[PackEntry]] = {
        val query = PackEntries.packEntries.filter(_.packId === packId).result
        db.run(query)
    }

    def getTokensForSale(saleId: UUID): Future[Seq[TokenForSale]] = {
        val query = TokensForSale.tokensForSale.filter(_.saleId === saleId).result
        db.run(query)
    }
}

object Sales {

    class Sales(tag: Tag) extends Table[Sale](tag, "SALES") {
        def id = column[UUID]("ID", O.PrimaryKey)
        def name = column[String]("NAME")
        def description = column[String]("DESCRIPTION")
        def startTime = column[Instant]("START_TIME", O.Default(Instant.now()))
        def endTime = column[Instant]("END_TIME", O.Default(Instant.now().plus(Duration.ofDays(365))))
        def sellerWallet = column[String]("SELLER_WALLET")
        def status = column[SaleStatus.Value]("STATUS")
        def * = (id, name, description, startTime, endTime, sellerWallet, status) <> (Sale.tupled, Sale.unapply)
    }

    val sales = TableQuery[Sales]
}

object TokensForSale {
    class TokensForSale(tag: Tag) extends Table[TokenForSale]   (tag, "TOKENS_FOR_SALE") {
        def id = column[UUID]("ID", O.PrimaryKey)
        def tokenId = column[String]("TOKEN_ID", O.Length(64, false))
        def amount = column[Int]("AMOUNT", O.Default(0))
        def rarity = column[Double]("RARITY")
        def category = column[String]("CATEGORY")
        def saleId = column[UUID]("SALE_ID")
        def sale = foreignKey("TOKENS_FOR_SALE__SALE_ID_FK", saleId, Sales.sales)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, tokenId, amount, rarity, category, saleId) <> (TokenForSale.tupled, TokenForSale.unapply)
    }

    val tokensForSale = TableQuery[TokensForSale]
}

object Prices {
    class Prices(tag: Tag) extends Table[Price](tag, "PRICES") {
        def id = column[UUID]("ID", O.PrimaryKey)
        def tokenId = column[String]("TOKEN_ID", O.Length(64, false))
        def amount = column[Long]("AMOUNT", O.Default(0))
        def packId = column[UUID]("PACK_ID")
        def pack = foreignKey("PRICES__PACK_ID_FK", packId, Packs.packs)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, tokenId, amount, packId) <> (Price.tupled, Price.unapply)
    }

    val prices = TableQuery[Prices]
}

object Packs {
    class Packs(tag: Tag) extends Table[Pack](tag, "PACKS") {
        def id = column[UUID]("ID", O.PrimaryKey)
        def name = column[String]("NAME")
        def saleId = column[UUID]("SALE_ID")
        def sale = foreignKey("PACKAGES__SALE_ID_FK", saleId, Sales.sales)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, name, saleId) <> (Pack.tupled, Pack.unapply)
    }

    val packs = TableQuery[Packs]
}

object PackEntries {
    class PackEntries(tag: Tag) extends Table[PackEntry](tag, "PACK_ENTRIES") {
        def id = column[UUID]("ID", O.PrimaryKey)
        def category = column[String]("CATEGORY")
        def amount = column[Int]("AMOUNT")
        def packId = column[UUID]("PACK_ID")
        def pack = foreignKey("PACK_ENTRIES__PACK_ID_FK", packId, Packs.packs)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, category, amount, packId) <> (PackEntry.tupled, PackEntry.unapply)
    }

    val packEntries = TableQuery[PackEntries]
}

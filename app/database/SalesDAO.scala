package database

import javax.inject._
import play.api.db.slick.DatabaseConfigProvider
import scala.concurrent.ExecutionContext
import play.api.db.slick.HasDatabaseConfigProvider
import slick.jdbc.JdbcProfile
import scala.concurrent.Future
import models._
import database.JsonPostgresProfile.api._
import java.time._
import java.util.UUID
import com.google.common.collect
import scala.concurrent.Await
import scala.concurrent.duration
import scala.util.Random
import play.api.Logging
import play.api.libs.json.JsValue


@Singleton
class SalesDAO @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile] with Logging {
    import profile.api._

    def updateSaleStatus(saleId: UUID, newStatus: SaleStatus.Value) = {
        logger.info(s"""Setting status for ${saleId.toString()} to $newStatus""")
        val query = Sales.sales.filter(_.id === saleId).map(sale => (sale.status, sale.updatedAt)).update((newStatus, Instant.now()))
        db.run(query)
    }

    def getAll : Future[Seq[Sale]] = {
        val query = Sales.sales.sortBy(_.createdAt.desc).result
        db.run(query)        
    }

    def getAllActive : Future[Seq[Sale]] = {
        val query = Sales.sales.filter(_.status =!= SaleStatus.FINISHED).result
        db.run(query)        
    }

    def getAllHighlighted : Future[Seq[Sale]] = {
        val subquery = HighlightedSales.highlightedSales
            .map { _.saleId }
        val query = Sales.sales.filter(_.id in subquery)
        val action = query.result
        db.run(action)
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

    def injectPackTokenId(packPlaceHolder: String, tokenId: String, saleId: UUID) = {
        db.run(sqlu"""
        UPDATE prices
        SET token_id = ${tokenId}
        WHERE token_id = ${packPlaceHolder}
        AND pack_id IN (
            SELECT id FROM packs
            WHERE sale_id = UUID(${saleId.toString()})
            )
        """)
    }

    def getPackEntries(packId: UUID): Future[Seq[PackEntry]] = {
        val query = PackEntries.packEntries.filter(_.packId === packId).result
        db.run(query)
    }

    def insertTokenForSale(tokenForSale: TokenForSale) = {
        db.run(DBIO.seq(TokensForSale.tokensForSale += tokenForSale))
    }

    def getTokensForSale(saleId: UUID): Future[Seq[TokenForSale]] = {
        val query = TokensForSale.tokensForSale.filter(_.saleId === saleId).result
        db.run(query)
    }

    def getTokenForSale(id: UUID): Future[TokenForSale] = {
        val query = TokensForSale.tokensForSale.filter(_.id === id).result.head
        db.run(query)
    }

    def getTokenForSale(tokenId: String, saleId: UUID): Future[TokenForSale] = {
        val query = TokensForSale.tokensForSale.filter(tfs => tfs.saleId === saleId && tfs.tokenId === tokenId).result.head
        db.run(query)
    }

    def newTokenOrder(id: UUID, userAddress: String, saleId: UUID, packId: UUID): Future[Any] = {
        logger.info(s"""Registering new token order for ${saleId.toString()}""")
        db.run(DBIO.seq(TokenOrders.tokenOrders += TokenOrder(id, userAddress, saleId, packId, "", "", TokenOrderStatus.INITIALIZED, Instant.now(), Instant.now())))
    }

    def getOpenTokenOrders(): Future[Seq[TokenOrder]] = {
        val query = TokenOrders.tokenOrders.filterNot(_.status inSet Seq(TokenOrderStatus.FULLFILLED, TokenOrderStatus.REFUNDED, TokenOrderStatus.FAILED)).sortBy(_.createdAt).result
        db.run(query)
    }

    def getTokenOrderHistory(address: String): Future[Seq[TokenOrder]] = {
        val query = TokenOrders.tokenOrders.filter(_.userWallet === address).sortBy(_.createdAt.desc).result
        db.run(query)
    }

    def updateTokenOrderStatus(tokenOrderId: UUID, orderBoxId: String, newStatus: TokenOrderStatus.Value, followUpTxId: String) = {
        logger.info(s"""Setting status for order ${tokenOrderId.toString()} to $newStatus""")
        val query = TokenOrders.tokenOrders.filter(_.id === tokenOrderId).map(tokenOrder => (tokenOrder.orderBoxId, tokenOrder.status, tokenOrder.followUpTxId, tokenOrder.updatedAt)).update((orderBoxId, newStatus,followUpTxId, Instant.now()))
        db.run(query)
    }

    def tokensLeft(saleId: UUID) = {
        val query = TokensForSale.tokensForSale.filter(_.saleId === saleId).map(_.amount).sum.result
        db.run(query)
    }

    def pickRandomToken(saleId: UUID, rarity: String) = {
        val randomTokenUUID = UUID.fromString(Await.result(db.run(sql"""
        select "id"  
        from 
            (select "id", (random()) as "lucky_num"
            from "tokens_for_sale"
            where "amount" > 0 
            and "sale_id" = UUID(${saleId.toString()})
            and "rarity" = $rarity
            order by "lucky_num" desc) as X
        LIMIT 1
        """.as[String]), duration.Duration.Inf).head)
        getTokenForSale(randomTokenUUID)
    }

    def rarityOdds(saleId: UUID, packRarity: PackRarity) = {
        Await.result(db.run(sql"""
        SELECT random()*${packRarity.odds}*SUM(amount)/SUM(original_amount) AS "lucky_num"
            FROM "tokens_for_sale"
            WHERE "sale_id" = UUID(${saleId.toString()})
            AND "rarity" = ${packRarity.rarity}
            GROUP BY sale_id, rarity
        LIMIT 1
        """.as[Double]), duration.Duration.Inf).head.toDouble
    }

    def reserveToken(tokenForSale: TokenForSale, reserveAmount: Int = 1) = {
        if (tokenForSale.amount-1 < 0) throw new Exception("Token amount can not be negative")
        val query = TokensForSale.tokensForSale.filter(_.id === tokenForSale.id).map(_.amount).update(tokenForSale.amount-reserveAmount)
        db.run(query)
    }

    def updateTokenAmount(saleId: UUID, tokenId: String, amount: Int) = {
        if (amount < 0) throw new Exception("Token amount can not be negative")
        val query = TokensForSale.tokensForSale.filter(tfs => tfs.saleId === saleId && tfs.tokenId === tokenId).map(_.amount).update(amount)
        db.run(query)
    }

    def highlightSale(saleId: UUID) = {
        val query = HighlightedSales.highlightedSales += HighlightedSale(UUID.randomUUID, saleId)
        db.run(query)
    }

    def removeSaleFromHighlights(saleId: UUID) = {
        val query = HighlightedSales.highlightedSales.filter(_.saleId === saleId)
        db.run(query.delete)
    }
}

object TokenOrders {
    class TokenOrders(tag: Tag) extends Table[TokenOrder](tag, "token_orders") {
        def id = column[UUID]("id", O.PrimaryKey)
        def userWallet = column[String]("user_wallet")
        def saleId = column[UUID]("sale_id")
        def packId = column[UUID]("pack_id")
        def orderBoxId = column[String]("order_box_id")
        def followUpTxId = column[String]("follow_up_tx_id", O.Length(64, true))
        def status = column[TokenOrderStatus.Value]("status")
        def createdAt = column[Instant]("created_at")
        def updatedAt = column[Instant]("updated_at")
        def sale = foreignKey("token_orders__sale_id_fk", saleId, Sales.sales)(_.id, onDelete=ForeignKeyAction.Cascade)
        def pack = foreignKey("token_orders__pack_id_fk", packId, Packs.packs)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, userWallet, saleId, packId, orderBoxId, followUpTxId, status, createdAt, updatedAt) <> (TokenOrder.tupled, TokenOrder.unapply)
    }

    val tokenOrders = TableQuery[TokenOrders]
}

object Sales {
    class Sales(tag: Tag) extends Table[Sale](tag, "sales") {
        def id = column[UUID]("id", O.PrimaryKey)
        def name = column[String]("name")
        def description = column[String]("description")
        def startTime = column[Instant]("start_time", O.Default(Instant.now()))
        def endTime = column[Instant]("end_time", O.Default(Instant.now().plus(Duration.ofDays(365))))
        def sellerWallet = column[String]("seller_wallet")
        def status = column[SaleStatus.Value]("status")
        def initialNanoErgFee = column[Long]("initial_nanoerg_fee")
        def saleFeePct = column[Int]("sale_fee_pct")
        def password = column[String]("password")
        def createdAt = column[Instant]("created_at")
        def updatedAt = column[Instant]("updated_at")
        def * = (id, name, description, startTime, endTime, sellerWallet, status, initialNanoErgFee, saleFeePct, password, createdAt, updatedAt) <> (Sale.tupled, Sale.unapply)
    }

    val sales = TableQuery[Sales]
}

object TokensForSale {
    class TokensForSale(tag: Tag) extends Table[TokenForSale]   (tag, "tokens_for_sale") {
        def id = column[UUID]("id", O.PrimaryKey)
        def tokenId = column[String]("token_id", O.Length(64, false))
        def amount = column[Int]("amount", O.Default(0))
        def originalAmount = column[Int]("original_amount")
        def rarity = column[String]("rarity")
        def saleId = column[UUID]("sale_id")
        def sale = foreignKey("tokens_for_sale__sale_id_fk", saleId, Sales.sales)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, tokenId, amount, originalAmount, rarity, saleId) <> (TokenForSale.tupled, TokenForSale.unapply)
    }

    val tokensForSale = TableQuery[TokensForSale]
}

object Prices {
    class Prices(tag: Tag) extends Table[Price](tag, "prices") {
        def id = column[UUID]("id", O.PrimaryKey)
        def tokenId = column[String]("token_id", O.Length(64, false))
        def amount = column[Long]("amount", O.Default(0))
        def packId = column[UUID]("pack_id")
        def pack = foreignKey("prices__pack_id_fk", packId, Packs.packs)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, tokenId, amount, packId) <> (Price.tupled, Price.unapply)
    }

    val prices = TableQuery[Prices]
}

object Packs {
    class Packs(tag: Tag) extends Table[Pack](tag, "packs") {
        def id = column[UUID]("id", O.PrimaryKey)
        def name = column[String]("name")
        def image = column[String]("image")
        def saleId = column[UUID]("sale_id")
        def sale = foreignKey("packages__sale_id_fk", saleId, Sales.sales)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, name, image, saleId) <> (Pack.tupled, Pack.unapply)
    }

    val packs = TableQuery[Packs]
}

object PackEntries {
    class PackEntries(tag: Tag) extends Table[PackEntry](tag, "pack_entries") {
        def id = column[UUID]("id", O.PrimaryKey)
        def rarity = column[JsValue]("rarity")
        def amount = column[Int]("amount")
        def packId = column[UUID]("pack_id")
        def pack = foreignKey("pack_entries__pack_id_fk", packId, Packs.packs)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, rarity, amount, packId) <> (PackEntry.tupled, PackEntry.unapply)
    }

    val packEntries = TableQuery[PackEntries]
}

object HighlightedSales {
    class HighlightedSales(tag: Tag) extends Table[HighlightedSale](tag, "highlighted_sales") {
        def id = column[UUID]("id", O.PrimaryKey)
        def saleId = column[UUID]("sale_id")
        def sale = foreignKey("highlighted_sales__sale_id_fk", saleId, Sales.sales)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, saleId) <> (HighlightedSale.tupled, HighlightedSale.unapply)
    }

    val highlightedSales = TableQuery[HighlightedSales]
}

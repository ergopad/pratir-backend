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
import scala.concurrent.Await
import scala.concurrent.duration
import scala.util.Random
import play.api.Logging


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

    def pickRandomToken(saleId: UUID, category: String) = {
        val randomTokenUUID = UUID.fromString(Await.result(db.run(sql"""
        select "ID"  
        from 
            (select "ID", ("RARITY"*random()) as "LUCKY_NUM"
            from "TOKENS_FOR_SALE"
            where "AMOUNT" > 0 
            and "SALE_ID" = UUID(${saleId.toString()})
            and "CATEGORY" = $category
            order by "LUCKY_NUM" desc) as X
        LIMIT 1
        """.as[String]), duration.Duration.Inf).head)
        getTokenForSale(randomTokenUUID)
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
}

object TokenOrders {
    class TokenOrders(tag: Tag) extends Table[TokenOrder](tag, "TOKEN_ORDERS") {
        def id = column[UUID]("ID", O.PrimaryKey)
        def userWallet = column[String]("USER_WALLET")
        def saleId = column[UUID]("SALE_ID")
        def packId = column[UUID]("PACK_ID")
        def orderBoxId = column[String]("ORDER_BOX_ID")
        def followUpTxId = column[String]("FOLLOW_UP_TX_ID", O.Length(64, true))
        def status = column[TokenOrderStatus.Value]("STATUS")
        def createdAt = column[Instant]("CREATED_AT")
        def updatedAt = column[Instant]("UPDATED_AT")
        def sale = foreignKey("TOKEN_ORDERS__SALE_ID_FK", saleId, Sales.sales)(_.id, onDelete=ForeignKeyAction.Cascade)
        def pack = foreignKey("TOKEN_ORDERS__PACK_ID_FK", packId, Packs.packs)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, userWallet, saleId, packId, orderBoxId, followUpTxId, status, createdAt, updatedAt) <> (TokenOrder.tupled, TokenOrder.unapply)
    }

    val tokenOrders = TableQuery[TokenOrders]
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
        def initialNanoErgFee = column[Long]("INITIAL_NANOERG_FEE")
        def saleFeePct = column[Int]("SALE_FEE_PCT")
        def password = column[String]("PASSWORD")
        def createdAt = column[Instant]("CREATED_AT")
        def updatedAt = column[Instant]("UPDATED_AT")
        def * = (id, name, description, startTime, endTime, sellerWallet, status, initialNanoErgFee, saleFeePct, password, createdAt, updatedAt) <> (Sale.tupled, Sale.unapply)
    }

    val sales = TableQuery[Sales]
}

object TokensForSale {
    class TokensForSale(tag: Tag) extends Table[TokenForSale]   (tag, "TOKENS_FOR_SALE") {
        def id = column[UUID]("ID", O.PrimaryKey)
        def tokenId = column[String]("TOKEN_ID", O.Length(64, false))
        def amount = column[Int]("AMOUNT", O.Default(0))
        def originalAmount = column[Int]("ORIGINAL_AMOUNT")
        def rarity = column[Double]("RARITY")
        def category = column[String]("CATEGORY")
        def saleId = column[UUID]("SALE_ID")
        def sale = foreignKey("TOKENS_FOR_SALE__SALE_ID_FK", saleId, Sales.sales)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, tokenId, amount, originalAmount, rarity, category, saleId) <> (TokenForSale.tupled, TokenForSale.unapply)
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

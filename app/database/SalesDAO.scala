package database

import java.time._
import java.util.UUID

import com.google.common.collect

import javax.inject._

import play.api.Logging
import play.api.db.slick.{DatabaseConfigProvider, HasDatabaseConfigProvider}
import play.api.libs.json.{JsValue, Json}

import slick.jdbc.JdbcProfile
import database.JsonPostgresProfile.api._

import models._

import scala.concurrent.{Await, ExecutionContext, Future}
import scala.concurrent.duration

import scala.util.Random

import util.Pratir
import slick.jdbc.GetResult
import com.github.tminglei.slickpg.ExPostgresProfile
import slick.ast.ColumnOption
import slick.relational.RelationalProfile
import util.CruxClient
import org.ergoplatform.appkit.BlockchainDataSource

@Singleton
class SalesDAO @Inject() (
    protected val dbConfigProvider: DatabaseConfigProvider,
    val cruxClient: CruxClient
)(implicit ec: ExecutionContext)
    extends HasDatabaseConfigProvider[JdbcProfile]
    with Logging {
  import profile.api._

  def updateSaleStatus(saleId: UUID, newStatus: SaleStatus.Value) = {
    logger.info(s"""Setting status for ${saleId.toString()} to $newStatus""")
    val query = Sales.sales
      .filter(_.id === saleId)
      .map(sale => (sale.status, sale.updatedAt))
      .update((newStatus, Instant.now()))
    db.run(query)
  }

  def getAll: Future[Seq[((Sale, Option[NFTCollection]), Option[User])]] = {
    val query = Sales.sales
      .joinLeft(Collections.collections)
      .on(_.id === _.saleId)
      .joinLeft(Users.users)
      .on(_._2.flatMap(_.artistId ?) === _.id)
      .sortBy(_._1._1.createdAt.desc)
      .result
    db.run(query)
  }

  def getAllFiltered(
      status: Option[String],
      address: Option[String]
  ): Future[Seq[((Sale, Option[NFTCollection]), Option[User])]] = {
    val mainQuery = Sales.sales
    val statusFiltered = status match {
      case None => mainQuery
      case Some(value) =>
        mainQuery
          .filter(_.status === SaleStatus.withName(value))
    }
    val addressFiltered = address match {
      case None => statusFiltered
      case Some(value) =>
        statusFiltered
          .filter(_.sellerWallet === value)
    }
    val query = addressFiltered
      .joinLeft(Collections.collections)
      .on(_.id === _.saleId)
      .joinLeft(Users.users)
      .on(_._2.flatMap(_.artistId ?) === _.id)
      .sortBy(_._1._1.createdAt.desc)
      .result
    query.statements.foreach(logger.info(_))
    db.run(query)
  }

  def getAllActive
      : Future[Seq[((Sale, Option[NFTCollection]), Option[User])]] = {
    val query = Sales.sales
      .filter(_.status =!= SaleStatus.FINISHED)
      .joinLeft(Collections.collections)
      .on(_.id === _.saleId)
      .joinLeft(Users.users)
      .on(_._2.flatMap(_.artistId ?) === _.id)
      .sortBy(_._1._1.createdAt.desc)
      .result
    db.run(query)
  }

  def getAllHighlighted
      : Future[Seq[((Sale, Option[NFTCollection]), Option[User])]] = {
    val subquery = HighlightedSales.highlightedSales
      .map { _.saleId }
    val query = Sales.sales
      .filter(_.id in subquery)
      .joinLeft(Collections.collections)
      .on(_.id === _.saleId)
      .joinLeft(Users.users)
      .on(_._2.flatMap(_.artistId ?) === _.id)
      .sortBy(_._1._1.createdAt.desc)
      .result
    db.run(query)
  }

  def getSale(
      saleId: UUID
  ): Future[((Sale, Option[NFTCollection]), Option[User])] = {
    val query = Sales.sales
      .filter(_.id === saleId)
      .joinLeft(Collections.collections)
      .on(_.id === _.saleId)
      .joinLeft(Users.users)
      .on(_._2.flatMap(_.artistId ?) === _.id)
      .sortBy(_._1._1.createdAt.desc)
      .result
      .head
    db.run(query)
  }

  def getSaleIdBySlug(
      saleSlug: String
  ): Option[UUID] = {
    val query = Sales.sales.result
    val res = Await.result(db.run(query), duration.Duration.Inf)
    val requiredSale =
      res.find(sale => Pratir.stringToUrl(sale.name) == saleSlug)
    if (requiredSale.isDefined) {
      Some(requiredSale.get.id)
    } else {
      None
    }
  }

  def getPacksFull(
      saleId: UUID,
      height: Int,
      dataSource: BlockchainDataSource
  ): Seq[PackFull] = {
    val jsResult = Await
      .result(
        db.run(sql"""
        select jsonb_agg(to_jsonb(p.*))::text from (select 
          id,
          name,
          image,
          (select jsonb_agg(to_jsonb(pr.*)) from (
            select id, token_id as "tokenId", amount, pack_id as "packId" from prices where pack_id = pack.id
          ) pr) as "price",
          (select jsonb_agg(to_jsonb(pc.*)) from (
            select id, rarity, amount, pack_id as "packId" from pack_entries where pack_id = pack.id
          ) pc) as "content",
          case 
            when (coalesce(((select sum(amount) from tokens_for_sale tfs where tfs.sale_id = pack.sale_id and rarity in (
              SELECT rarity
              FROM (
                select rarity as "rarities" 
                from public.pack_entries pes
                where pes.pack_id = pack.id
              ) pe, jsonb_to_recordset(pe.rarities) as items(rarity text)
            ))
            ),0) <= 0) then TRUE
            when exists (select * 
                  from tokens_for_sale tfs 
                  where tfs.sale_id = pack.sale_id 
                  and tfs.token_id in (select token_id from prices where pack_id = pack.id)
                  and tfs.amount <= 0) then TRUE
            else FALSE end as "soldOut"
        from packs pack
        where pack.sale_id = UUID(${saleId.toString()})) p
        """.as[String]),
        duration.Duration.Inf
      )
      .head
    try {
      val withoutDerived =
        Json.fromJson[Seq[PackFull]](Json.parse(jsResult)).get
      withoutDerived.map(pf => {
        pf.copy(derivedPrice =
          Some(
            DerivedPrice.fromPrice(pf.price, height, cruxClient)
          )
        )
      })
    } catch {
      case e: Exception =>
        logger.error(s"Failed to parse packs for ${saleId.toString()}", e)
        Array[PackFull]()
    }
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

  def getPackTokens(): Future[Seq[String]] = {
    val query =
      Prices.prices.filter(_.amount === 1L).map(_.tokenId).distinct.result
    db.run(query)
  }

  def getPackTokensForSale(sale_id: UUID): Future[Seq[String]] = {
    val query = Prices.prices
      .filter(_.pack.filter(_.saleId === sale_id).exists)
      .filter(_.amount === 1L)
      .map(_.tokenId)
      .distinct
      .result
    db.run(query)
  }

  def getSaleForPackToken(packToken: String): Future[Seq[(UUID, UUID)]] = {
    val query = Packs.packs
      .filter(
        _.id.in(
          Prices.prices
            .filter(_.tokenId === packToken)
            .filter(_.amount === 1L)
            .map(_.packId)
        )
      )
      .map(p => (p.saleId, p.id))
      .take(1)
      .result
    db.run(query)
  }

  def injectPackTokenId(
      packPlaceHolder: String,
      tokenId: String,
      saleId: UUID
  ) = {
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
    val query = TokensForSale.tokensForSale
      .filter(tfs => tfs.saleId === saleId && tfs.tokenId === tokenId)
      .result
      .head
    db.run(query)
  }

  def newTokenOrder(
      id: UUID,
      userAddress: String,
      saleId: UUID,
      packId: UUID
  ): Future[Any] = {
    logger.info(s"""Registering new token order for ${saleId.toString()}""")
    db.run(
      DBIO.seq(
        TokenOrders.tokenOrders += TokenOrder(
          id,
          userAddress,
          saleId,
          packId,
          "",
          "",
          TokenOrderStatus.INITIALIZED,
          Instant.now(),
          Instant.now()
        )
      )
    )
  }

  def getInitializedTokenOrders(): Future[Seq[TokenOrder]] = {
    val query = TokenOrders.tokenOrders
      .filter(
        _.status === TokenOrderStatus.INITIALIZED
      )
      .sortBy(_.createdAt)
      .result
    db.run(query)
  }

  def getConfirmingTokenOrders(): Future[Seq[TokenOrder]] = {
    val query = TokenOrders.tokenOrders
      .filter(
        _.status === TokenOrderStatus.CONFIRMING
      )
      .sortBy(_.createdAt)
      .take(120)
      .result
    db.run(query)
  }

  def getConfirmedTokenOrders(): Future[Seq[TokenOrder]] = {
    val query = TokenOrders.tokenOrders
      .filter(
        _.status === TokenOrderStatus.CONFIRMED
      )
      .sortBy(_.createdAt)
      .take(120)
      .result
    db.run(query)
  }

  def getFollowUpTokenOrders(): Future[Seq[TokenOrder]] = {
    val query = TokenOrders.tokenOrders
      .filter(
        _.status inSet Seq(
          TokenOrderStatus.FULLFILLING,
          TokenOrderStatus.REFUNDING
        )
      )
      .sortBy(_.createdAt)
      .take(120)
      .result
    db.run(query)
  }

  def getRealPacks(
      salesOpt: Option[Seq[UUID]]
  ): Future[Seq[UUID]] = {
    val query =
      Packs.packs.filterOpt(salesOpt)((pack, sales) =>
        pack.saleId.inSet(sales)
      ) join PackEntries.packEntries.filterNot(
        _.rarity.~>(0).+>>("rarity").like("_pt_rarity%")
      ) on (_.id === _.packId)
    db.run(query.map(_._1.id).result)
  }

  def getTokenOrderHistory(
      addresses: Seq[String],
      salesOpt: Option[Seq[UUID]],
      ordersOpt: Option[Seq[UUID]],
      realPacksOpt: Option[Seq[UUID]]
  ): Future[Seq[TokenOrder]] = {
    val query = TokenOrders.tokenOrders
      .filter(_.userWallet.inSet(addresses))
      .filterOpt(salesOpt)((tokenOrder, sales) =>
        tokenOrder.saleId.inSet(sales)
      )
      .filterOpt(realPacksOpt)((tokenOrder, realPacks) =>
        tokenOrder.packId.inSet(realPacks)
      )
      .filterOpt(ordersOpt)((tokenOrder, orders) => tokenOrder.id.inSet(orders))
      .filterNot(_.status === TokenOrderStatus.INITIALIZED)
      .sortBy(_.createdAt.desc)
      .result
    db.run(query)
  }

  def getPackTokenForPack(
      packId: UUID
  ): Future[Seq[String]] = {
    val query = Prices.prices
      .filter(_.packId === packId)
      .filter(_.amount <= 1L)
      .map(_.tokenId)
      .distinct
      .result
    db.run(query)
  }

  def updateTokenOrderStatus(
      tokenOrderId: UUID,
      orderBoxId: String,
      newStatus: TokenOrderStatus.Value,
      followUpTxId: String
  ) = {
    logger.info(
      s"""Setting status for order ${tokenOrderId.toString()} to $newStatus"""
    )
    val query = TokenOrders.tokenOrders
      .filter(_.id === tokenOrderId)
      .map(tokenOrder =>
        (
          tokenOrder.orderBoxId,
          tokenOrder.status,
          tokenOrder.followUpTxId,
          tokenOrder.updatedAt
        )
      )
      .update((orderBoxId, newStatus, followUpTxId, Instant.now()))
    db.run(query)
  }

  def tokensLeft(saleId: UUID) = {
    val query = TokensForSale.tokensForSale
      .filter(_.saleId === saleId)
      .map(_.amount)
      .sum
      .result
    db.run(query)
  }

  def tokensLeft(saleId: UUID, rarity: String) = {
    val query = TokensForSale.tokensForSale
      .filter(_.saleId === saleId)
      .filter(_.rarity === rarity)
      .map(_.amount)
      .sum
      .result
    db.run(query)
  }

  def pickRandomTokens(saleId: UUID, rarity: String, amount: Int = 1) = {
    implicit val getTokenForSaleResult = GetResult(r =>
      TokenForSale(
        UUID.fromString(r.nextString()),
        r.nextString(),
        r.nextInt(),
        r.nextInt(),
        r.nextString(),
        UUID.fromString(r.nextString())
      )
    );

    db.run(sql"""
        select  "id", "tokenId", amount, "originalAmount", rarity, "saleId"  
        from 
            (select "id", token_id as "tokenId", amount, original_amount as "originalAmount", rarity, sale_id as "saleId", (random()) as "lucky_num"
            from "tokens_for_sale"
            where "amount" > 0 
            and "sale_id" = UUID(${saleId.toString()})
            and "rarity" = $rarity
            order by "lucky_num" desc) as X
        LIMIT $amount
        """.as[TokenForSale])
  }

  def rarityOdds(saleId: UUID, packRarity: PackRarity) = {
    Await
      .result(
        db.run(sql"""
        SELECT COALESCE((SELECT random()*${packRarity.odds}*SUM(amount)/SUM(original_amount) AS "lucky_num"
            FROM "tokens_for_sale"
            WHERE "sale_id" = UUID(${saleId.toString()})
            AND "rarity" = ${packRarity.rarity}
            GROUP BY sale_id, rarity
        LIMIT 1),0)
        """.as[Double]),
        duration.Duration.Inf
      )
      .head
      .toDouble
  }

  def reserveToken(tokenForSale: TokenForSale, reserveAmount: Int = 1) = {
    if (tokenForSale.amount - 1 < 0)
      throw new Exception("Token amount can not be negative")
    val query = TokensForSale.tokensForSale
      .filter(_.id === tokenForSale.id)
      .map(_.amount)
      .update(tokenForSale.amount - reserveAmount)
    db.run(query)
  }

  def updateTokenAmount(saleId: UUID, tokenId: String, amount: Int) = {
    if (amount < 0) throw new Exception("Token amount can not be negative")
    val query = TokensForSale.tokensForSale
      .filter(tfs => tfs.saleId === saleId && tfs.tokenId === tokenId)
      .map(_.amount)
      .update(amount)
    db.run(query)
  }

  def highlightSale(saleId: UUID) = {
    val query = HighlightedSales.highlightedSales += HighlightedSale(
      UUID.randomUUID,
      saleId
    )
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
    def sale = foreignKey("token_orders__sale_id_fk", saleId, Sales.sales)(
      _.id,
      onDelete = ForeignKeyAction.Cascade
    )
    def pack = foreignKey("token_orders__pack_id_fk", packId, Packs.packs)(
      _.id,
      onDelete = ForeignKeyAction.Cascade
    )
    def * = (
      id,
      userWallet,
      saleId,
      packId,
      orderBoxId,
      followUpTxId,
      status,
      createdAt,
      updatedAt
    ) <> ((TokenOrder.apply _).tupled, TokenOrder.unapply)
  }

  val tokenOrders = TableQuery[TokenOrders]
}

object Sales {
  class Sales(tag: Tag) extends Table[Sale](tag, "sales") {
    def id = column[UUID]("id", O.PrimaryKey)
    def name = column[String]("name")
    def description = column[String]("description")
    def startTime = column[Instant]("start_time", O.Default(Instant.now()))
    def endTime = column[Instant](
      "end_time",
      O.Default(Instant.now().plus(Duration.ofDays(365)))
    )
    def sellerWallet = column[String]("seller_wallet")
    def status = column[SaleStatus.Value]("status")
    def initialNanoErgFee = column[Long]("initial_nanoerg_fee")
    def saleFeePct = column[Int]("sale_fee_pct")
    def password = column[String]("password")
    def createdAt = column[Instant]("created_at")
    def updatedAt = column[Instant]("updated_at")
    def profitShare = column[JsValue]("profit_share")
    def nameIndex = index("sales_name_index", (name), unique = true)
    def * = (
      id,
      name,
      description,
      startTime,
      endTime,
      sellerWallet,
      status,
      initialNanoErgFee,
      saleFeePct,
      password,
      createdAt,
      updatedAt,
      profitShare
    ) <> ((Sale.apply _).tupled, Sale.unapply)
  }

  val sales = TableQuery[Sales]
}

object TokensForSale {
  class TokensForSale(tag: Tag)
      extends Table[TokenForSale](tag, "tokens_for_sale") {
    def id = column[UUID]("id", O.PrimaryKey)
    def tokenId = column[String]("token_id", O.Length(64, false))
    def amount = column[Int]("amount", O.Default(0))
    def originalAmount = column[Int]("original_amount")
    def rarity = column[String]("rarity")
    def saleId = column[UUID]("sale_id")
    def sale = foreignKey("tokens_for_sale__sale_id_fk", saleId, Sales.sales)(
      _.id,
      onDelete = ForeignKeyAction.Cascade
    )
    def * = (
      id,
      tokenId,
      amount,
      originalAmount,
      rarity,
      saleId
    ) <> ((TokenForSale.apply _).tupled, TokenForSale.unapply)
  }

  val tokensForSale = TableQuery[TokensForSale]
}

object Prices {
  class Prices(tag: Tag) extends Table[Price](tag, "prices") {
    def id = column[UUID]("id", O.PrimaryKey)
    def tokenId = column[String]("token_id", O.Length(64, false))
    def amount = column[Long]("amount", O.Default(0))
    def packId = column[UUID]("pack_id")
    def pack = foreignKey("prices__pack_id_fk", packId, Packs.packs)(
      _.id,
      onDelete = ForeignKeyAction.Cascade
    )
    def * =
      (id, tokenId, amount, packId) <> ((Price.apply _).tupled, Price.unapply)
  }

  val prices = TableQuery[Prices]
}

object Packs {
  class Packs(tag: Tag) extends Table[Pack](tag, "packs") {
    def id = column[UUID]("id", O.PrimaryKey)
    def name = column[String]("name")
    def image = column[String]("image")
    def saleId = column[UUID]("sale_id")
    def sale = foreignKey("packages__sale_id_fk", saleId, Sales.sales)(
      _.id,
      onDelete = ForeignKeyAction.Cascade
    )
    def * = (id, name, image, saleId) <> ((Pack.apply _).tupled, Pack.unapply)
  }

  val packs = TableQuery[Packs]
}

object PackEntries {
  class PackEntries(tag: Tag) extends Table[PackEntry](tag, "pack_entries") {
    def id = column[UUID]("id", O.PrimaryKey)
    def rarity = column[JsValue]("rarity")
    def amount = column[Int]("amount")
    def packId = column[UUID]("pack_id")
    def pack = foreignKey("pack_entries__pack_id_fk", packId, Packs.packs)(
      _.id,
      onDelete = ForeignKeyAction.Cascade
    )
    def * =
      (
        id,
        rarity,
        amount,
        packId
      ) <> ((PackEntry.apply _).tupled, PackEntry.unapply)
  }

  val packEntries = TableQuery[PackEntries]
}

object HighlightedSales {
  class HighlightedSales(tag: Tag)
      extends Table[HighlightedSale](tag, "highlighted_sales") {
    def id = column[UUID]("id", O.PrimaryKey)
    def saleId = column[UUID]("sale_id")
    def sale = foreignKey("highlighted_sales__sale_id_fk", saleId, Sales.sales)(
      _.id,
      onDelete = ForeignKeyAction.Cascade
    )
    def * = (
      id,
      saleId
    ) <> ((HighlightedSale.apply _).tupled, HighlightedSale.unapply)
  }

  val highlightedSales = TableQuery[HighlightedSales]
}

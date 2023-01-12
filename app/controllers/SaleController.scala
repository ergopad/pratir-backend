package controllers;

import play.api.mvc._
import javax.inject._
import slick.lifted.TableQuery
import database._
import play.api.libs.json._
import play.api.db.slick._
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.JdbcProfile
import scala.concurrent.ExecutionContext
import models._
import java.util.UUID
import scala.concurrent.duration.Duration
import scala.concurrent.Await
import _root_.util.Pratir
import org.ergoplatform.appkit.BoxOperations
import org.ergoplatform.appkit.RestApiErgoClient
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.BlockchainContext
import contracts.BuyOrder
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit.ExplorerAndPoolUnspentBoxesLoader
import scala.collection.mutable.HashMap
import org.ergoplatform.appkit.ErgoToken
import org.ergoplatform.appkit.scalaapi.ErgoValueBuilder
import sigmastate.eval.Colls
import java.nio.charset.CharsetEncoder
import java.nio.charset.StandardCharsets
import org.ergoplatform.appkit.Address
import scala.collection.JavaConverters._
import special.collection.Coll
import org.ergoplatform.appkit.UnsignedTransaction

@Singleton
class SaleController @Inject()(protected val dbConfigProvider: DatabaseConfigProvider, val controllerComponents: ControllerComponents)(implicit ec: ExecutionContext)
extends BaseController
 with HasDatabaseConfigProvider[JdbcProfile] {
    implicit val saleJson = Json.format[Sale]
    implicit val newTokenForSaleJson = Json.format[NewTokenForSale]
    implicit val newPriceJson = Json.format[NewPrice]
    implicit val newPackEntryJson = Json.format[NewPackEntry]
    implicit val newPackJson = Json.format[NewPack]
    implicit val newSaleJson = Json.format[NewSale]
    implicit val priceJson = Json.format[Price]
    implicit val packEntryJson = Json.format[PackEntry]
    implicit val packFullJson = Json.format[PackFull]
    implicit val tokenForSaleJson = Json.format[TokenForSale]
    implicit val saleFullJson = Json.format[SaleFull]
    implicit val buyPackRequestJson = Json.format[BuyPackRequest]
    implicit val buySaleRequestJson = Json.format[BuySaleRequest]
    implicit val buyRequestJson = Json.format[BuyRequest]
    implicit val mTokenJson = Json.format[MToken]
    implicit val mInputJson = Json.format[MInput]
    implicit val mOutputJson = Json.format[MOutput]
    implicit val mUnsignedTransactionJson = Json.format[MUnsignedTransaction]

    def getAll(): Action[AnyContent] = Action.async { implicit request =>
        val salesdao = new SalesDAO(dbConfigProvider)
        salesdao.getAll.map(sale => Ok(Json.toJson(sale)))
    }

    def getSale(_saleId: String) = Action {
        implicit request =>
        val saleId = UUID.fromString(_saleId)
        val salesdao = new SalesDAO(dbConfigProvider)
        val sale = Await.result(salesdao.getSale(saleId), Duration.Inf)
        val tokens = Await.result(salesdao.getTokensForSale(saleId), Duration.Inf)
        val packs = Await.result(salesdao.getPacks(saleId), Duration.Inf).map(
            p => {
                val price = Await.result(salesdao.getPrice(p.id), Duration.Inf)
                val content = Await.result(salesdao.getPackEntries(p.id), Duration.Inf)
                PackFull(p.id, p.name, price.toArray, content.toArray)
            }
        )
        Ok(Json.toJson(SaleFull(sale.id, sale.name, sale.description, sale.startTime, sale.endTime, sale.sellerWallet, Pratir.getSaleAddress(sale).toString(), packs.toArray, tokens.toArray)))
    }

    def createSale() = Action { implicit request =>
        val content = request.body
        val jsonObject = content.asJson
        val sale: Option[NewSale] = 
            jsonObject.flatMap( 
                Json.fromJson[NewSale](_).asOpt 
            ) 
        
        sale match {
            case None => BadRequest
            case Some(newSale) =>
                val saleId = UUID.randomUUID()
                val saleAdded = Sale(saleId, newSale.name, newSale.description, newSale.startTime, newSale.endTime, newSale.sellerWallet, SaleStatus.PENDING)
                val tokensAdded = newSale.tokens.map((token: NewTokenForSale) =>
                    TokenForSale(UUID.randomUUID(),token.tokenId,token.amount,token.rarity,token.category,saleId)
                )
                val packsDBIO = DBIO.seq(newSale.packs.flatMap((pack: NewPack) => {
                    val packId = UUID.randomUUID()
                    Seq[DBIOAction[Any,slick.dbio.NoStream,?]](
                        Packs.packs += Pack(packId, pack.name, saleId),
                        PackEntries.packEntries ++=
                            pack.content.map(entry =>
                                PackEntry(UUID.randomUUID(), entry.category, entry.amount, packId)
                            ),
                        Prices.prices ++=
                            pack.price.map(p =>
                                Price(UUID.randomUUID(), p.tokenId, p.amount, packId)
                            )
                    )
                }):_*)
                Await.result(db.run(DBIO.seq(
                    Sales.sales += saleAdded,
                    TokensForSale.tokensForSale ++= tokensAdded,
                    packsDBIO
                )),Duration.Inf)
                Created(Json.toJson(saleAdded))
        }
    }

    def buyOrder() = Action { implicit request =>
        val salesdao = new SalesDAO(dbConfigProvider)
        val content = request.body
        val jsonObject = content.asJson
        val buyRequest: Option[BuyRequest] = 
            jsonObject.flatMap( 
                Json.fromJson[BuyRequest](_).asOpt 
            ) 
        buyRequest match {
            case None => BadRequest
            case Some(buyOrder) => {
                val salesdao = new SalesDAO(dbConfigProvider)
                val ergoClient = RestApiErgoClient.create(sys.env.get("ERGO_NODE").get,NetworkType.MAINNET,"",sys.env.get("ERGO_EXPLORER").get)
                Ok(Json.toJson(MUnsignedTransaction(ergoClient.execute(new java.util.function.Function[BlockchainContext,UnsignedTransaction] {
                        override def apply(ctx: BlockchainContext): UnsignedTransaction = {
                            val buyOrderBoxes = buyOrder.requests.flatMap((bsr: BuySaleRequest) => {
                                bsr.packRequests.flatMap(bpr => {
                                    val packPrice = Await.result(salesdao.getPrice(bpr.packId), Duration.Inf)
                                    val combinedPrices = new HashMap[String, Long]()
                                    packPrice.foreach(p => combinedPrices.put(p.tokenId, p.amount + combinedPrices.getOrElse(p.tokenId,0L)))
                                    scala.collection.immutable.Range(0,bpr.count).map(i => {
                                        val outBoxBuilder = ctx.newTxBuilder()
                                            .outBoxBuilder()
                                            .registers(
                                                ErgoValueBuilder.buildFor(Colls.fromArray(bsr.saleId.toString().getBytes(StandardCharsets.UTF_8))),
                                                ErgoValueBuilder.buildFor(Colls.fromArray(bpr.packId.toString().getBytes(StandardCharsets.UTF_8))),
                                                ErgoValueBuilder.buildFor(Colls.fromArray(Address.create(buyOrder.targetAddress).toPropositionBytes())),
                                                ErgoValueBuilder.buildFor(Colls.fromArray(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8)))
                                            )
                                            .contract(new ErgoTreeContract(BuyOrder.contract(buyOrder.userWallet(0)), NetworkType.MAINNET))
                                            .value(combinedPrices.getOrElse("0"*64, 0L) + 2000000L)
                                        if (combinedPrices.filterNot(_._1 == "0"*64).size > 0) 
                                            outBoxBuilder
                                            .tokens(
                                                combinedPrices.filterNot(_._1 == "0"*64).map((kv: (String, Long)) => new ErgoToken(kv._1,kv._2)).toArray:_*
                                            )
                                            .build()
                                        else
                                            outBoxBuilder.build()
                                    })
                                })
                            }) 
                            val boxesLoader = new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)
                            val boxOperations = BoxOperations.createForSenders(buyOrder.userWallet.map(Address.create(_)).toList.asJava,ctx).withInputBoxesLoader(boxesLoader)
                                .withMaxInputBoxesToSelect(20).withFeeAmount(1000000L)
                            val unsigned = boxOperations.buildTxWithDefaultInputs(tb => tb.addOutputs(buyOrderBoxes:_*))
                            buyOrderBoxes.foreach(bob => {
                                 Await.result(salesdao.newTokenOrder(
                                    buyOrder.targetAddress, 
                                    UUID.fromString(new String(bob.getRegisters().get(0).getValue().asInstanceOf[Coll[Byte]].toArray, StandardCharsets.UTF_8)),
                                    UUID.fromString(new String(bob.getRegisters().get(1).getValue().asInstanceOf[Coll[Byte]].toArray, StandardCharsets.UTF_8)),
                                    UUID.fromString(new String(bob.getRegisters().get(3).getValue().asInstanceOf[Coll[Byte]].toArray, StandardCharsets.UTF_8))
                                ), Duration.Inf)
                            })
                            unsigned
                        }
                }))))
            }
        }
    }
 }
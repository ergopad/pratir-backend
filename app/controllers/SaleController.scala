package controllers;

import java.time.Instant
import java.util.UUID
import java.util.Base64
import java.nio.charset.CharsetEncoder
import java.nio.charset.StandardCharsets

import javax.inject._

import slick.lifted.TableQuery
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.JdbcProfile

import _root_.util._

import org.ergoplatform.appkit.BoxOperations
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit.ExplorerAndPoolUnspentBoxesLoader
import org.ergoplatform.sdk.ErgoToken
import org.ergoplatform.appkit.scalaapi.ErgoValueBuilder
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.UnsignedTransaction
import org.ergoplatform.appkit.OutBox
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughTokensException
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughErgsException
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughCoinsForChangeException

import play.api.mvc._
import play.api.libs.json._
import play.api.db.slick._

import scala.collection.mutable.HashMap
import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.Duration
import scala.concurrent.Await

import sigma.Colls

import sigma.Coll

import contracts.BuyOrder
import database._
import models._
import org.ergoplatform.appkit.RestApiErgoClient
import play.api.Logging
import org.ergoplatform.appkit.impl.NodeDataSourceImpl
import cats.instances.duration

@Singleton
class SaleController @Inject() (
    val salesdao: SalesDAO,
    val usersDao: UsersDAO,
    val controllerComponents: ControllerComponents,
    val cruxClient: CruxClient,
    protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
    extends BaseController
    with HasDatabaseConfigProvider[JdbcProfile]
    with Logging {

  def getAll(): Action[AnyContent] = Action.async { implicit request =>
    {
      val ergoClient = RestApiErgoClient.create(
        sys.env.get("ERGO_NODE").get,
        NetworkType.MAINNET,
        "",
        sys.env.get("ERGO_EXPLORER").get
      )
      val height = ergoClient
        .getDataSource()
        .getLastBlockHeaders(1, false)
        .get(0)
        .getHeight()
      salesdao.getAll.map(sale =>
        Ok(
          Json.toJson(
            sale.map(
              SaleLite.fromSale(_, salesdao, height, ergoClient.getDataSource())
            )
          )
        )
      )
    }
  }

  def getAllHighlighted(): Action[AnyContent] = Action.async {
    implicit request =>
      {
        val ergoClient = RestApiErgoClient.create(
          sys.env.get("ERGO_NODE").get,
          NetworkType.MAINNET,
          "",
          sys.env.get("ERGO_EXPLORER").get
        )
        val height = ergoClient
          .getDataSource()
          .getLastBlockHeaders(1, false)
          .get(0)
          .getHeight()
        salesdao.getAllHighlighted.map(sale =>
          Ok(
            Json.toJson(
              sale.map(
                SaleLite.fromSale(
                  _,
                  salesdao,
                  height,
                  ergoClient.getDataSource()
                )
              )
            )
          )
        )
      }
  }

  def getAllFiltered(status: Option[String], address: Option[String]) =
    Action.async { implicit request =>
      {
        val ergoClient = RestApiErgoClient.create(
          sys.env.get("ERGO_NODE").get,
          NetworkType.MAINNET,
          "",
          sys.env.get("ERGO_EXPLORER").get
        )
        val height = ergoClient
          .getDataSource()
          .getLastBlockHeaders(1, false)
          .get(0)
          .getHeight()
        salesdao
          .getAllFiltered(status, address)
          .map(sale =>
            Ok(
              Json.toJson(
                sale.map(
                  SaleLite
                    .fromSale(_, salesdao, height, ergoClient.getDataSource())
                )
              )
            )
          )
      }
    }

  def getAllFilteredMulti(status: Option[String]) = Action { implicit request =>
    val content = request.body
    val jsonObject = content.asJson
    val addressListJson = Json.fromJson[AddressList](jsonObject.get)

    val ergoClient = RestApiErgoClient.create(
      sys.env.get("ERGO_NODE").get,
      NetworkType.MAINNET,
      "",
      sys.env.get("ERGO_EXPLORER").get
    )
    val height = ergoClient
      .getDataSource()
      .getLastBlockHeaders(1, false)
      .get(0)
      .getHeight()

    addressListJson match {
      case je: JsError => BadRequest(JsError.toJson(je))
      case js: JsSuccess[AddressList] =>
        val addressList = js.value
        Ok(
          Json.toJson(
            addressList.addresses
              .map(addr =>
                Await.result(
                  salesdao.getAllFiltered(status, Some(addr)),
                  Duration.Inf
                )
              )
              .flatten
              .map(sale =>
                Json.toJson(
                  SaleLite.fromSale(
                    sale,
                    salesdao,
                    height,
                    ergoClient.getDataSource()
                  )
                )
              )
          )
        )

    }
  }

  def getUserPacks() = Action { implicit request =>
    val content = request.body
    val jsonObject = content.asJson
    val getPackTokensRequestJson =
      Json.fromJson[GetPackTokensRequest](jsonObject.get)

    val ergoClient = RestApiErgoClient.create(
      sys.env.get("ERGO_NODE").get,
      NetworkType.MAINNET,
      "",
      sys.env.get("ERGO_EXPLORER").get
    )

    getPackTokensRequestJson match {
      case je: JsError => BadRequest(JsError.toJson(je))
      case js: JsSuccess[GetPackTokensRequest] =>
        val getPackTokensRequest = js.value
        val userBalance = Pratir.balance(
          getPackTokensRequest.addresses.flatMap(address =>
            NodePoolDataSource
              .getAllUnspentBoxesFor(
                Address.create(address),
                ergoClient.getDataSource().asInstanceOf[NodeDataSourceImpl]
              )
              .asScala
          )
        )
        val packTokens = getPackTokensRequest.sales match {
          case None => Await.result(salesdao.getPackTokens(), Duration.Inf)
          case Some(sales) =>
            sales.flatMap(s =>
              Await.result(salesdao.getPackTokensForSale(s), Duration.Inf)
            )
        }
        Ok(
          Json.toJson(
            userBalance._2
              .filter(token => packTokens.contains(token._1))
              .map(pt => {
                val packTokenInfo =
                  Await
                    .result(salesdao.getSaleForPackToken(pt._1), Duration.Inf)(
                      0
                    )
                GetPackTokensResponse(
                  saleId = packTokenInfo._1,
                  packId = packTokenInfo._2,
                  packToken = pt._1,
                  amount = pt._2
                )
              })
          )
        )
    }
  }

  def getSale(_saleId: String) = Action { implicit request =>
    try {
      val ergoClient = RestApiErgoClient.create(
        sys.env.get("ERGO_NODE").get,
        NetworkType.MAINNET,
        "",
        sys.env.get("ERGO_EXPLORER").get
      )
      val height = ergoClient
        .getDataSource()
        .getLastBlockHeaders(1, false)
        .get(0)
        .getHeight()
      if (Pratir.isValidUUID(_saleId)) {
        Ok(
          Json.toJson(
            SaleFull.fromSaleId(
              _saleId,
              salesdao,
              height,
              ergoClient.getDataSource()
            )
          )
        )
      } else {
        val uuid = salesdao.getSaleIdBySlug(_saleId).getOrElse(UUID.randomUUID)
        Ok(
          Json.toJson(
            SaleFull.fromSaleId(
              uuid.toString,
              salesdao,
              height,
              ergoClient.getDataSource()
            )
          )
        )
      }
    } catch {
      case nse: NoSuchElementException =>
        NotFound("Could not find sale with id " + _saleId)
      case e: Exception => {
        logger.error("Caught unexpected error", e);
        BadRequest(e.getMessage())
      }
    }
  }

  def createSale() = Action { implicit request =>
    val content = request.body
    val jsonObject = content.asJson
    val sale = Json.fromJson[NewSale](jsonObject.get)

    sale match {
      case je: JsError => BadRequest(JsError.toJson(je))
      case js: JsSuccess[NewSale] =>
        val newSale = js.value

        val saleId = UUID.randomUUID()
        val encryptedPassword = Pratir.encoder.encode(newSale.password)
        val saleAdded = Sale(
          saleId,
          newSale.name,
          newSale.description,
          newSale.startTime,
          newSale.endTime,
          newSale.sellerWallet,
          SaleStatus.PENDING,
          Pratir.initialNanoErgFee,
          Pratir.saleFeePct,
          encryptedPassword,
          Instant.now(),
          Instant.now(),
          Json.toJson(newSale.profitShare)
        )
        val tokensAdded = newSale.tokens.map((token: NewTokenForSale) =>
          TokenForSale(
            UUID.randomUUID(),
            token.tokenId,
            0,
            token.amount,
            token.rarity,
            saleId
          )
        )
        val packsDBIO = DBIO.seq(newSale.packs.flatMap((pack: NewPack) => {
          pack.toPacks(saleId)
        }): _*)
        Await.result(
          db.run(
            DBIO.seq(
              Sales.sales += saleAdded,
              TokensForSale.tokensForSale ++= tokensAdded,
              packsDBIO
            )
          ),
          Duration.Inf
        )
        val ergoClient = RestApiErgoClient.create(
          sys.env.get("ERGO_NODE").get,
          NetworkType.MAINNET,
          "",
          sys.env.get("ERGO_EXPLORER").get
        )
        val height = ergoClient
          .getDataSource()
          .getLastBlockHeaders(1, false)
          .get(0)
          .getHeight()
        val fullSale = SaleFull.fromSaleId(
          saleAdded.id.toString(),
          salesdao,
          height,
          ergoClient.getDataSource()
        )
        val bootStrapTx =
          try {
            Some(
              MUnsignedTransaction(
                saleAdded.bootstrapTx(
                  newSale.sourceAddresses,
                  ergoClient,
                  salesdao
                )
              )
            )
          } catch {
            case e: Exception => None
          }
        Created(Json.toJson(CreatedSale(fullSale, bootStrapTx)))
    }
  }

  def bootstrapSale() = Action { implicit request =>
    val content = request.body
    val jsonObject = content.asJson
    val bootstrapSaleRequest: Option[BootstrapSale] =
      jsonObject.flatMap(
        Json.fromJson[BootstrapSale](_).asOpt
      )
    bootstrapSaleRequest match {
      case None => BadRequest
      case Some(bootstrapSale) => {
        val ergoClient = RestApiErgoClient.create(
          sys.env.get("ERGO_NODE").get,
          NetworkType.MAINNET,
          "",
          sys.env.get("ERGO_EXPLORER").get
        )
        val sale =
          Await
            .result(salesdao.getSale(bootstrapSale.saleId), Duration.Inf)
            ._1
            ._1
        try {
          Ok(
            Json.toJson(
              MUnsignedTransaction(
                sale.bootstrapTx(
                  bootstrapSale.sourceAddresses,
                  ergoClient,
                  salesdao
                )
              )
            )
          )
        } catch {
          case nete: NotEnoughTokensException =>
            BadRequest(
              "The wallet did not contain the tokens required for bootstrapping"
            )
          case neee: NotEnoughErgsException =>
            BadRequest("Not enough erg in wallet for bootstrapping")
          case necfc: NotEnoughCoinsForChangeException =>
            BadRequest(
              "Not enough erg for change box, try consolidating your utxos to remove this error"
            )
          case e: Exception => {
            logger.error("Caught unexpected error", e);
            BadRequest(e.getMessage())
          }
        }
      }
    }
  }

  def getBuyOrders() = Action { implicit request =>
    val content = request.body
    val jsonObject = content.asJson
    val getBuyOrdersOpt: Option[GetBuyOrdersRequest] =
      jsonObject.flatMap(
        Json.fromJson[GetBuyOrdersRequest](_).asOpt
      )
    getBuyOrdersOpt match {
      case None => BadRequest
      case Some(value) =>
        val orders = Await.result(
          salesdao.getTokenOrderHistory(
            value.addresses,
            value.sales,
            value.orders,
            value.offset,
            value.limit
          ),
          Duration.Inf
        )
        Ok(
          Json.toJson(
            orders.map(to =>
              GetBuyOrdersResponse.fromTokenOrder(to, cruxClient)
            )
          )
        )
    }
  }

  def buyOrder() = Action { implicit request =>
    val content = request.body
    val jsonObject = content.asJson
    val buyRequest: Option[BuyRequest] =
      jsonObject.flatMap(
        Json.fromJson[BuyRequest](_).asOpt
      )
    buyRequest match {
      case None => BadRequest
      case Some(buyOrder) => {
        val ergoClient = RestApiErgoClient.create(
          sys.env.get("ERGO_NODE").get,
          NetworkType.MAINNET,
          "",
          sys.env.get("ERGO_EXPLORER").get
        )
        try {
          val (unsigned, orders) = ergoClient.execute(
            new java.util.function.Function[
              BlockchainContext,
              (UnsignedTransaction, Seq[BuySaleResponse])
            ] {
              override def apply(
                  ctx: BlockchainContext
              ): (UnsignedTransaction, Seq[BuySaleResponse]) = {
                val totalPrices = new HashMap[String, Long]()
                val buyOrderBoxes =
                  buyOrder.requests.flatMap((bsr: BuySaleRequest) => {
                    bsr.packRequests.flatMap(bpr => {
                      val packPrice = Await.result(
                        salesdao.getPrice(bpr.packId),
                        Duration.Inf
                      )

                      val combinedPackPrice = new HashMap[String, Long]()
                      packPrice.foreach(p =>
                        combinedPackPrice.put(
                          p.tokenId,
                          p.amount + combinedPackPrice.getOrElse(
                            p.tokenId,
                            0L
                          )
                        )
                      )

                      val derivedPrices =
                        DerivedPrice.fromPrice(
                          packPrice,
                          ctx.getHeight(),
                          cruxClient
                        )

                      val combinedDerivedPrices =
                        derivedPrices.map(dp => {
                          val combinedDerivedPrice =
                            new HashMap[String, Long]()
                          dp.foreach(p => {
                            combinedDerivedPrice.put(
                              p.tokenId,
                              p.amount + combinedDerivedPrice.getOrElse(
                                p.tokenId,
                                0L
                              )
                            )
                          })
                          combinedDerivedPrice
                        })

                      val potentialPrices =
                        Array(combinedPackPrice) ++ combinedDerivedPrices

                      val selectedPrice = potentialPrices
                        .find(pp => pp.get(bpr.currencyTokenId).isDefined)
                        .get
                        .filter(p => p._2 > 0L)

                      scala.collection.immutable
                        .Range(0, bpr.count)
                        .map(i => {

                          val boxValue =
                            selectedPrice
                              .getOrElse("0" * 64, 0L) + 10000000L

                          totalPrices.put(
                            "0" * 64,
                            boxValue + totalPrices.getOrElse("0" * 64, 0L)
                          )
                          val outBoxBuilder = ctx
                            .newTxBuilder()
                            .outBoxBuilder()
                            .registers(
                              ErgoValueBuilder.buildFor(
                                Colls.fromArray(
                                  bsr.saleId
                                    .toString()
                                    .getBytes(StandardCharsets.UTF_8)
                                )
                              ),
                              ErgoValueBuilder.buildFor(
                                Colls.fromArray(
                                  bpr.packId
                                    .toString()
                                    .getBytes(StandardCharsets.UTF_8)
                                )
                              ),
                              ErgoValueBuilder.buildFor(
                                Colls.fromArray(
                                  Address
                                    .create(buyOrder.targetAddress)
                                    .toPropositionBytes()
                                )
                              ),
                              ErgoValueBuilder.buildFor(
                                Colls.fromArray(
                                  UUID
                                    .randomUUID()
                                    .toString()
                                    .getBytes(StandardCharsets.UTF_8)
                                )
                              )
                            )
                            .contract(
                              new ErgoTreeContract(
                                BuyOrder.contract(buyOrder.userWallet(0)),
                                NetworkType.MAINNET
                              )
                            )
                            .value(boxValue)
                          if (
                            !bpr.currencyTokenId
                              .equals("0" * 64) || selectedPrice.size > 1
                          ) {
                            val tokens = selectedPrice
                              .filter(sp => !sp._1.equals("0" * 64))
                              .map(sp => new ErgoToken(sp._1, sp._2))
                              .toArray
                            tokens.foreach(t =>
                              totalPrices.put(
                                t.getId.toString,
                                t.getValue + totalPrices
                                  .getOrElse(t.getId.toString, 0L)
                              )
                            )
                            outBoxBuilder
                              .tokens(
                                tokens: _*
                              )
                              .build()
                          } else
                            outBoxBuilder.build()
                        })
                    })
                  })

                val boxesLoader = new ExplorerAndPoolUnspentBoxesLoader()
                  .withAllowChainedTx(true)

                val boxOperations = BoxOperations
                  .createForSenders(
                    buyOrder.userWallet
                      .map(Address.create(_))
                      .toList
                      .asJava,
                    ctx
                  )
                  .withInputBoxesLoader(boxesLoader)
                  .withMaxInputBoxesToSelect(100)
                  .withFeeAmount(1000000L)
                  .withAmountToSpend(totalPrices.get("0" * 64).get)

                if (totalPrices.size > 1)
                  boxOperations.withTokensToSpend(
                    totalPrices
                      .filterNot(_._1 == "0" * 64)
                      .map((kv: (String, Long)) => new ErgoToken(kv._1, kv._2))
                      .toList
                      .asJava
                  )

                val unsigned =
                  boxOperations.buildTxWithDefaultInputs(tb =>
                    tb.addOutputs(buyOrderBoxes: _*)
                  )

                val orders = buyOrderBoxes.map(bob => {
                  BuySaleResponse(
                    saleId = UUID.fromString(
                      new String(
                        bob
                          .getRegisters()
                          .get(0)
                          .getValue()
                          .asInstanceOf[Coll[Byte]]
                          .toArray,
                        StandardCharsets.UTF_8
                      )
                    ),
                    packId = UUID.fromString(
                      new String(
                        bob
                          .getRegisters()
                          .get(1)
                          .getValue()
                          .asInstanceOf[Coll[Byte]]
                          .toArray,
                        StandardCharsets.UTF_8
                      )
                    ),
                    orderId = UUID.fromString(
                      new String(
                        bob
                          .getRegisters()
                          .get(3)
                          .getValue()
                          .asInstanceOf[Coll[Byte]]
                          .toArray,
                        StandardCharsets.UTF_8
                      )
                    )
                  )
                })

                orders.foreach(bob => {
                  Await.result(
                    salesdao.newTokenOrder(
                      bob.orderId,
                      buyOrder.targetAddress,
                      bob.saleId,
                      bob.packId
                    ),
                    Duration.Inf
                  )
                })

                (unsigned, orders)
              }
            }
          )
          val reduced = ergoClient.execute(
            new java.util.function.Function[
              BlockchainContext,
              String
            ] {
              override def apply(
                  ctx: BlockchainContext
              ): String = {
                Base64
                  .getUrlEncoder()
                  .encodeToString(
                    ctx.newProverBuilder().build().reduce(unsigned, 0).toBytes()
                  )
              }
            }
          )
          Ok(
            Json.toJson(
              BuyResponse(
                unsigned = MUnsignedTransactionResponse(
                  MUnsignedTransaction(unsigned),
                  reduced
                ),
                orders = orders
              )
            )
          )
        } catch {
          case nete: NotEnoughTokensException =>
            BadRequest("The wallet did not enough tokens for this order")
          case neee: NotEnoughErgsException =>
            BadRequest("Not enough erg in wallet for this order")
          case necfc: NotEnoughCoinsForChangeException =>
            BadRequest(
              "Not enough erg for change box, try consolidating your utxos to remove this error"
            )
          case e: Exception => {
            logger.error("Caught unexpected error", e);
            BadRequest(e.getMessage())
          }
        }
      }
    }
  }

  def highlightSale() = Action { implicit request =>
    val content = request.body
    val jsonObject = content.asJson
    val highlightSaleRequest: Option[HighlightSaleRequest] =
      jsonObject.flatMap(
        Json.fromJson[HighlightSaleRequest](_).asOpt
      )
    highlightSaleRequest match {
      case None => BadRequest
      case Some(highlightSale) => {
        try {
          if (isValidAuthToken(highlightSale.verificationToken)) {
            Await.result(
              salesdao.highlightSale(highlightSale.saleId),
              Duration.Inf
            )
            Ok(
              Json.toJson(
                HighlightSaleResponse(
                  "ok",
                  "Sale Highlighted",
                  Some(highlightSale.saleId)
                )
              )
            )
          } else {
            Unauthorized("Unauthorized - Token Verification Failed")
          }
        } catch {
          case e: Exception => {
            logger.error("Caught unexpected error", e);
            BadRequest(e.getMessage())
          }
        }
      }
    }
  }

  def removeSaleFromHighlights() = Action { implicit request =>
    val content = request.body
    val jsonObject = content.asJson
    val highlightSaleRequest: Option[HighlightSaleRequest] =
      jsonObject.flatMap(
        Json.fromJson[HighlightSaleRequest](_).asOpt
      )
    highlightSaleRequest match {
      case None => BadRequest
      case Some(highlightSale) => {
        try {
          if (isValidAuthToken(highlightSale.verificationToken)) {
            Await.result(
              salesdao.removeSaleFromHighlights(highlightSale.saleId),
              Duration.Inf
            )
            Ok(
              Json.toJson(
                HighlightSaleResponse(
                  "ok",
                  "Sale Highlight Removed",
                  Some(highlightSale.saleId)
                )
              )
            )
          } else {
            Unauthorized("Unauthorized - Token Verification Failed")
          }
        } catch {
          case e: Exception => {
            logger.error("Caught unexpected error", e);
            BadRequest(e.getMessage())
          }
        }
      }
    }
  }

  private def isValidAuthToken(verificationToken: String): Boolean = {
    val admin = sys.env.get("ADMIN_ACCESS_WALLET").get
    val authRequest = Await.result(
      usersDao.getAuthRequestByToken(verificationToken),
      Duration.Inf
    )
    if (authRequest.isDefined && authRequest.get.address.equals(admin)) {
      Await.result(usersDao.deleteAuthRequest(authRequest.get.id), Duration.Inf)
      true
    } else {
      false
    }
  }
}

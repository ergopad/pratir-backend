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
import _root_.util._
import org.ergoplatform.appkit.BoxOperations
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
import org.ergoplatform.appkit.OutBox
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughTokensException
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughErgsException
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughCoinsForChangeException
import java.time.Instant
import models.AddressList
import scala.concurrent.Future

@Singleton
class SaleController @Inject() (
    val salesdao: SalesDAO,
    val usersDao: UsersDAO,
    val controllerComponents: ControllerComponents,
    protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
    extends BaseController
    with HasDatabaseConfigProvider[JdbcProfile] {
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
  implicit val mInputJson = Json.format[MInput]
  implicit val mOutputJson = Json.format[MOutput]
  implicit val mUnsignedTransactionJson = Json.format[MUnsignedTransaction]
  implicit val createdSaleJson = Json.format[CreatedSale]
  implicit val bootstrapSaleJson = Json.format[BootstrapSale]
  implicit val saleLiteJson = Json.format[SaleLite]
  implicit val tokenOrderJson = Json.format[TokenOrder]
  implicit val HighlightSaleRequestJson = Json.format[HighlightSaleRequest]
  implicit val HighlightSaleResponseJson = Json.format[HighlightSaleResponse]

  def getAll(): Action[AnyContent] = Action.async { implicit request =>
    salesdao.getAll.map(sale =>
      Ok(Json.toJson(sale.map(SaleLite.fromSale(_, salesdao))))
    )
  }

  def getAllHighlighted(): Action[AnyContent] = Action.async {
    implicit request =>
      salesdao.getAllHighlighted.map(sale =>
        Ok(Json.toJson(sale.map(SaleLite.fromSale(_, salesdao))))
      )
  }

  def getAllFiltered(status: Option[String], address: Option[String]) =
    Action.async { implicit request =>
      salesdao
        .getAllFiltered(status, address)
        .map(sale => Ok(Json.toJson(sale.map(SaleLite.fromSale(_, salesdao)))))
    }

  def getAllFilteredMulti(status: Option[String]) = Action { implicit request =>
    val content = request.body
    val jsonObject = content.asJson
    val addressListJson = Json.fromJson[AddressList](jsonObject.get)

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
              .map(sale => Json.toJson(SaleLite.fromSale(sale, salesdao)))
          )
        )

    }
  }

  def getSale(_saleId: String) = Action { implicit request =>
    Ok(Json.toJson(SaleFull.fromSaleId(_saleId, salesdao)))
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
          Instant.now()
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
        val fullSale = SaleFull.fromSaleId(saleAdded.id.toString(), salesdao)
        val ergoClient = RestApiErgoClientWithNodePoolDataSource.create(
          sys.env.get("ERGO_NODE").get,
          NetworkType.MAINNET,
          "",
          sys.env.get("ERGO_EXPLORER").get
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
        val ergoClient = RestApiErgoClientWithNodePoolDataSource.create(
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
          case e: Exception => BadRequest(e.getMessage())
        }
      }
    }
  }

  def getBuyOrders(address: String): Action[AnyContent] = Action.async {
    implicit request =>
      val orders = salesdao.getTokenOrderHistory(address)
      orders.map(o => Ok(Json.toJson(o)))
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
        val ergoClient = RestApiErgoClientWithNodePoolDataSource.create(
          sys.env.get("ERGO_NODE").get,
          NetworkType.MAINNET,
          "",
          sys.env.get("ERGO_EXPLORER").get
        )
        try {
          Ok(
            Json.toJson(
              MUnsignedTransaction(
                ergoClient.execute(
                  new java.util.function.Function[
                    BlockchainContext,
                    UnsignedTransaction
                  ] {
                    override def apply(
                        ctx: BlockchainContext
                    ): UnsignedTransaction = {
                      val totalPrices = new HashMap[String, Long]()
                      val buyOrderBoxes =
                        buyOrder.requests.flatMap((bsr: BuySaleRequest) => {
                          bsr.packRequests.flatMap(bpr => {
                            val packPrice = Await.result(
                              salesdao.getPrice(bpr.packId),
                              Duration.Inf
                            )
                            val combinedPrices = new HashMap[String, Long]()
                            packPrice.foreach(p =>
                              combinedPrices.put(
                                p.tokenId,
                                p.amount + combinedPrices.getOrElse(
                                  p.tokenId,
                                  0L
                                )
                              )
                            )
                            scala.collection.immutable
                              .Range(0, bpr.count)
                              .map(i => {

                                val boxValue = combinedPrices
                                  .getOrElse("0" * 64, 0L) + 4000000L

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
                                val tokenPrice = combinedPrices.filterNot(cp =>
                                  cp._1 == "0" * 64 || cp._2 < 1
                                )
                                if (tokenPrice.size > 0) {
                                  val tokens = tokenPrice
                                    .map((kv: (String, Long)) =>
                                      new ErgoToken(kv._1, kv._2)
                                    )
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
                            .map((kv: (String, Long)) =>
                              new ErgoToken(kv._1, kv._2)
                            )
                            .toList
                            .asJava
                        )

                      val unsigned =
                        boxOperations.buildTxWithDefaultInputs(tb =>
                          tb.addOutputs(buyOrderBoxes: _*)
                        )

                      buyOrderBoxes.foreach(bob => {
                        Await.result(
                          salesdao.newTokenOrder(
                            UUID.fromString(
                              new String(
                                bob
                                  .getRegisters()
                                  .get(3)
                                  .getValue()
                                  .asInstanceOf[Coll[Byte]]
                                  .toArray,
                                StandardCharsets.UTF_8
                              )
                            ),
                            buyOrder.targetAddress,
                            UUID.fromString(
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
                            UUID.fromString(
                              new String(
                                bob
                                  .getRegisters()
                                  .get(1)
                                  .getValue()
                                  .asInstanceOf[Coll[Byte]]
                                  .toArray,
                                StandardCharsets.UTF_8
                              )
                            )
                          ),
                          Duration.Inf
                        )
                      })

                      unsigned
                    }
                  }
                )
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
          case e: Exception => BadRequest(e.getMessage())
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
          case e: Exception => BadRequest(e.getMessage)
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
          case e: Exception => BadRequest(e.getMessage)
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

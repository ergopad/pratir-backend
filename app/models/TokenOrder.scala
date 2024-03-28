package models

import java.util.UUID
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import slick.jdbc.PostgresProfile.api._
import database.SalesDAO
import org.ergoplatform.appkit.ErgoClient
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit.NetworkType
import contracts.BuyOrder
import scala.collection.JavaConverters._
import org.ergoplatform.appkit.InputBox
import sigma.Coll
import java.nio.charset.StandardCharsets
import scala.collection.mutable.HashMap
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.appkit.ExplorerAndPoolUnspentBoxesLoader
import org.ergoplatform.appkit.BoxOperations
import util.Pratir
import org.ergoplatform.appkit.Address
import org.ergoplatform.sdk.ErgoToken
import org.ergoplatform.appkit.UnsignedTransaction
import _root_.util.NodePoolDataSource
import org.ergoplatform.appkit.ErgoClientException
import play.api.Logging
import java.time.Instant
import org.ergoplatform.appkit.impl.OutBoxBuilderImpl
import org.ergoplatform.wallet.transactions.TransactionBuilder
import org.ergoplatform.appkit.impl.UnsignedTransactionBuilderImpl
import org.ergoplatform.appkit.OutBox
import play.api.libs.json.Json
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import com.amazonaws.services.batch.model.NodeDetails
import org.ergoplatform.appkit.impl.NodeDataSourceImpl

object TokenOrderStatus extends Enumeration {
  type TokenOrderStatus = Value
  val INITIALIZED, CONFIRMING, CONFIRMED, FULLFILLING, REFUNDING, FULLFILLED,
      REFUNDED, FAILED = Value

  implicit val readsTokenOrderStatus = Reads.enumNameReads(TokenOrderStatus)
  implicit val writesTokenOrderStatus = Writes.enumNameWrites
  implicit val statusMapper = MappedColumnType.base[TokenOrderStatus, String](
    e => e.toString,
    s => TokenOrderStatus.withName(s)
  )
}

final case class TokenOrder(
    id: UUID,
    userAddress: String,
    saleId: UUID,
    packId: UUID,
    orderBoxId: String,
    followUpTxId: String,
    status: TokenOrderStatus.Value,
    created_at: Instant,
    updated_at: Instant
) extends Logging {
  def handleInitialized(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
    val boxes = NodePoolDataSource
      .getAllUnspentBoxesFor(
        new ErgoTreeContract(
          BuyOrder.contract(userAddress),
          NetworkType.MAINNET
        ).toAddress(),
        ergoClient
          .getDataSource()
          .asInstanceOf[NodeDataSourceImpl]
      )
      .asScala

    val orderBox = boxes.filter((box: InputBox) =>
      id == UUID.fromString(
        new String(
          box.getRegisters().get(3).getValue().asInstanceOf[Coll[Byte]].toArray,
          StandardCharsets.UTF_8
        )
      )
    )
    if (orderBox.size > 0)
      Await.result(
        salesdao.updateTokenOrderStatus(
          id,
          orderBox(0).getId().toString(),
          TokenOrderStatus.CONFIRMING,
          ""
        ),
        Duration.Inf
      )
  }

  def handleSale(
      ergoClient: ErgoClient,
      salesdao: SalesDAO
  ): Option[Fulfillment] = {
    val boxes = NodePoolDataSource
      .getAllUnspentBoxesFor(
        new ErgoTreeContract(
          BuyOrder.contract(userAddress),
          NetworkType.MAINNET
        ).toAddress(),
        ergoClient
          .getDataSource()
          .asInstanceOf[NodeDataSourceImpl]
      )
      .asScala
    // Testing only
    // val boxes = ergoClient.getDataSource().asInstanceOf[NodePoolDataSource].getAllUnspentBoxesFor(new ErgoTreeContract(BuyOrder.contract(userAddress),NetworkType.MAINNET).toAddress()).asScala.toArray
    val height = ergoClient
      .getDataSource()
      .getLastBlockHeaders(1, false)
      .get(0)
      .getHeight()
    if (
      boxes.exists((box: InputBox) =>
        id == UUID.fromString(
          new String(
            box
              .getRegisters()
              .get(3)
              .getValue()
              .asInstanceOf[Coll[Byte]]
              .toArray,
            StandardCharsets.UTF_8
          )
        )
      )
    ) {

      val orderBox = boxes
        .find((box: InputBox) =>
          id == UUID.fromString(
            new String(
              box
                .getRegisters()
                .get(3)
                .getValue()
                .asInstanceOf[Coll[Byte]]
                .toArray,
              StandardCharsets.UTF_8
            )
          )
        )
        .get

      if (status == TokenOrderStatus.CONFIRMING)
        Await.result(
          salesdao.updateTokenOrderStatus(
            id,
            orderBox.getId.toString,
            TokenOrderStatus.CONFIRMED,
            ""
          ),
          Duration.Inf
        )

      val sale = Await.result(salesdao.getSale(saleId), Duration.Inf)._1._1
      val pack = Await.result(salesdao.getPack(packId), Duration.Inf)
      val fullPack = PackFull(
        pack,
        salesdao,
        orderBox.getCreationHeight()
      )
      val packPrice = fullPack.price

      val basePrice = new HashMap[String, Long]()
      packPrice.foreach(p =>
        basePrice.put(
          p.tokenId,
          p.amount + basePrice.getOrElse(p.tokenId, 0L)
        )
      )

      val derivedPrices = fullPack.derivedPrice
        .getOrElse(new Array(0))
        .map(dp => {
          val derivedPriceMap = new HashMap[String, Long]()
          dp.foreach(p => derivedPriceMap.put(p.tokenId, p.amount))
          derivedPriceMap
        })

      val potentialPrices = Array(basePrice) ++ derivedPrices

      val combinedPricesOpt = potentialPrices.find(pp => {
        orderBox
          .getValue() >= pp.getOrElse("0" * 64, 0L) + 10000000L &&
        pp
          .filterNot(cp => cp._1 == "0" * 64 || cp._2 < 1)
          .forall((token: (String, Long)) =>
            orderBox
              .getTokens()
              .asScala
              .exists((ergoToken: ErgoToken) =>
                ergoToken.getId
                  .toString() == token._1 && ergoToken.getValue >= token._2
              )
          )
      })

      val sufficientFunds = combinedPricesOpt.isDefined

      val result: Option[Fulfillment] =
        if (
          sufficientFunds && sale.status == SaleStatus.LIVE && !fullPack.soldOut
        ) {
          val combinedPrices = combinedPricesOpt.get
          val negativeTokens =
            combinedPrices.filterNot(cp => cp._1 == "0" * 64 || cp._2 > 0)
          val negativeSalesInOrder =
            try {
              negativeTokens.foreach(c => {
                val tokenForSale = Await
                  .result(salesdao.getTokenForSale(c._1, sale.id), Duration.Inf)
                Await.result(
                  salesdao.reserveToken(tokenForSale, math.abs(c._2).toInt),
                  Duration.Inf
                )
              })
              true
            } catch {
              case e: Exception => {
                logger.error(e.getMessage())
                false
              }
            }
          if (negativeSalesInOrder) {

            val packContent = fullPack.content

            val rarityList = packContent
              .flatMap(pe => {
                pe.getRarity
              })
              .map(pr => pr.rarity)
              .toSet

            val randomTokens
                : collection.mutable.Map[String, Iterator[TokenForSale]] =
              collection.mutable.Map(
                rarityList
                  .map(r => {
                    (
                      r,
                      Await
                        .result(
                          salesdao.pickRandomTokens(saleId, r, 100),
                          Duration.Inf
                        )
                        .toIterator
                    )
                  })
                  .toSeq: _*
              )

            val tokensPicked = packContent.flatMap(pe => {
              Range(0, pe.amount).map(i => {
                val randomRarity = pe.pickRarity(salesdao, saleId)
                logger.info(s"Random rarity: $randomRarity")
                var randomTokenIterator =
                  randomTokens.get(randomRarity.rarity).get
                if (!randomTokenIterator.hasNext) {
                  randomTokenIterator = Await
                    .result(
                      salesdao
                        .pickRandomTokens(saleId, randomRarity.rarity, 100),
                      Duration.Inf
                    )
                    .toIterator;
                  randomTokens.update(randomRarity.rarity, randomTokenIterator)
                }
                val randomNFT = randomTokenIterator.next()
                logger.info(s"Random token: ${randomNFT.tokenId}")
                Await.result(salesdao.reserveToken(randomNFT), Duration.Inf)
                randomNFT
              })
            })

            val tokenMap = new HashMap[String, Long]()

            tokensPicked.foreach(pick =>
              tokenMap.put(
                pick.tokenId,
                1L + tokenMap.getOrElse(pick.tokenId, 0L)
              )
            )
            var nftBox, sellerBox: Option[OutBox] = None
            var profitShareBoxes: Option[Array[OutBox]] = None
            ergoClient.execute(
              new java.util.function.Function[BlockchainContext, Unit] {
                override def apply(ctx: BlockchainContext): Unit = {

                  nftBox = Some(
                    ctx
                      .newTxBuilder()
                      .outBoxBuilder()
                      .contract(Address.create(userAddress).toErgoContract())
                      .value(1000000L)
                      .tokens(
                        tokenMap
                          .map(et => new ErgoToken(et._1, et._2))
                          .toSeq: _*
                      )
                      .build()
                  )

                  val profitShares = (Json
                    .fromJson[Array[SaleProfitShare]](sale.profitShare) match {
                    case JsError(errors)     => Array[SaleProfitShare]()
                    case JsSuccess(value, _) => value
                  }) ++ Array(
                    SaleProfitShare(
                      Address.create(Pratir.pratirFeeWallet).toString(),
                      sale.saleFeePct
                    )
                  )

                  val totalProfitSharePct =
                    profitShares.foldLeft(0)((z, ps) => z + ps.pct)

                  val sellerBoxBuilder = ctx
                    .newTxBuilder()
                    .outBoxBuilder()
                    .contract(
                      Address.create(sale.sellerWallet).toErgoContract()
                    )
                    .value(
                      combinedPrices.getOrElse(
                        "0" * 64,
                        0L
                      ) * (100 - totalProfitSharePct) / 100 + 1000000L
                    )
                  if (combinedPrices.filterNot(_._1 == "0" * 64).size > 0)
                    sellerBoxBuilder.tokens(
                      combinedPrices
                        .filterNot(_._1 == "0" * 64)
                        .map(t =>
                          new ErgoToken(
                            t._1,
                            math
                              .round(
                                math
                                  .abs(t._2)
                                  .toDouble * (100.0 - totalProfitSharePct) / 100.0
                              )
                              .toLong
                          )
                        )
                        .toArray: _*
                    )
                  sellerBox = Some(sellerBoxBuilder.build())

                  profitShareBoxes = Some(profitShares.map(ps => {

                    val feeTokens = combinedPrices.filterNot(cp =>
                      cp._1 == "0" * 64 || math.abs(
                        cp._2
                      ) * ps.pct / 100 < 1
                    )
                    val feeBoxBuilder = ctx
                      .newTxBuilder()
                      .outBoxBuilder()
                      .contract(
                        Address.create(ps.address).toErgoContract()
                      )
                      .value(
                        combinedPrices.getOrElse(
                          "0" * 64,
                          0L
                        ) * ps.pct / 100 + 1000000L
                      )
                    if (feeTokens.size > 0)
                      feeBoxBuilder.tokens(
                        feeTokens
                          .map(t =>
                            new ErgoToken(
                              t._1,
                              math.abs(t._2) * ps.pct / 100
                            )
                          )
                          .toArray: _*
                      )
                    feeBoxBuilder.build()
                  }))
                }
              }
            )
            Some(
              Fulfillment(
                saleId,
                id,
                orderBox,
                nftBox.get,
                sellerBox.get,
                profitShareBoxes.get
              )
            )
          } else {
            None
          }
        } else {
          None
        }

      if (
        !result.isDefined && !(sale.status == SaleStatus.SOLD_OUT && sale.updated_at
          .isAfter(Instant.now().minusSeconds(600)))
      ) {
        ergoClient.execute(
          new java.util.function.Function[BlockchainContext, Unit] {
            override def apply(ctx: BlockchainContext): Unit = {
              val signed = Pratir.sign(ctx, refund(ctx, orderBox))

              ctx.sendTransaction(signed)

              Await.result(
                salesdao.updateTokenOrderStatus(
                  id,
                  orderBox.getId.toString,
                  TokenOrderStatus.REFUNDING,
                  signed.getId()
                ),
                Duration.Inf
              )
            }
          }
        )
      }
      result
    } else {
      None
    }
  }

  def followUp(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {

    val mempoolTxState =
      NodePoolDataSource.getUnconfirmedTransactionState(
        followUpTxId,
        ergoClient
          .getDataSource()
          .asInstanceOf[NodeDataSourceImpl]
      )
    // If the tx is no longer in the mempool we need to ensure it is confirmed and set the state accordingly
    if (mempoolTxState == 404) {
      try {
        val orderBox =
          ergoClient.getDataSource().getBoxById(orderBoxId, false, false)
        // The order box is still not spent, so something pushed the follow up tx out of the mempool, we need to try again.
        Await.result(
          salesdao.updateTokenOrderStatus(
            id,
            orderBox.getId.toString,
            TokenOrderStatus.CONFIRMED,
            ""
          ),
          Duration.Inf
        )
      } catch {
        case e: Exception => {
          // The follow up is no longer in mempool and the order box is spent, we can set the state to FULLFILLED/REFUNDED
          if (status == TokenOrderStatus.FULLFILLING)
            Await.result(
              salesdao.updateTokenOrderStatus(
                id,
                orderBoxId,
                TokenOrderStatus.FULLFILLED,
                followUpTxId
              ),
              Duration.Inf
            )
          else
            Await.result(
              salesdao.updateTokenOrderStatus(
                id,
                orderBoxId,
                TokenOrderStatus.REFUNDED,
                followUpTxId
              ),
              Duration.Inf
            )
        }
      }
    }
  }

  def refund(
      ctx: BlockchainContext,
      orderBox: InputBox
  ): UnsignedTransaction = {
    val fee = 1000000L

    val refundBoxBuilder = ctx
      .newTxBuilder()
      .outBoxBuilder()
      .contract(Address.create(userAddress).toErgoContract())
      .value(orderBox.getValue() - fee)

    if (orderBox.getTokens().size() > 0)
      refundBoxBuilder.tokens(orderBox.getTokens().asScala: _*)

    val refundBox = refundBoxBuilder.build()

    ctx.newTxBuilder
      .addInputs(orderBox)
      .addOutputs(refundBox)
      .fee(fee)
      .sendChangeTo(Address.create(Pratir.pratirFeeWallet))
      .build
  }
}

object TokenOrder {
  implicit val json = Json.format[TokenOrder]
}

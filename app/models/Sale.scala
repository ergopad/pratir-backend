package models

import java.time.Instant
import java.util.UUID
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import slick.jdbc.PostgresProfile.api._
import contracts.SaleBox
import scala.collection.mutable.HashMap
import org.ergoplatform.appkit.ErgoClient
import database.SalesDAO
import util.Pratir
import scala.concurrent.duration.Duration
import scala.concurrent.Await
import scala.collection.JavaConverters._
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.ExplorerAndPoolUnspentBoxesLoader
import org.ergoplatform.appkit.BoxOperations
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.UnsignedTransaction
import org.ergoplatform.sdk.ErgoToken
import org.ergoplatform.appkit.InputBox
import util.NodePoolDataSource
import play.api.Logging
import org.ergoplatform.sdk.ErgoId
import play.api.libs.json.Json
import scala.collection.mutable.ArrayBuffer
import play.api.libs.json.JsValue
import org.ergoplatform.appkit.impl.NodeDataSourceImpl

object SaleStatus extends Enumeration {
  type SaleStatus = Value
  val PENDING, WAITING, LIVE, SOLD_OUT, FINISHED = Value

  implicit val readsSaleStatus = Reads.enumNameReads(SaleStatus)
  implicit val writesSaleStatus = Writes.enumNameWrites
  implicit val statusMapper = MappedColumnType.base[SaleStatus, String](
    e => e.toString,
    s => SaleStatus.withName(s)
  )
}

final case class SaleProfitShare(
    address: String,
    pct: Int
)

object SaleProfitShare {
  implicit val json = Json.format[SaleProfitShare]
}

final case class Sale(
    id: UUID,
    name: String,
    description: String,
    startTime: Instant,
    endTime: Instant,
    sellerWallet: String,
    status: SaleStatus.Value,
    initialNanoErgFee: Long,
    saleFeePct: Int,
    password: String,
    created_at: Instant,
    updated_at: Instant,
    profitShare: JsValue
) extends Logging {
  def isFinished: Boolean = Instant.now().isAfter(endTime)

  def fulfill(
      fulfillments: Array[Fulfillment],
      ergoClient: ErgoClient,
      salesdao: SalesDAO
  ) = {
    val boxesLoader =
      new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)

    val orderBoxes = fulfillments.map(f => f.orderBox)

    val nftBoxes = fulfillments.map(f => f.nftBox)

    ergoClient.execute(
      new java.util.function.Function[BlockchainContext, Unit] {
        override def apply(ctx: BlockchainContext): Unit = {

          val sellerBalance = Pratir.balance(fulfillments.map(f => f.sellerBox))
          val sellerBoxBuilder = ctx
            .newTxBuilder()
            .outBoxBuilder()
            .contract(
              new ErgoTreeContract(
                fulfillments(0).sellerBox.getErgoTree(),
                ctx.getNetworkType()
              )
            )
            .value(sellerBalance._1)
          if (sellerBalance._2.size > 0)
            sellerBoxBuilder.tokens(
              sellerBalance._2
                .map((st: (String, Long)) => new ErgoToken(st._1, st._2))
                .toList: _*
            )
          val sellerBox = sellerBoxBuilder.build()

          val profitShareAddresses = fulfillments
            .flatMap(f => f.profitShareBoxes.map(psb => psb.getErgoTree()))
            .toSet

          val profitShareBoxes = profitShareAddresses
            .map(psa => {
              val feeBalance = Pratir.balance(
                fulfillments.flatMap(f =>
                  f.profitShareBoxes.filter(psb => psb.getErgoTree() == psa)
                )
              )
              val feeBoxBuilder = ctx
                .newTxBuilder()
                .outBoxBuilder()
                .contract(
                  new ErgoTreeContract(
                    psa,
                    ctx.getNetworkType()
                  )
                )
                .value(feeBalance._1)
              if (feeBalance._2.size > 0)
                feeBoxBuilder.tokens(
                  feeBalance._2
                    .map((st: (String, Long)) => new ErgoToken(st._1, st._2))
                    .toList: _*
                )
              feeBoxBuilder.build()
            })
            .toArray

          val assetsLacking = Pratir.assetsMissing(
            orderBoxes,
            nftBoxes ++ Array(sellerBox) ++ profitShareBoxes
          )

          val boxOperations = BoxOperations
            .createForSender(getSaleAddress, ctx)
            .withInputBoxesLoader(boxesLoader)
            .withTokensToSpend(assetsLacking._2.asJava)
            .withAmountToSpend(math.max(assetsLacking._1, 1000000L))

          try {
            val unsigned = ctx.newTxBuilder
              .addOutputs(nftBoxes: _*)
              .addOutputs(sellerBox)
              .addOutputs(profitShareBoxes: _*)
              .addInputs(orderBoxes: _*)
              .fee(1000000L)
              .sendChangeTo(getSaleAddress)
              .addInputs(boxOperations.loadTop(2000000L).asScala: _*)
              .build()

            val signed = Pratir.sign(ctx, unsigned)

            fulfillments.foreach(ff =>
              Await.result(
                salesdao.updateTokenOrderStatus(
                  ff.orderId,
                  ff.orderBox.getId().toString(),
                  TokenOrderStatus.FULLFILLING,
                  signed.getId()
                ),
                Duration.Inf
              )
            )

            ctx.sendTransaction(signed)
          } catch {
            case e: Exception => logger.error(e.getMessage())
          }
        }
      }
    )
  }

  def handleLive(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
    if (status == SaleStatus.LIVE || status == SaleStatus.SOLD_OUT) {

      val boxes =
        NodePoolDataSource
          .getAllUnspentBoxesFor(
            getSaleAddress,
            ergoClient
              .getDataSource()
              .asInstanceOf[NodeDataSourceImpl]
          )
          .asScala

      val balance = Pratir.balance(boxes)

      val tokensForSale =
        Await.result(salesdao.getTokensForSale(id), Duration.Inf)

      var foundTokens = false

      tokensForSale.foreach(tfs => {
        if (tfs.amount != balance._2.getOrElse(tfs.tokenId, 0L).toInt) {
          Await.result(
            salesdao.updateTokenAmount(
              id,
              tfs.tokenId,
              balance._2.getOrElse(tfs.tokenId, 0L).toInt
            ),
            Duration.Inf
          )
        }
        if (balance._2.getOrElse(tfs.tokenId, 0L) > 0) {
          foundTokens = true
        }
      })

      if (status == SaleStatus.SOLD_OUT && foundTokens)
        Await.result(
          salesdao.updateSaleStatus(id, SaleStatus.LIVE),
          Duration.Inf
        )

    }
  }

  def handlePending(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
    if (status != SaleStatus.PENDING) return

    val boxes =
      NodePoolDataSource
        .getAllUnspentBoxesFor(
          getSaleAddress,
          ergoClient
            .getDataSource()
            .asInstanceOf[NodeDataSourceImpl]
        )
        .asScala

    val balance = Pratir.balance(boxes)

    val tokensRequired =
      Await.result(salesdao.getTokensForSale(id), Duration.Inf)
    // check base fee
    val baseFeeDeposit = balance._1 >= initialNanoErgFee + boxes.size * 1000000L
    val tokensDeposit = tokensRequired.forall(tr => {
      if (tr.amount != balance._2.getOrElse(tr.tokenId, 0L))
        Await.result(
          salesdao.updateTokenAmount(
            id,
            tr.tokenId,
            balance._2.getOrElse(tr.tokenId, 0L).toInt
          ),
          Duration.Inf
        )
      tr.originalAmount <= balance._2.getOrElse(tr.tokenId, 0L)
    })

    if (baseFeeDeposit && tokensDeposit) {
      Await.result(
        salesdao.updateSaleStatus(id, SaleStatus.WAITING),
        Duration.Inf
      )
    }
  }

  def handleWaiting(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
    if (status != SaleStatus.WAITING) return
    ergoClient.execute(
      new java.util.function.Function[BlockchainContext, Unit] {
        override def apply(ctx: BlockchainContext): Unit = {

          val feeBox = ctx
            .newTxBuilder()
            .outBoxBuilder()
            .contract(Address.create(Pratir.pratirFeeWallet).toErgoContract())
            .value(initialNanoErgFee)
            .build()

          val boxesLoader =
            new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)

          val boxOperations = BoxOperations
            .createForSender(getSaleAddress, ctx)
            .withInputBoxesLoader(boxesLoader)
            .withFeeAmount(1000000L)
            .withAmountToSpend(initialNanoErgFee)

          val unsigned =
            boxOperations.buildTxWithDefaultInputs(tb => tb.addOutputs(feeBox))

          val signed = Pratir.sign(ctx, unsigned)

          ctx.sendTransaction(signed)

          Await.result(
            salesdao.updateSaleStatus(id, SaleStatus.LIVE),
            Duration.Inf
          )
        }
      }
    )
  }

  def bootstrapTx(
      fromAddresses: Array[String],
      ergoClient: ErgoClient,
      salesdao: SalesDAO
  ): UnsignedTransaction = {
    val tokens = Await.result(salesdao.getTokensForSale(id), Duration.Inf)
    ergoClient.execute(
      new java.util.function.Function[BlockchainContext, UnsignedTransaction] {
        override def apply(ctx: BlockchainContext): UnsignedTransaction = {
          val currentBoxes =
            NodePoolDataSource
              .getAllUnspentBoxesFor(
                getSaleAddress,
                ctx
                  .getDataSource()
                  .asInstanceOf[NodeDataSourceImpl]
              )
              .asScala

          val ergNeeded =
            2000000L + initialNanoErgFee + currentBoxes.size * 1000000L - currentBoxes
              .foldLeft(0L)((z: Long, box: InputBox) => z + box.getValue)

          val outBoxBuilder =
            ctx
              .newTxBuilder()
              .outBoxBuilder()
              .value(ergNeeded)
              .contract(getSaleAddress.toErgoContract())

          if (tokens.filter(t => t.amount < t.originalAmount).size > 0)
            outBoxBuilder.tokens(
              tokens
                .filter(t => t.amount < t.originalAmount)
                .take(50)
                .map(et =>
                  new ErgoToken(et.tokenId, et.originalAmount - et.amount)
                ): _*
            )

          val outBox = outBoxBuilder.build()

          val boxesLoader =
            new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)

          val boxOperations = BoxOperations
            .createForSenders(
              fromAddresses.map(Address.create(_)).toList.asJava,
              ctx
            )
            .withInputBoxesLoader(boxesLoader)
            .withFeeAmount(1000000L)
            .withAmountToSpend(ergNeeded)
            .withTokensToSpend(outBox.getTokens())

          boxOperations.buildTxWithDefaultInputs(tb => tb.addOutputs(outBox))
        }
      }
    )
  }

  def packTokensToMint(salesdao: SalesDAO, collectionId: UUID) = {
    implicit val packEntryJson = Json.format[PackEntry]
    val minted = ArrayBuffer[String]()

    val packs = Await
      .result(salesdao.getPacks(id), Duration.Inf)
      .map(p => {
        PackFull(p, salesdao)
      })
    packs.flatMap(p => {
      p.price.find(pr => pr.tokenId.contains("_pt_")) match {
        case Some(pr) =>
          val values = pr.tokenId.split("_")
          val amount = values(2).toLong
          val name = values(3).trim()
          if (minted.contains(name)) {
            Array[NFT]()
          } else {
            minted.append(name)

            val description = Json
              .toJson[Array[NewPackEntry]](
                p.content.map(pe =>
                  NewPackEntry(
                    Json.fromJson[Seq[PackRarity]](pe.rarity).get,
                    pe.amount
                  )
                )
              )
              .toString()
            Array(
              NFT(
                UUID.randomUUID(),
                collectionId,
                "",
                amount,
                name,
                p.image,
                description,
                Json.toJson[Seq[Trait]](Array[Trait]()),
                "_pt_rarity_" + name,
                false,
                NFTStatus.INITIALIZED,
                "",
                Json.toJson[Seq[Royalty]](Array[Royalty]()),
                Instant.now(),
                Instant.now()
              )
            )
          }
        case None =>
          Array[NFT]()
      }
    })
  }

  def getSaleAddress = {
    new ErgoTreeContract(SaleBox.contract(this), NetworkType.MAINNET)
      .toAddress()
  }
}

object Sale {
  implicit val json = Json.format[Sale]
}

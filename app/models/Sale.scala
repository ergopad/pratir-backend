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
import org.ergoplatform.appkit.ErgoToken
import org.ergoplatform.appkit.InputBox
import util.NodePoolDataSource
import play.api.Logging

object SaleStatus extends Enumeration {
    type SaleStatus = Value
    val PENDING, WAITING, LIVE, FINISHED = Value

    implicit val readsSaleStatus = Reads.enumNameReads(SaleStatus)
    implicit val writesSaleStatus = Writes.enumNameWrites
    implicit val statusMapper = MappedColumnType.base[SaleStatus, String](
        e => e.toString,
        s => SaleStatus.withName(s)
    )
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
    password: String
) extends Logging {
    def isFinished: Boolean = Instant.now().isAfter(endTime)

    def handleLive(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
        if (status != SaleStatus.LIVE) return
        
        val boxes = ergoClient.getDataSource().asInstanceOf[NodePoolDataSource].getAllUnspentBoxesFor(getSaleAddress).asScala
        
        val balance = Pratir.balance(boxes.toArray)

        val tokensForSale = Await.result(salesdao.getTokensForSale(id), Duration.Inf)

        tokensForSale.foreach(tfs => {
            if (tfs.amount != balance.getOrElse(tfs.tokenId,0L).toInt) {
                Await.result(salesdao.updateTokenAmount(id, tfs.tokenId, balance.getOrElse(tfs.tokenId,0L).toInt), Duration.Inf)
            }
        })
    }

    def handlePending(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
        if (status != SaleStatus.PENDING) return
                
        val boxes = ergoClient.getDataSource().asInstanceOf[NodePoolDataSource].getAllUnspentBoxesFor(getSaleAddress).asScala
        
        val balance = Pratir.balance(boxes.toArray)

        val tokensRequired = Await.result(salesdao.getTokensForSale(id),Duration.Inf)
        //check base fee
        val baseFeeDeposit = balance.getOrElse("nanoerg",0L) >= initialNanoErgFee + boxes.size*1000000L
        val tokensDeposit = tokensRequired.forall(tr => {
            if (tr.amount != balance.getOrElse(tr.tokenId,0L))
                Await.result(salesdao.updateTokenAmount(id,tr.tokenId,balance.getOrElse(tr.tokenId,0L).toInt), Duration.Inf)
            tr.originalAmount <= balance.getOrElse(tr.tokenId,0L)
        })

        if (baseFeeDeposit && tokensDeposit) {
            Await.result(salesdao.updateSaleStatus(id,SaleStatus.WAITING),Duration.Inf)
        }
    }

    def handleWaiting(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
        if (status != SaleStatus.WAITING) return
        ergoClient.execute(new java.util.function.Function[BlockchainContext,Unit] {
            override def apply(ctx: BlockchainContext): Unit = {
                
                val feeBox = ctx.newTxBuilder().outBoxBuilder()
                    .contract(Address.create(Pratir.pratirFeeWallet).toErgoContract())
                    .value(initialNanoErgFee)
                    .build()
                
                val boxesLoader = new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)
                
                val boxOperations = BoxOperations.createForSender(getSaleAddress,ctx)
                    .withInputBoxesLoader(boxesLoader)
                    .withMaxInputBoxesToSelect(20)
                    .withFeeAmount(1000000L)
                    .withAmountToSpend(initialNanoErgFee)
                
                val unsigned = boxOperations.buildTxWithDefaultInputs(tb => tb.addOutputs(feeBox))
                
                val signed = Pratir.sign(ctx,unsigned)
                
                ctx.sendTransaction(signed)
                
                Await.result(salesdao.updateSaleStatus(id,SaleStatus.LIVE),Duration.Inf)
            }
        })
    }

    def bootstrapTx(fromAddresses: Array[String], ergoClient: ErgoClient, salesdao: SalesDAO): UnsignedTransaction = {
        val tokens = Await.result(salesdao.getTokensForSale(id), Duration.Inf)
        ergoClient.execute(new java.util.function.Function[BlockchainContext,UnsignedTransaction] {
            override def apply(ctx: BlockchainContext): UnsignedTransaction = {
                val currentBoxes = ctx.getDataSource().asInstanceOf[NodePoolDataSource].getAllUnspentBoxesFor(getSaleAddress).asScala

                val ergNeeded = 2000000L + initialNanoErgFee + currentBoxes.size*1000000L - currentBoxes.foldLeft(0L)((z: Long, box: InputBox) => z + box.getValue)

                val outBoxBuilder = 
                    ctx.newTxBuilder().outBoxBuilder()
                    .value(ergNeeded)
                    .contract(getSaleAddress.toErgoContract())

                if (tokens.filter(t => t.amount < t.originalAmount).size > 0) 
                    outBoxBuilder.tokens(tokens.filter(t => t.amount < t.originalAmount).take(50).map(et => new ErgoToken(et.tokenId, et.originalAmount-et.amount)):_*)
                    
                val outBox = outBoxBuilder.build()
                
                val boxesLoader = new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)

                val boxOperations = BoxOperations.createForSenders(fromAddresses.map(Address.create(_)).toList.asJava,ctx)
                        .withInputBoxesLoader(boxesLoader)
                        .withFeeAmount(1000000L)
                        .withAmountToSpend(ergNeeded)
                        .withTokensToSpend(outBox.getTokens())

                boxOperations.buildTxWithDefaultInputs(tb => tb.addOutputs(outBox))
            }
        })
    }

    def getSaleAddress = {
        new ErgoTreeContract(SaleBox.contract(this),NetworkType.MAINNET).toAddress()
    }
}
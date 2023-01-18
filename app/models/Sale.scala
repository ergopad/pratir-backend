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
    saleFeePct: Int
) {
    def isFinished: Boolean = Instant.now().isAfter(endTime)

    def handlePending(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
        if (status != SaleStatus.PENDING) return
        val balance = new HashMap[String,Long]()
                
        val boxes = ergoClient.getDataSource().getUnspentBoxesFor(getSaleAddress,0,100)
        boxes.asScala.foreach(utxo =>
            {
                balance.put("nanoerg",utxo.getValue()+balance.getOrElse("nanoerg",0L))
                utxo.getTokens().asScala.foreach(token =>
                    balance.put(token.getId().toString(),token.getValue()+balance.getOrElse(token.getId().toString(),0L)))
            })

        val tokensRequired = Await.result(salesdao.getTokensForSale(id),Duration.Inf)
        //check base fee
        val baseFeeDeposit = balance.getOrElse("nanoerg",0L) >= initialNanoErgFee + 1000000L
        val tokensDeposit = tokensRequired.forall(tr => tr.amount <= balance.getOrElse(tr.tokenId,0L))

        if (baseFeeDeposit && tokensDeposit) {
            Await.result(salesdao.updateSaleStatus(id,SaleStatus.WAITING),Duration.Inf)
        }
    }

    def handleWaiting(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
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

    def getSaleAddress = {
        new ErgoTreeContract(SaleBox.contract(this),NetworkType.MAINNET).toAddress()
    }
}
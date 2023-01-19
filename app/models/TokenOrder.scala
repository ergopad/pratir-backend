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
import special.collection.Coll
import java.nio.charset.StandardCharsets
import scala.collection.mutable.HashMap
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.appkit.ExplorerAndPoolUnspentBoxesLoader
import org.ergoplatform.appkit.BoxOperations
import util.Pratir
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.ErgoToken
import org.ergoplatform.appkit.UnsignedTransaction
import _root_.util.NodePoolDataSource
import org.ergoplatform.appkit.ErgoClientException

object TokenOrderStatus extends Enumeration {
    type TokenOrderStatus = Value
    val INITIALIZED, CONFIRMING, CONFIRMED, FULLFILLING, REFUNDING, FULLFILLED, REFUNDED, FAILED = Value

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
    status: TokenOrderStatus.Value
) {
    def handleInitialized(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
        val boxes = ergoClient.getDataSource().getUnconfirmedUnspentBoxesFor(new ErgoTreeContract(BuyOrder.contract(userAddress),NetworkType.MAINNET).toAddress(),0,100).asScala.toArray
                
        if (boxes.exists((box: InputBox) => id == UUID.fromString(new String(box.getRegisters().get(3).getValue().asInstanceOf[Coll[Byte]].toArray,StandardCharsets.UTF_8))))
            Await.result(salesdao.updateTokenOrderStatus(id,"",TokenOrderStatus.CONFIRMING,""),Duration.Inf)
    }

    def handleSale(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
        val boxes = ergoClient.getDataSource().getUnspentBoxesFor(new ErgoTreeContract(BuyOrder.contract(userAddress),NetworkType.MAINNET).toAddress(),0,100).asScala.toArray
                
        if (boxes.exists((box: InputBox) => id == UUID.fromString(new String(box.getRegisters().get(3).getValue().asInstanceOf[Coll[Byte]].toArray,StandardCharsets.UTF_8)))) {

            val orderBox = boxes.find((box: InputBox) => id == UUID.fromString(new String(box.getRegisters().get(3).getValue().asInstanceOf[Coll[Byte]].toArray,StandardCharsets.UTF_8))).get
            
            Await.result(salesdao.updateTokenOrderStatus(id,orderBox.getId.toString, TokenOrderStatus.CONFIRMED,""),Duration.Inf)

            val sale = Await.result(salesdao.getSale(saleId), Duration.Inf)
            
            val packPrice = Await.result(salesdao.getPrice(packId), Duration.Inf)
            
            val combinedPrices = new HashMap[String, Long]()
            packPrice.foreach(p => combinedPrices.put(p.tokenId, p.amount + combinedPrices.getOrElse(p.tokenId,0L)))
            
            val sufficientFunds = orderBox.getValue() >= combinedPrices.getOrElse("0"*64,0L) + 4000000L && 
                combinedPrices.filterNot(cp => cp._1 == "0"*64 || cp._2 < 1)
                .forall((token: (String, Long)) => 
                    orderBox.getTokens().asScala.exists((ergoToken: ErgoToken) => 
                        ergoToken.getId().toString() == token._1 && ergoToken.getValue() >= token._2))

            if (sufficientFunds && sale.status == SaleStatus.LIVE) {
                
                val pack = Await.result(salesdao.getPackEntries(packId), Duration.Inf)
                
                val tokensPicked = pack.flatMap(pe => {
                    Range(0,pe.amount).map(i => {
                        val randomNFT = Await.result(salesdao.pickRandomToken(saleId, pe.category), Duration.Inf)
                        Await.result(salesdao.reserveToken(randomNFT),Duration.Inf)
                        randomNFT
                    })
                })
                
                val sale = Await.result(salesdao.getSale(saleId),Duration.Inf)
                
                val tokenMap = new HashMap[String,Long]()
                
                tokensPicked.foreach(pick => tokenMap.put(pick.tokenId, 1l + tokenMap.getOrElse(pick.tokenId,0L)))
                
                ergoClient.execute(new java.util.function.Function[BlockchainContext,Unit] {
                    override def apply(ctx: BlockchainContext): Unit = {
                        
                        val nftBox = ctx.newTxBuilder().outBoxBuilder()
                            .contract(Address.create(userAddress).toErgoContract())
                            .value(1000000L)
                            .tokens(tokenMap.map(et => new ErgoToken(et._1,et._2)).toSeq:_*)
                            .build()
                        
                        val sellerBoxBuilder = ctx.newTxBuilder().outBoxBuilder()
                            .contract(Address.create(sale.sellerWallet).toErgoContract())
                            .value(combinedPrices.getOrElse("0"*64,0L)*100/(100-sale.saleFeePct)+1000000L)
                        if (combinedPrices.filterNot(_._1 == "0"*64).size > 0)
                            sellerBoxBuilder.tokens(combinedPrices.filterNot(_._1 == "0"*64).map(t => new ErgoToken(t._1,math.round(math.abs(t._2).toDouble*100/(100-sale.saleFeePct)).toLong)).toArray:_*)
                        val sellerBox = sellerBoxBuilder.build()                              
                        
                        val feeTokens = combinedPrices.filterNot(cp => cp._1 == "0"*64 || math.abs(cp._2)*100/sale.saleFeePct < 1)
                        val feeBoxBuilder = ctx.newTxBuilder().outBoxBuilder()
                            .contract(Address.create(Pratir.pratirFeeWallet).toErgoContract())
                            .value(combinedPrices.getOrElse("0"*64,0L)*100/sale.saleFeePct+1000000L)
                        if (feeTokens.size > 0)
                            feeBoxBuilder.tokens(feeTokens.map(t => new ErgoToken(t._1,math.abs(t._2)*100/sale.saleFeePct)).toArray:_*)
                        val feeBox = feeBoxBuilder.build()

                        val boxesLoader = new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)
                        
                        val boxOperations = BoxOperations.createForSender(sale.getSaleAddress,ctx).withInputBoxesLoader(boxesLoader)
                            .withMaxInputBoxesToSelect(20)
                            .withFeeAmount(1000000L)
                            .withTokensToSpend(nftBox.getTokens())
                        
                        val unsigned = boxOperations.buildTxWithDefaultInputs(tb => tb.addOutputs(nftBox, sellerBox, feeBox).addInputs(orderBox))
                        
                        val signed = Pratir.sign(ctx,unsigned)
                        
                        ctx.sendTransaction(signed)
                        
                        Await.result(salesdao.updateTokenOrderStatus(id,orderBox.getId.toString,TokenOrderStatus.FULLFILLING,signed.getId()),Duration.Inf)
                    }
                })
            } else {
                ergoClient.execute(new java.util.function.Function[BlockchainContext,Unit] {
                    override def apply(ctx: BlockchainContext): Unit = {
                        val signed = Pratir.sign(ctx,refund(ctx, orderBox))
                                
                        ctx.sendTransaction(signed)
                        
                        Await.result(salesdao.updateTokenOrderStatus(id,orderBox.getId.toString,TokenOrderStatus.REFUNDING,signed.getId()),Duration.Inf)
                    }
                })
            }
        }
    }

    def followUp(ergoClient: ErgoClient, salesdao: SalesDAO): Unit = {
        val mempoolTxState = ergoClient.getDataSource().asInstanceOf[NodePoolDataSource].getUnconfirmedTransactionState(followUpTxId)
        //If the tx is no longer in the mempool we need to ensure it is confirmed and set the state accordingly
        if (mempoolTxState == 404) {
            try {
                val orderBox = ergoClient.getDataSource().getBoxById(orderBoxId, false, false)
                //The order box is still not spent, so something pushed the follow up tx out of the mempool, we need to try again.
                Await.result(salesdao.updateTokenOrderStatus(id,orderBox.getId.toString,TokenOrderStatus.CONFIRMED,""),Duration.Inf)
            } catch {
                case e: Exception => {
                    //The follow up is no longer in mempool and the order box is spent, we can set the state to FULLFILLED/REFUNDED
                    if (status == TokenOrderStatus.FULLFILLING)
                        Await.result(salesdao.updateTokenOrderStatus(id,orderBoxId,TokenOrderStatus.FULLFILLED,followUpTxId),Duration.Inf)
                    else
                        Await.result(salesdao.updateTokenOrderStatus(id,orderBoxId,TokenOrderStatus.REFUNDED,followUpTxId),Duration.Inf)
                }
            }
        }
    }

    def refund(ctx: BlockchainContext, orderBox: InputBox): UnsignedTransaction = {
        val fee = 1000000L

        val refundBoxBuilder = ctx.newTxBuilder().outBoxBuilder()
            .contract(Address.create(userAddress).toErgoContract())
            .value(orderBox.getValue()-fee)

        if (orderBox.getTokens().size() > 0)
            refundBoxBuilder.tokens(orderBox.getTokens().asScala:_*)
            
        val refundBox = refundBoxBuilder.build()

        ctx.newTxBuilder.addInputs(orderBox).addOutputs(refundBox).fee(fee).sendChangeTo(Address.create(Pratir.pratirFeeWallet)).build 
    }
}

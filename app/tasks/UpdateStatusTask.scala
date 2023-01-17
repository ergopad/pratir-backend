package tasks

import javax.inject.Inject
import javax.inject.Named

import akka.actor.ActorRef
import akka.actor.ActorSystem

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import database._
import slick.jdbc.JdbcProfile
import play.api.db.slick._
import scala.concurrent.ExecutionContext.Implicits.global
import slick.basic.DatabasePublisher
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.Await
import java.time.LocalDateTime
import java.time.Instant
import models.SaleStatus
import org.ergoplatform.appkit.RestApiErgoClient
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.BlockchainContext
import util._
import scala.collection.JavaConverters._
import scala.collection.mutable.HashMap
import org.ergoplatform.appkit.CoveringBoxes
import org.ergoplatform.appkit.BoxOperations
import org.ergoplatform.appkit.ExplorerAndPoolUnspentBoxesLoader
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import org.ergoplatform.appkit.SignedInput
import org.ergoplatform.appkit.impl.SignedInputImpl
import models.TokenOrderStatus
import contracts.BuyOrder
import org.ergoplatform.appkit.impl.ErgoTreeContract
import java.nio.charset.StandardCharsets
import special.collection.Coll
import java.util.UUID
import org.ergoplatform.appkit.InputBox
import org.ergoplatform.appkit.ErgoToken
import org.ergoplatform.appkit.Address
import scala.util.Random
import org.ergoplatform.appkit.ErgoClient
import play.api.Logging

class UpdateStatusTask @Inject() (protected val dbConfigProvider: DatabaseConfigProvider, actorSystem: ActorSystem)
extends  HasDatabaseConfigProvider[JdbcProfile] with Logging {
    actorSystem.scheduler.scheduleWithFixedDelay(initialDelay = 5.seconds, delay = 10.seconds)(() =>
        try {
            Random.setSeed(Instant.now().toEpochMilli())
            val ergoClient = RestApiErgoClientWithNodePoolDataSource.create(sys.env.get("ERGO_NODE").get,NetworkType.MAINNET,"",sys.env.get("ERGO_EXPLORER").get)
            val salesdao = new SalesDAO(dbConfigProvider)
            logger.info("Handling open orders...")
            try {
                handleOrders(ergoClient,salesdao)
            } catch {
                case e: Exception => logger.error(e.getMessage())
            }
            logger.info("Handling sales...")
            try {
                handleSales(ergoClient,salesdao)
            } catch {
                case e: Exception => logger.error(e.getMessage())
            }
        } catch {
            case e: Exception => logger.error(e.getMessage(), e)
        }
    )

    def handleSales(ergoClient: ErgoClient, salesdao: SalesDAO) = {
        val activeSales = Await.result(salesdao.getAllActive, Duration.Inf)
            activeSales.foreach(as => {
                if (Instant.now().isAfter(as.endTime)) {
                    Await.result(salesdao.updateSaleStatus(as.id,SaleStatus.FINISHED),Duration.Inf)
                } else if (as.status == SaleStatus.PENDING) {
                    val balance = new HashMap[String,Long]()
                    val boxes = ergoClient.getDataSource().getUnspentBoxesFor(Pratir.getSaleAddress(as),0,100)
                    boxes.asScala.foreach(utxo =>
                        {
                            balance.put("nanoerg",utxo.getValue()+balance.getOrElse("nanoerg",0L))
                            utxo.getTokens().asScala.foreach(token =>
                                balance.put(token.getId().toString(),token.getValue()+balance.getOrElse(token.getId().toString(),0L)))
                        })

                    val tokensRequired = Await.result(salesdao.getTokensForSale(as.id),Duration.Inf)
                    //check base fee
                    val baseFeeDeposit = balance.getOrElse("nanoerg",0L) >= as.initialNanoErgFee + 1000000L
                    val tokensDeposit = tokensRequired.forall(tr => tr.amount <= balance.getOrElse(tr.tokenId,0L))

                    if (baseFeeDeposit && tokensDeposit) {
                        Await.result(salesdao.updateSaleStatus(as.id,SaleStatus.WAITING),Duration.Inf)
                    }
                } else if (as.status == SaleStatus.WAITING && Instant.now().isAfter(as.startTime)) {
                    ergoClient.execute(new java.util.function.Function[BlockchainContext,Unit] {
                            override def apply(ctx: BlockchainContext): Unit = {
                                val feeBox = ctx.newTxBuilder().outBoxBuilder().contract(Address.create(Pratir.pratirFeeWallet).toErgoContract()).value(as.initialNanoErgFee).build()
                                val boxesLoader = new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)
                                val boxOperations = BoxOperations.createForSender(Pratir.getSaleAddress(as),ctx).withInputBoxesLoader(boxesLoader)
                                    .withMaxInputBoxesToSelect(20).withFeeAmount(1000000L).withAmountToSpend(as.initialNanoErgFee)
                                val unsigned = boxOperations.buildTxWithDefaultInputs(tb => tb.addOutputs(feeBox))
                                val signed = Pratir.sign(ctx,unsigned)
                                ctx.sendTransaction(signed)
                                Await.result(salesdao.updateSaleStatus(as.id,SaleStatus.LIVE),Duration.Inf)
                            }
                    })
                }
            })
    }

    def handleOrders(ergoClient: ErgoClient, salesdao: SalesDAO) = {
        val openOrders = Await.result(salesdao.getOpenTokenOrders, Duration.Inf)
        openOrders.foreach(oto => {
            if (oto.status == TokenOrderStatus.INITIALIZED) {
                val boxes = ergoClient.getDataSource().getUnconfirmedUnspentBoxesFor(new ErgoTreeContract(BuyOrder.contract(oto.userAddress),NetworkType.MAINNET).toAddress(),0,100).asScala.toArray
                if (boxes.exists((box: InputBox) => oto.orderBoxId == UUID.fromString(new String(box.getRegisters().get(3).getValue().asInstanceOf[Coll[Byte]].toArray,StandardCharsets.UTF_8))))
                    Await.result(salesdao.updateTokenOrderStatus(oto.id,TokenOrderStatus.CONFIRMING,""),Duration.Inf)
            }
            if (oto.status == TokenOrderStatus.INITIALIZED || oto.status == TokenOrderStatus.CONFIRMING || oto.status == TokenOrderStatus.CONFIRMED) {
                val boxes = ergoClient.getDataSource().getUnspentBoxesFor(new ErgoTreeContract(BuyOrder.contract(oto.userAddress),NetworkType.MAINNET).toAddress(),0,100).asScala.toArray
                if (boxes.exists((box: InputBox) => oto.orderBoxId == UUID.fromString(new String(box.getRegisters().get(3).getValue().asInstanceOf[Coll[Byte]].toArray,StandardCharsets.UTF_8)))) {
                    Await.result(salesdao.updateTokenOrderStatus(oto.id,TokenOrderStatus.CONFIRMED,""),Duration.Inf)
                    val orderBox = boxes.find((box: InputBox) => oto.orderBoxId == UUID.fromString(new String(box.getRegisters().get(3).getValue().asInstanceOf[Coll[Byte]].toArray,StandardCharsets.UTF_8))).get
                    val packPrice = Await.result(salesdao.getPrice(oto.packId), Duration.Inf)
                    val combinedPrices = new HashMap[String, Long]()
                    packPrice.foreach(p => combinedPrices.put(p.tokenId, p.amount + combinedPrices.getOrElse(p.tokenId,0L)))
                    val sufficientFunds = orderBox.getValue() >= combinedPrices.getOrElse("0"*64,0L) && 
                        combinedPrices.filterNot(_._1 == "0"*64)
                        .forall((token: (String, Long)) => orderBox.getTokens().asScala.exists((ergoToken: ErgoToken) => ergoToken.getId().toString() == token._1 && ergoToken.getValue() >= token._2))
                    if (sufficientFunds) {
                        val pack = Await.result(salesdao.getPackEntries(oto.packId), Duration.Inf)
                        val tokensPicked = pack.flatMap(pe => {
                            Range(0,pe.amount).map(i => {
                                val randomNFT = Await.result(salesdao.pickRandomToken(oto.saleId, pe.category), Duration.Inf)
                                Await.result(salesdao.reserveToken(randomNFT),Duration.Inf)
                                randomNFT
                            })
                        })
                        val sale = Await.result(salesdao.getSale(oto.saleId),Duration.Inf)
                        val tokenMap = new HashMap[String,Long]()
                        tokensPicked.foreach(pick => tokenMap.put(pick.tokenId, 1l + tokenMap.getOrElse(pick.tokenId,0L)))
                        ergoClient.execute(new java.util.function.Function[BlockchainContext,Unit] {
                            override def apply(ctx: BlockchainContext): Unit = {
                                val nftBox = ctx.newTxBuilder().outBoxBuilder().contract(Address.create(oto.userAddress).toErgoContract()).value(1000000L).tokens(tokenMap.map(et => new ErgoToken(et._1,et._2)).toSeq:_*).build()
                                val sellerBox = ctx.newTxBuilder().outBoxBuilder().contract(Address.create(sale.sellerWallet).toErgoContract()).value(combinedPrices.getOrElse("0"*64,1000000L)).build()                              
                                val boxesLoader = new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)
                                val boxOperations = BoxOperations.createForSender(Pratir.getSaleAddress(sale),ctx).withInputBoxesLoader(boxesLoader)
                                    .withMaxInputBoxesToSelect(20).withFeeAmount(1500000L)
                                val unsigned = boxOperations.buildTxWithDefaultInputs(tb => tb.addOutputs(nftBox, sellerBox).addInputs(orderBox))
                                val signed = Pratir.sign(ctx,unsigned)
                                ctx.sendTransaction(signed)
                                Await.result(salesdao.updateTokenOrderStatus(oto.id,TokenOrderStatus.FULLFILLING,signed.getId()),Duration.Inf)
                            }
                        })
                    } else {
                        //Refund
                        // ergoClient.execute(new java.util.function.Function[BlockchainContext,Unit] {
                        // override def apply(ctx: BlockchainContext): Unit = {
                        //     val refundBox = ctx.newTxBuilder().outBoxBuilder().contract(Address.create(oto.userAddress)).value(orderBox.getValue()-1000000L).build()
                        //     val boxesLoader = new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)
                        //     val boxOperations = BoxOperations.createForSender(Pratir.getSaleAddress(as),ctx).withInputBoxesLoader(boxesLoader)
                        //         .withMaxInputBoxesToSelect(20).withFeeAmount(1000000L)
                        //     val unsigned = boxOperations.buildTxWithDefaultInputs(tb => tb.addOutputs(feeBox))
                        //     val signed = Pratir.sign(ctx,unsigned)
                        //     ctx.sendTransaction(signed)
                        //     println("5")
                        //     Await.result(salesdao.updateSaleStatus(as.id,SaleStatus.LIVE),Duration.Inf)
                        // 
                    }
                }
            }
        })        
    }
}


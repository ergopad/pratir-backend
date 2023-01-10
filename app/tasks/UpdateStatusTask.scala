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
import util.Pratir
import scala.collection.JavaConverters._
import scala.collection.mutable.HashMap
import org.ergoplatform.appkit.CoveringBoxes
import org.ergoplatform.appkit.BoxOperations
import org.ergoplatform.appkit.ExplorerAndPoolUnspentBoxesLoader
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import org.ergoplatform.appkit.SignedInput
import org.ergoplatform.appkit.impl.SignedInputImpl

class UpdateStatusTask @Inject() (protected val dbConfigProvider: DatabaseConfigProvider, actorSystem: ActorSystem)
extends  HasDatabaseConfigProvider[JdbcProfile]{
  actorSystem.scheduler.scheduleWithFixedDelay(initialDelay = 5.seconds, delay = 5.seconds)(() =>
    try {
        val salesdao = new SalesDAO(dbConfigProvider)
        val activeSales = Await.result(salesdao.getAllActive, Duration.Inf)
        activeSales.map(as => {
            println("Checking active sale")
            if (Instant.now().isAfter(as.endTime)) {
                println("Sale is finished")
                Await.result(salesdao.updateSaleStatus(as.id,SaleStatus.FINISHED),Duration.Inf)
            } else if (as.status == SaleStatus.PENDING) {
                println("Status pending")
                //Checking to see whether all NFT's are present in sale wallet
                val ergoClient = RestApiErgoClient.create(sys.env.get("ERGO_NODE").get,NetworkType.MAINNET,"",sys.env.get("ERGO_EXPLORER").get)
                println("Checking balance for ".concat(Pratir.getSaleAddress(as).toString()))
                val balance = new HashMap[String,Long]()
                val boxes = ergoClient.getDataSource().getUnspentBoxesFor(Pratir.getSaleAddress(as),0,100)
                println(boxes.size())
                boxes.asScala.foreach(utxo =>
                    {
                        balance.put("nanoerg",utxo.getValue()+balance.getOrElse("nanoerg",0L))
                        utxo.getTokens().asScala.foreach(token =>
                            balance.put(token.getId().toString(),token.getValue()+balance.getOrElse(token.getId().toString(),0L)))
                    })

                val tokensRequired = Await.result(salesdao.getTokensForSale(as.id),Duration.Inf)
                balance.foreach(println(_))
                //check base fee
                val baseFeeDeposit = balance.getOrElse("nanoerg",0L) >= 100000000
                val tokensDeposit = tokensRequired.forall(tr => tr.amount <= balance.getOrElse(tr.tokenId,0L))

                if (baseFeeDeposit && tokensDeposit) {
                    Await.result(salesdao.updateSaleStatus(as.id,SaleStatus.WAITING),Duration.Inf)
                }
            } else if (as.status == SaleStatus.WAITING && Instant.now().isAfter(as.startTime)) {
                val ergoClient = RestApiErgoClient.create(sys.env.get("ERGO_NODE").get,NetworkType.MAINNET,"",sys.env.get("ERGO_EXPLORER").get)
                ergoClient.execute(new java.util.function.Function[BlockchainContext,Unit] {
                        override def apply(ctx: BlockchainContext): Unit = {
                            println("1")
                            val feeBox = ctx.newTxBuilder().outBoxBuilder().contract(Pratir.address().toErgoContract()).value(100000000L).build()
                            println("2")
                            val boxesLoader = new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)
                            println("3")
                            val boxOperations = BoxOperations.createForSender(Pratir.getSaleAddress(as),ctx).withInputBoxesLoader(boxesLoader)
                                .withMaxInputBoxesToSelect(20).withFeeAmount(1000000L)
                            println("4")
                            val unsigned = boxOperations.buildTxWithDefaultInputs(tb => tb.addOutputs(feeBox))
                            println("4.5")
                            val signed = Pratir.sign(ctx,unsigned)
                            println("4.75")
                            ctx.sendTransaction(signed)
                            println("5")
                            Await.result(salesdao.updateSaleStatus(as.id,SaleStatus.LIVE),Duration.Inf)
                        }
                })
            }
        })
    } catch {
        case e: Exception => println(e)
    }
  )
}


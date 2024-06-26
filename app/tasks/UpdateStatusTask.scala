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
import sigma.Coll
import java.util.UUID
import org.ergoplatform.appkit.InputBox
import org.ergoplatform.sdk.ErgoToken
import org.ergoplatform.appkit.Address
import scala.util.Random
import org.ergoplatform.appkit.ErgoClient
import play.api.Logging
import org.ergoplatform.appkit.OutBox
import models.Fulfillment
import scala.collection.mutable.Buffer
import scala.collection.mutable.ArrayBuffer
import models.NFTCollectionStatus
import _root_.org.ergoplatform.appkit.impl.NodeDataSourceImpl

class UpdateStatusTask @Inject() (
    protected val dbConfigProvider: DatabaseConfigProvider,
    actorSystem: ActorSystem,
    val cruxClient: CruxClient
) extends HasDatabaseConfigProvider[JdbcProfile]
    with Logging {
  actorSystem.scheduler.scheduleWithFixedDelay(
    initialDelay = 5.seconds,
    delay = 10.seconds
  )(() =>
    try {
      Random.setSeed(Instant.now().toEpochMilli())
      val ergoClient = RestApiErgoClient.create(
        sys.env.get("ERGO_NODE").get,
        NetworkType.MAINNET,
        "",
        sys.env.get("ERGO_EXPLORER").get
      )
      val salesdao = new SalesDAO(dbConfigProvider, cruxClient)
      val mintdao = new MintDAO(dbConfigProvider)
      logger.info("Handling open orders...")
      try {
        handleOrders(ergoClient, salesdao)
      } catch {
        case e: Exception => logger.error(e.getMessage())
      }
      logger.info("Handling sales...")
      try {
        handleSales(ergoClient, salesdao)
      } catch {
        case e: Exception => logger.error(e.getMessage())
      }
    } catch {
      case e: Exception => logger.error(e.getMessage(), e)
    }
  )

  def handleSales(ergoClient: ErgoClient, salesdao: SalesDAO) = {

    val activeSales =
      Await.result(salesdao.getAllActive, Duration.Inf).map(_._1._1)

    activeSales.foreach(as => {
      try {
        if (as.status == SaleStatus.LIVE || as.status == SaleStatus.SOLD_OUT) {

          as.handleLive(ergoClient, salesdao)

        }

        val tokensLeft =
          Await.result(salesdao.tokensLeft(as.id), Duration.Inf).getOrElse(0)

        if (as.status == SaleStatus.LIVE && tokensLeft < 1) {

          Await.result(
            salesdao.updateSaleStatus(as.id, SaleStatus.SOLD_OUT),
            Duration.Inf
          )

        }

        if (as.isFinished) {

          Await.result(
            salesdao.updateSaleStatus(as.id, SaleStatus.FINISHED),
            Duration.Inf
          )

        } else if (as.status == SaleStatus.PENDING) {

          as.handlePending(ergoClient, salesdao)

        } else if (
          as.status == SaleStatus.WAITING && Instant.now().isAfter(as.startTime)
        ) {

          as.handleWaiting(ergoClient, salesdao)

        }
      } catch {
        case e: Exception => logger.error(e.getMessage())
      }
    })
  }

  def handleOrders(ergoClient: ErgoClient, salesdao: SalesDAO) = {

    val mempoolState =
      NodePoolDataSource.getMempoolBoxes(
        ergoClient.getDataSource().asInstanceOf[NodeDataSourceImpl]
      )

    val confirmedOrderBoxes = HashMap[String, Seq[InputBox]]()

    val initializedOrders =
      Await.result(salesdao.getInitializedTokenOrders, Duration.Inf)

    val fulfillments = new HashMap[UUID, Buffer[Fulfillment]]()

    initializedOrders.foreach(oto => {
      try {
        if (oto.status == TokenOrderStatus.INITIALIZED) {

          oto.handleInitialized(
            ergoClient,
            salesdao,
            mempoolState,
            confirmedOrderBoxes
          )

        }
      } catch {
        case e: Exception => logger.error(e.getMessage())
      }
    })

    val confirmingOrders =
      Await.result(salesdao.getConfirmingTokenOrders, Duration.Inf)

    confirmingOrders.foreach(cto => {
      try {
        cto.handleConfirming(ergoClient, salesdao)
      } catch {
        case e: Exception => logger.error(e.getMessage())
      }
    })

    val confirmedOrders =
      Await.result(salesdao.getConfirmedTokenOrders, Duration.Inf)

    confirmedOrders.foreach(oto => {
      try {
        oto.handleSale(ergoClient, salesdao, mempoolState, cruxClient) match {
          case Some(fulfillment) =>
            fulfillments.put(
              fulfillment.saleId,
              fulfillments.getOrElse(
                fulfillment.saleId,
                new ArrayBuffer[Fulfillment]()
              ) ++ Array(fulfillment)
            )
          case None =>
        }
      } catch {
        case e: Exception => logger.error(e.getMessage())
      }
    })

    fulfillments.foreach(ff =>
      ff._2
        .grouped(30)
        .foreach(batch =>
          Await
            .result(salesdao.getSale(ff._1), Duration.Inf)
            ._1
            ._1
            .fulfill(batch.toArray, ergoClient, salesdao)
        )
    )

    val followUpOrder =
      Await.result(salesdao.getFollowUpTokenOrders, Duration.Inf)

    followUpOrder.foreach(fto => {
      try {
        fto.followUp(ergoClient, salesdao)
      } catch {
        case e: Exception => logger.error(e.getMessage())
      }
    })

  }
}

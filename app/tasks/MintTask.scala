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
import org.ergoplatform.appkit.OutBox
import models.Fulfillment
import scala.collection.mutable.Buffer
import scala.collection.mutable.ArrayBuffer
import models.NFTCollectionStatus

class MintTask @Inject() (
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
      val ergoClient = RestApiErgoClientWithNodePoolDataSource.create(
        sys.env.get("ERGO_NODE").get,
        NetworkType.MAINNET,
        "",
        sys.env.get("ERGO_EXPLORER").get
      )
      val salesdao = new SalesDAO(dbConfigProvider, cruxClient)
      val mintdao = new MintDAO(dbConfigProvider)
      val usersdao = new UsersDAO(dbConfigProvider)
      logger.info("Handling mints...")
      try {
        handleMints(ergoClient, mintdao, salesdao, usersdao)
      } catch {
        case e: Exception => logger.error(e.getMessage())
      }
    } catch {
      case e: Exception => logger.error(e.getMessage(), e)
    }
  )

  def handleMints(
      ergoClient: ErgoClient,
      mintdao: MintDAO,
      salesdao: SalesDAO,
      usersdao: UsersDAO
  ) = {
    val mintingNFTs = Await.result(mintdao.getNFTsMinting, Duration.Inf)

    try {
      mintingNFTs.foreach(_.followUp(ergoClient, mintdao, salesdao, usersdao))
    } catch {
      case e: Exception => logger.error(e.getMessage())
    }

    val unmintedCollections =
      Await.result(mintdao.getUnmintedCollections(), Duration.Inf)

    unmintedCollections.foreach(umc => {
      try {

        if (umc.status == NFTCollectionStatus.INITIALIZED) {
          umc.handleInitialized(ergoClient, mintdao, usersdao)
        }

        if (umc.status == NFTCollectionStatus.MINTING) {
          umc.followUp(ergoClient, mintdao, usersdao)
        }

        if (umc.status == NFTCollectionStatus.MINTING_NFTS) {
          umc.mintNFTs(ergoClient, mintdao, salesdao, usersdao)
        }

      } catch {
        case e: Exception =>
          logger.error(e.getMessage())
          logger.error(e.getStackTrace().mkString("\n"))
      }
    })
  }
}

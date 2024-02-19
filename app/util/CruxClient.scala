package util

import javax.inject._

import play.api.libs.ws.WSClient
import scala.concurrent.duration.Duration
import scala.concurrent.Await
import play.api.cache.AsyncCacheApi
import java.util.concurrent.TimeUnit
import play.api.cache.SyncCacheApi
import org.ergoplatform.appkit.BlockchainDataSource
import play.api.libs.json.JsValue

final case class PriceStats(
    decimals: Int,
    maxUsd: Double,
    maxErg: Double,
    minUsd: Double,
    minErg: Double,
    avgUsd: Double,
    avgErg: Double
)

@Singleton
class CruxClient @Inject() (ws: WSClient, cache: SyncCacheApi) {
  val cacheExpire = Duration.create(10.0, TimeUnit.MINUTES)
  def get_price_stats(
      tokenId: String,
      timepoint: Long,
      timeWindow: Long
  ): PriceStats = {
    cache.getOrElseUpdate(tokenId + timepoint.toString(), cacheExpire)({
      val request = ws
        .url("https://api.cruxfinance.io/spectrum/price_stats")
        .withQueryStringParameters(
          ("token_id", tokenId),
          ("time_point", timepoint.toString()),
          ("time_window", timeWindow.toString())
        )
        .get()
      val response = Await.result(request, Duration.Inf)
      PriceStats(
        decimals = response.json.\("token_info").\("decimals").get.as[Int],
        maxUsd = response.json.\("max").\("usd").get.as[Double],
        maxErg = response.json.\("max").\("erg").get.as[Double],
        minUsd = response.json.\("min").\("usd").get.as[Double],
        minErg = response.json.\("min").\("erg").get.as[Double],
        avgUsd = response.json.\("average").\("usd").get.as[Double],
        avgErg = response.json.\("average").\("erg").get.as[Double]
      )
    })
  }

  def getTokensFromFollowUp(
      orderBoxId: String,
      followUpTxId: String
  ): Seq[(String, Long)] = {
    val followUpTx = cache.getOrElseUpdate(followUpTxId, cacheExpire)({
      val request = ws
        .url(
          sys.env
            .get("ERGO_NODE")
            .get + "/blockchain/transaction/byId/" + followUpTxId
        )
        .get()
      val response = Await.result(request, Duration.Inf)
      response
    })
    val inputs = followUpTx.json.\("inputs").get.as[Seq[JsValue]]
    val orderBoxIndex =
      Range(0, inputs.length).toSeq.find(i =>
        orderBoxId.equals(inputs(i)("boxId").as[String])
      )
    orderBoxIndex match {
      case None => Seq()
      case Some(value) =>
        val outputAssets =
          followUpTx.json("outputs")(value)("assets").as[Seq[JsValue]]
        outputAssets.map(oa =>
          (oa("tokenId").as[String], oa("amount").as[Long])
        )
    }
  }

  def heightToTimestamp(height: Int): Long = {
    cache.getOrElseUpdate(height.toString())({
      val request = ws
        .url(sys.env.get("ERGO_NODE").get + "/blocks/chainSlice")
        .withQueryStringParameters(
          ("fromHeight", height.toString()),
          ("toHeight", height.toString())
        )
        .get()
      val response = Await.result(request, Duration.Inf)
      val timestamp = response.json.\(0).\("timestamp").get.as[Long]
      timestamp
    })
  }
}

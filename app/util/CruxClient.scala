package util

import javax.inject._

import play.api.libs.ws.WSClient
import scala.concurrent.duration.Duration
import scala.concurrent.Await

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
class CruxClient @Inject() (ws: WSClient) {
  def get_price_stats(
      tokenId: String,
      timepoint: Long,
      timeWindow: Long
  ): PriceStats = {
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
  }
}

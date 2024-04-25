package models

import play.api.libs.json.Json
import java.util.UUID
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import database.SalesDAO

final case class TokenSaleStats(
    tokenId: String,
    sold: Int,
    remaining: Int
)

object TokenSaleStats {
  implicit val json = Json.format[TokenSaleStats]
}

final case class SaleStats(
    sold: Int,
    remaining: Int,
    tokenStats: Seq[TokenSaleStats]
)

object SaleStats {
  implicit val json = Json.format[SaleStats]

  def getPacksStats(_saleId: UUID, salesdao: SalesDAO): SaleStats = {
    val packTokenIds =
      Await.result(
        salesdao.getPackTokensForSale(_saleId),
        Duration.Inf
      )
    val stats =
      Await.result(salesdao.getTokensForSale(packTokenIds), Duration.Inf)
    val sold = stats.foldLeft(0) { (c, tfs) =>
      c + (tfs.originalAmount - tfs.amount)
    }
    // Hardcoded 25k cap for blitz, should be a sale config
    val remaining = math.min(
      stats.foldLeft(0) { (c, tfs) =>
        c + tfs.originalAmount
      },
      25000
    ) - sold
    SaleStats(
      sold = sold,
      remaining = remaining,
      tokenStats = stats.map { tfs =>
        TokenSaleStats(
          tokenId = tfs.tokenId,
          sold = tfs.originalAmount - tfs.amount,
          remaining = tfs.amount
        )
      }
    )

  }
}

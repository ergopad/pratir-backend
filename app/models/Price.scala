package models

import java.util.UUID
import play.api.libs.json.Json
import util.CruxClient
import javax.inject._
import org.joda.time.DateTime
import org.joda.time.DateTimeZone
import org.ergoplatform.appkit.BlockchainDataSource

final case class DerivedPrice(
    derivedFrom: UUID,
    tokenId: String,
    amount: Long,
    packId: UUID
)

@Singleton class PriceToDerivedPrice(val cruxClient: CruxClient) {}

object DerivedPrice {
  implicit val json = Json.format[DerivedPrice]

  val supported_tokens = Array(
    "0" * 64, // Erg
    "03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04" // SigUSD
  )

  def fromPrice(
      prices: Seq[Price],
      height: Int,
      cruxClient: CruxClient,
      buffer: Double = 0.0
  ): Array[Array[DerivedPrice]] = {
    prices
      .flatMap(price => {
        if (DerivedPrice.supported_tokens.contains(price.tokenId)) {
          val otherPrices = prices
            .filter(p => !p.tokenId.equals(price.tokenId))
            .map(p => DerivedPrice(p.id, p.tokenId, p.amount, p.packId))
          val currentTime =
            cruxClient.heightToTimestamp(height) / 1000;
          val timeWindow = 12 * 3600;
          val baseTokenPrice =
            cruxClient.get_price_stats(price.tokenId, currentTime, timeWindow);
          val basePriceValue =
            baseTokenPrice.avgUsd * price.amount / math.pow(
              10,
              baseTokenPrice.decimals
            );
          Some(
            DerivedPrice.supported_tokens
              .filter(st => !st.equals(price.tokenId))
              .map(st => {
                val derivedTokenPrice =
                  cruxClient.get_price_stats(st, currentTime, timeWindow);
                val derivedAmount = math.max(
                  math.round(
                    basePriceValue / derivedTokenPrice.avgUsd * math
                      .pow(10, derivedTokenPrice.decimals)
                  ),
                  1L
                )
                Array(
                  DerivedPrice(
                    price.id,
                    st,
                    derivedAmount,
                    price.packId
                  )
                ) ++ otherPrices
              })
          )
        } else {
          None
        }
      })
      .flatten
      .toArray
  }
}

final case class Price(
    id: UUID,
    tokenId: String,
    amount: Long,
    packId: UUID
)

object Price {
  implicit val json = Json.format[Price]
}

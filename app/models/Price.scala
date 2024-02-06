package models

import java.util.UUID
import play.api.libs.json.Json
import util.CruxClient
import javax.inject.Inject
import org.joda.time.DateTime
import org.joda.time.DateTimeZone

final case class DerivedPrice(
    derivedFrom: UUID,
    tokenId: String,
    amount: Long,
    packId: UUID
)

object DerivedPrice {
  implicit val json = Json.format[DerivedPrice]

  @Inject var cruxClient: CruxClient = null;

  val supported_tokens = Array(
    "0" * 64, // Erg
    "03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04" // SigUSD
  )

  def fromPrice(
      price: Price,
      buffer: Double = 0.0
  ): Option[Array[DerivedPrice]] = {
    if (supported_tokens.contains(price.tokenId)) {
      val currentTime = DateTime.now(DateTimeZone.UTC).getMillis() / 1000;
      val timeWindow = 12 * 3600;
      val baseTokenPrice =
        cruxClient.get_price_stats(price.tokenId, currentTime, timeWindow);
      val basePriceValue =
        baseTokenPrice.avgUsd * price.amount / math.pow(
          10,
          baseTokenPrice.decimals
        );
      Some(
        supported_tokens
          .filter(st => !st.equals(price.tokenId))
          .map(st => {
            val derivedTokenPrice =
              cruxClient.get_price_stats(st, currentTime, timeWindow);
            val derivedAmount = math.round(
              basePriceValue / derivedTokenPrice.avgUsd * math
                .pow(10, derivedTokenPrice.decimals)
            )
            DerivedPrice(
              price.id,
              st,
              derivedAmount,
              price.packId
            )
          })
      )
    } else {
      None
    }
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

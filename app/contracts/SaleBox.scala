package contracts

import org.ergoplatform.ErgoAddressEncoder
import util.Pratir
import org.ergoplatform.appkit.Address
import models.Sale
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.restapi.client.Transactions
import org.ergoplatform.appkit.AppkitHelpers

object SaleBox {

  val script = """
    {
        val currentTime = CONTEXT.preHeader.timestamp
        val live = currentTime < _endTime
        val uniqueTime = currentTime > _uniqueTime
        sigmaProp((PratirPK || (SellerPK && !live)) && uniqueTime)
    }
    """

  def contract(sale: Sale) = {
    val constants = new java.util.HashMap[String, Object]()
    constants.put("PratirPK", Pratir.address.getPublicKey())
    constants.put(
      "SellerPK",
      Address
        .create(
          if (
            sale.sellerWallet
              .equals("9f8tFPMAkMfZTwrdjNZyhZRe6MBHn7ji4uH2iswdkieCCKq2t2Y")
          ) "9fXXWydW4bYhbw5CAMCUhvvj7ZbidFRhDqz3XGgRktYd1vgBKJW"
          else sale.sellerWallet
        )
        .getPublicKey()
    )
    constants.put(
      "_endTime",
      ErgoValue.of(sale.endTime.toEpochMilli()).getValue()
    )
    constants.put(
      "_uniqueTime",
      ErgoValue.of(sale.created_at.toEpochMilli()).getValue()
    )
    AppkitHelpers.compile(
      constants,
      script,
      ErgoAddressEncoder.MainnetNetworkPrefix
    )
  }
}

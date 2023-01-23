package contracts

import org.ergoplatform.appkit.JavaHelpers
import org.ergoplatform.ErgoAddressEncoder
import util.Pratir
import org.ergoplatform.appkit.Address
import models.Sale
import org.ergoplatform.appkit.ErgoValue
import org.ergoplatform.restapi.client.Transactions

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
        val constants = new java.util.HashMap[String,Object]()
        constants.put("PratirPK",Pratir.address.getPublicKey())
        constants.put("SellerPK",Address.create(sale.sellerWallet).getPublicKey())
        constants.put("_endTime", ErgoValue.of(sale.endTime.toEpochMilli()).getValue())
        constants.put("_uniqueTime", ErgoValue.of(sale.created_at.toEpochMilli()).getValue())
        JavaHelpers.compile(
            constants, script, ErgoAddressEncoder.MainnetNetworkPrefix
        )
    } 
}

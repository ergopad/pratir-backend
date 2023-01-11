package contracts

import util.Pratir
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.JavaHelpers
import org.ergoplatform.ErgoAddressEncoder

object BuyOrder {
    //R4: sale id
    //R5: pack id
    //R6: user address
    val script = """
    {
        val timestamp = CONTEXT.preHeader.timestamp
        sigmaProp((UserPK || PratirPK) && timestamp > 0L)
    }
    """

    def contract(userAddress: String) = {
        val constants = new java.util.HashMap[String,Object]()
        constants.put("PratirPK",Pratir.address.getPublicKey())
        constants.put("UserPK",Address.create(userAddress).getPublicKey())
        JavaHelpers.compile(
            constants, script, ErgoAddressEncoder.MainnetNetworkPrefix
        )
    } 
}

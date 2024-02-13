package contracts

import util.Pratir
import org.ergoplatform.appkit.Address
import org.ergoplatform.ErgoAddressEncoder
import org.ergoplatform.appkit.AppkitHelpers

object Mint {
  // R4: sale id
  // R5: pack id
  // R6: user address
  val script = """
    {
        val timestamp = CONTEXT.preHeader.timestamp
        sigmaProp((PratirPK || UserPK) && timestamp > 0L)
    }
    """

  def contract(userAddress: String) = {
    val constants = new java.util.HashMap[String, Object]()
    constants.put("PratirPK", Pratir.address.getPublicKey())
    constants.put("UserPK", Address.create(userAddress).getPublicKey())
    AppkitHelpers.compile(
      constants,
      script,
      ErgoAddressEncoder.MainnetNetworkPrefix
    )
  }
}

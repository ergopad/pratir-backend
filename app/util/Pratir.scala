package util

import org.ergoplatform.appkit.ErgoProver
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import org.ergoplatform.appkit.UnsignedTransaction
import org.ergoplatform.appkit.SecretString
import org.ergoplatform.appkit.Mnemonic
import scorex.crypto.authds.merkle.sparse.BlockchainSimulator
import org.ergoplatform.ErgoAddress
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.NetworkType
import models.Sale
import contracts.SaleBox
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.appkit.AppkitProvingInterpreter
import org.ergoplatform.wallet.mnemonic.{Mnemonic => WMnemonic}
import org.ergoplatform.wallet.secrets.ExtendedSecretKey
import sigmastate.basics.DiffieHellmanTupleProverInput

object Pratir {

    private val masterKey = {
        val seed = mnemonic.toSeed()
        ExtendedSecretKey.deriveMasterKey(seed, false)
    }

    private val dHTInputs: java.util.List[DiffieHellmanTupleProverInput] =
        new java.util.ArrayList[DiffieHellmanTupleProverInput](0)

    lazy val mnemonic = Mnemonic.create(SecretString.create(sys.env.get("SECRET").get),SecretString.create(""))

    def sign(ctx: BlockchainContext, unsigned: UnsignedTransaction) = {
        ctx.newProverBuilder().withMnemonic(mnemonic,false).withEip3Secret(0).build().sign(unsigned)
    }

    def address() = 
        Address.createEip3Address(0,NetworkType.MAINNET,mnemonic.getPhrase(),mnemonic.getPassword(),false)

    def getSaleAddress(sale: Sale) = {
        new ErgoTreeContract(SaleBox.contract(sale),NetworkType.MAINNET).toAddress()
    }
}

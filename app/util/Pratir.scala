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
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.ergoplatform.appkit.InputBox
import scala.collection.mutable.HashMap
import scala.collection.JavaConverters._

object Pratir {

    val encoder = new Argon2PasswordEncoder(32,64,1,15*1024,2)

    private val masterKey = {
        val seed = mnemonic.toSeed()
        ExtendedSecretKey.deriveMasterKey(seed, false)
    }

    lazy val mnemonic = Mnemonic.create(SecretString.create(sys.env.get("SECRET").get),SecretString.create(""))

    def sign(ctx: BlockchainContext, unsigned: UnsignedTransaction) = {
        ctx.newProverBuilder().withMnemonic(mnemonic,false).withEip3Secret(0).build().sign(unsigned)
    }

    def address() = 
        Address.createEip3Address(0,NetworkType.MAINNET,mnemonic.getPhrase(),mnemonic.getPassword(),false)

    lazy val initialNanoErgFee = sys.env.get("INITIAL_NANO_ERG_FEE").get.toLong
    lazy val saleFeePct = sys.env.get("SALE_FEE_PCT").get.toInt
    lazy val pratirFeeWallet = sys.env.get("PRATIR_FEE_WALLET").get

    def balance(boxes: Array[InputBox]): HashMap[String, Long] = {
        val balance = new HashMap[String,Long]()
        boxes.foreach(utxo =>
            {
                balance.put("nanoerg",utxo.getValue()+balance.getOrElse("nanoerg",0L))
                utxo.getTokens().asScala.foreach(token =>
                    balance.put(token.getId().toString(),token.getValue()+balance.getOrElse(token.getId().toString(),0L)))
            })
        balance
    }
}

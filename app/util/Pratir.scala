package util

import org.ergoplatform.appkit.ErgoProver
import org.ergoplatform.appkit.impl.BlockchainContextImpl
import org.ergoplatform.appkit.UnsignedTransaction
import org.ergoplatform.sdk.SecretString
import org.ergoplatform.appkit.Mnemonic
import scorex.crypto.authds.merkle.sparse.BlockchainSimulator
import org.ergoplatform.ErgoAddress
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.NetworkType
import models.Sale
import contracts.SaleBox
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.sdk.AppkitProvingInterpreter
import org.ergoplatform.wallet.mnemonic.{Mnemonic => WMnemonic}
import sigmastate.crypto.DiffieHellmanTupleProverInput
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder
import org.ergoplatform.appkit.InputBox
import scala.collection.mutable.HashMap
import scala.collection.JavaConverters._
import org.ergoplatform.appkit.TransactionBox
import org.ergoplatform.appkit.OutBox
import org.ergoplatform.sdk.ErgoToken
import java.util.UUID
import org.ergoplatform.sdk.wallet.secrets.ExtendedSecretKey

object Pratir {

  val encoder = new Argon2PasswordEncoder(32, 64, 1, 15 * 1024, 2)

  private val masterKey = {
    val seed = mnemonic.toSeed()
    ExtendedSecretKey.deriveMasterKey(seed, false)
  }

  lazy val mnemonic = Mnemonic.create(
    SecretString.create(sys.env.get("SECRET").get),
    SecretString.create("")
  )

  def sign(ctx: BlockchainContext, unsigned: UnsignedTransaction) = {
    ctx
      .newProverBuilder()
      .withMnemonic(mnemonic, false)
      .withEip3Secret(0)
      .build()
      .sign(unsigned)
  }

  def address() =
    Address.createEip3Address(
      0,
      NetworkType.MAINNET,
      mnemonic.getPhrase(),
      mnemonic.getPassword(),
      false
    )

  lazy val initialNanoErgFee = sys.env.get("INITIAL_NANO_ERG_FEE").get.toLong
  lazy val saleFeePct = sys.env.get("SALE_FEE_PCT").get.toInt
  lazy val pratirFeeWallet = sys.env.get("PRATIR_FEE_WALLET").get

  def balance(boxes: Seq[TransactionBox]): (Long, HashMap[String, Long]) = {
    var nergs = 0L
    val balance = new HashMap[String, Long]()
    boxes.foreach(utxo => {
      nergs += utxo.getValue()
      utxo
        .getTokens()
        .asScala
        .foreach(token =>
          balance.put(
            token.getId.toString(),
            token.getValue + balance.getOrElse(token.getId.toString(), 0L)
          )
        )
    })
    (nergs, balance)
  }

  def assetsMissing(
      inputs: Array[InputBox],
      outputs: Array[OutBox]
  ): (Long, List[ErgoToken]) = {
    val inputsBalance = balance(inputs)
    val outputsBalance = balance(outputs)

    val nergLacking = outputsBalance._1 - inputsBalance._1

    val tokensLacking = new HashMap[String, Long]()
    outputsBalance._2.foreach(out =>
      if (out._2 > inputsBalance._2.getOrElse(out._1, 0L))
        tokensLacking.put(
          out._1,
          out._2 - inputsBalance._2.getOrElse(out._1, 0L)
        )
    )

    (nergLacking, tokensLacking.map(tl => new ErgoToken(tl._1, tl._2)).toList)
  }

  def stringToUrl(str: String) = {
    // Replace all spaces with dashes and convert to lowercase
    val processedStr = str.replaceAll("\\s+", "-").toLowerCase()
    // Remove all special characters using a regular expression
    processedStr.replaceAll("[^\\w-]+", "")
  }

  /** This code was GPT generated :O
    */
  def isValidUUID(uuidStr: String): Boolean = {
    try {
      UUID.fromString(uuidStr)
      true
    } catch {
      case _: IllegalArgumentException => false
    }
  }
}

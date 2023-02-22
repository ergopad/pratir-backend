package models

import java.util.UUID
import play.api.libs.json.JsValue
import java.time.Instant
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import slick.jdbc.PostgresProfile.api._
import play.api.libs.json.Json
import org.ergoplatform.appkit.ErgoClient
import database.MintDAO
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import util.NodePoolDataSource
import util.Pratir
import scala.collection.JavaConverters._
import play.api.Logging
import database.SalesDAO
import org.ergoplatform.appkit.Address
import contracts.Mint
import org.ergoplatform.appkit.InputBox
import org.ergoplatform.appkit.scalaapi.ErgoValueBuilder
import sigmastate.eval.Colls
import org.ergoplatform.appkit.ErgoValue
import java.nio.charset.StandardCharsets
import org.ergoplatform.appkit.ErgoId
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.appkit.impl.ErgoTreeContract
import org.ergoplatform.appkit.impl.Eip4TokenBuilder
import scala.io.Source
import java.security.MessageDigest
import java.io.IOException
import java.net.URL
import java.io.BufferedInputStream
import org.ergoplatform.appkit.UnsignedTransaction
import org.ergoplatform.appkit.Eip4Token

object NFTStatus extends Enumeration {
    type NFTStatus = Value
    val INITIALIZED, MINTING, MINTED, REFUNDING, REFUNDED, FAILED = Value

    implicit val readsNFTStatus = Reads.enumNameReads(NFTStatus)
    implicit val writesNFTStatus = Writes.enumNameWrites
    implicit val statusMapper = MappedColumnType.base[NFTStatus, String](
        e => e.toString,
        s => NFTStatus.withName(s)
    )
}

final case class NFT(
    id: UUID,
    collectionId: UUID,
    tokenId: String,
    amount: Long,
    name: String,
    image: String,
    description: String,
    traits: JsValue,
    rarity: String,
    explicit: Boolean,
    status: NFTStatus.Value,
    mintTransactionId: String,
    royalty: JsValue,
    createdAt: Instant,
    updatedAt: Instant
) extends Logging {

    def mint(issuerBox: InputBox, ergoClient: ErgoClient, mintdao: MintDAO, salesdao: SalesDAO, nextNFT: Option[NFT]): UnsignedTransaction = {
        val collection = Await.result(mintdao.getCollection(collectionId), Duration.Inf)
        if (!registersMatch(collection, issuerBox)) {
            throw new Exception("Registers not correct in issuerbox")
        }
        ergoClient.execute(new java.util.function.Function[BlockchainContext,UnsignedTransaction] {
            override def apply(ctx: BlockchainContext): UnsignedTransaction = {
                val u = new URL(image)
                val uc = u.openConnection()
                val contentType = uc.getContentType()
                val contentLength = uc.getContentLength()
                if (contentType.startsWith("text/") || contentLength == -1) {
                    throw new IOException("This is not a binary file.")
                }
                val raw = uc.getInputStream()
                val in = new BufferedInputStream(raw)
                val data = new Array[Byte](contentLength)
                var bytesRead = 0
                var offset = 0
                while (offset < contentLength) {
                    bytesRead = in.read(data, offset, data.length - offset);
                    if (bytesRead > -1)
                        offset += bytesRead
                }
                in.close()

                val digest = MessageDigest.getInstance("SHA-256");

                val hash = digest.digest(data)

                val newIssuerBoxBuilder = ctx.newTxBuilder().outBoxBuilder()
                    .contract(new ErgoTreeContract(issuerBox.getErgoTree(),ctx.getNetworkType()))
                    .value(issuerBox.getValue()-2000000L)
                    .tokens(issuerBox.getTokens().asScala:_*)

                val newIssuerBox = nextNFT match {
                        case None => newIssuerBoxBuilder.build()
                        case Some(next) => newIssuerBoxBuilder.registers(next.issuerBoxRegisters(collection):_*).build()
                    }


                val mintNFTBox = if (rarity.contains("_pt_")) {
                    ctx.newTxBuilder().outBoxBuilder()
                    .mintToken(
                        new Eip4Token(
                            issuerBox.getId().toString(),
                            amount,
                            name,
                            description,
                            0,
                            ErgoValueBuilder.buildFor(Colls.fromArray(Array[Byte](1.toByte,5.toByte))),
                            ErgoValueBuilder.buildFor(Colls.fromArray(hash)),
                            ErgoValueBuilder.buildFor(Colls.fromArray(image.getBytes(StandardCharsets.UTF_8)))
                        )
                    )
                    .value(1000000L)
                    .contract(address(mintdao, salesdao).toErgoContract())
                    .build()
                } else {
                    ctx.newTxBuilder().outBoxBuilder()
                    .mintToken(Eip4TokenBuilder.buildNftPictureToken(
                        issuerBox.getId().toString(),
                        amount,
                        name,
                        description,
                        0,
                        hash,
                        image
                    ))
                    .value(1000000L)
                    .contract(address(mintdao, salesdao).toErgoContract())
                    .build()
                }

                ctx.newTxBuilder()
                    .addInputs(issuerBox)
                    .addOutputs(newIssuerBox, mintNFTBox)
                    .fee(1000000L)
                    .sendChangeTo(new ErgoTreeContract(issuerBox.getErgoTree(),ctx.getNetworkType()).toAddress())
                    .build()
            }
        })
    }

    def registersMatch(collection: NFTCollection, issuerBox: InputBox) = {
        val correctRegisters = issuerBoxRegisters(collection)
        correctRegisters.indices.forall(i => issuerBox.getRegisters().get(i).toHex().equals(correctRegisters(i).toHex()))
    }

    def issuerBoxRegisters(collection: NFTCollection) = {
        Array[ErgoValue[_]](
            ErgoValueBuilder.buildFor(2),
            ErgoValueBuilder.buildFor(Colls.fromArray(
                getRoyalties.map(roy =>                  
                    (Colls.fromArray(Address.create(roy.address).toPropositionBytes()),roy.royaltyPct)
                    ).toArray)
                ),
            ErgoValueBuilder.buildFor(
                (
                    // properties
                    Colls.fromArray(getProperties(collection).map(p => (Colls.fromArray(p.name.getBytes(StandardCharsets.UTF_8)),Colls.fromArray(p.valueString.getOrElse("").getBytes(StandardCharsets.UTF_8)))).toArray)
                    ,
                    (
                        // levels
                        Colls.fromArray(getLevels(collection).toArray)
                        ,
                        // stats
                        Colls.fromArray(getStats(collection).toArray)
                    )
                )
            ),
            ErgoValueBuilder.buildFor(Colls.fromArray(ErgoId.create(collection.tokenId).getBytes())),
            ErgoValueBuilder.buildFor(Colls.fromArray(
                Array((Colls.fromArray("explicit".getBytes(StandardCharsets.UTF_8)),Colls.fromArray(Array[Byte]((if (explicit) 1.toByte else 0.toByte )))))
            ))
        )
    }

    def getRoyalties: Seq[Royalty] = {
        val royalties: Option[Seq[Royalty]] = 
                Json.fromJson[Seq[Royalty]](royalty).asOpt 
        
        royalties match {
            case None => Array[Royalty]()
            case Some(myRoyalties) => myRoyalties
        }
    }

    def getTraits = {
        val _traits: Option[Seq[Trait]] = 
                Json.fromJson[Seq[Trait]](traits).asOpt 
        
        _traits match {
            case None => Seq[Trait]()
            case Some(myTraits) => myTraits
        }
    }

    def getProperties(collection: NFTCollection) = {
        val availableTraits = collection.getAvailableTraits
        getTraits.filter(_.tpe == TraitType.PROPERTY).filter(t => availableTraits.exists(at => at.name.equals(t.name)))
    }

    def getLevels(collection: NFTCollection) = {
        val availableTraits = collection.getAvailableTraits
        getTraits.filter(_.tpe == TraitType.LEVEL).filter(t => availableTraits.exists(at => at.name.equals(t.name)))
            .map(t => (Colls.fromArray(t.name.getBytes(StandardCharsets.UTF_8)),
                (
                    t.valueInt.getOrElse(0),
                    availableTraits.find(at => at.name.equals(t.name)).get.max.getOrElse(0)
                )))
    }

    def getStats(collection: NFTCollection) = {
        val availableTraits = collection.getAvailableTraits
        getTraits.filter(_.tpe == TraitType.STAT).filter(t => availableTraits.exists(at => at.name.equals(t.name)))
            .map(t => (Colls.fromArray(t.name.getBytes(StandardCharsets.UTF_8)),
                (
                    t.valueInt.getOrElse(0),
                    availableTraits.find(at => at.name.equals(t.name)).get.max.getOrElse(0)
                )))
    }

    def followUp(ergoClient: ErgoClient, mintdao: MintDAO, salesdao: SalesDAO, retries: Int = 10): Unit = {
        if (retries < 0) {
            Await.result(mintdao.updateNFTStatus(id, "", NFTStatus.INITIALIZED, ""), Duration.Inf)
        } else {
            val mempoolTxState = ergoClient.getDataSource().asInstanceOf[NodePoolDataSource].getUnconfirmedTransactionState(mintTransactionId)
            //If the tx is no longer in the mempool we need to ensure it is confirmed and set the state accordingly
            if (mempoolTxState == 404) {
                val mintingAddress = address(mintdao, salesdao)
                val balance = Pratir.balance(ergoClient.getDataSource().asInstanceOf[NodePoolDataSource].getAllUnspentBoxesFor(mintingAddress,false).asScala)
                if (balance._2.contains(tokenId)) {
                    Await.result(mintdao.updateNFTStatus(id, tokenId, NFTStatus.MINTED, mintTransactionId), Duration.Inf)
                    val collection = Await.result(mintdao.getCollection(collectionId), Duration.Inf)
                    collection.saleId match {
                        case None => Unit
                        case Some(saleId) => 
                            Await.result(salesdao.insertTokenForSale(TokenForSale(
                                UUID.randomUUID(),
                                tokenId,
                                amount.toInt,
                                amount.toInt,
                                rarity,
                                saleId
                            )), Duration.Inf)
                            if (rarity.contains("_pt_rarity")) {
                                Await.result(salesdao.injectPackTokenId(s"_pt_${amount.toString()}_${name}", tokenId, saleId), Duration.Inf)
                            }
                    }
                } else {
                    logger.info(s"""Transaction lost, waiting 10 seconds...""")
                    Thread.sleep(10000)
                    followUp(ergoClient, mintdao, salesdao, retries-1)
                }
            }
        }
    }

    def address(mintdao: MintDAO, salesdao: SalesDAO) = {
        val collection = Await.result(mintdao.getCollection(collectionId), Duration.Inf)
        collection.saleId match {
            case None => 
                val collection = Await.result(mintdao.getCollection(collectionId), Duration.Inf)
                val _artist = collection.artist(mintdao)
                Address.create(_artist.address)
            case Some(sid) =>
                val sale = Await.result(salesdao.getSale(sid), Duration.Inf)
                sale.getSaleAddress
        }
    }
}

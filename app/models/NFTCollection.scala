package models

import java.util.UUID
import play.api.libs.json.JsValue
import java.time.Instant
import play.api.libs.json.Reads
import play.api.libs.json.Writes
import slick.jdbc.PostgresProfile.api._
import org.ergoplatform.appkit.ErgoClient
import org.ergoplatform.appkit.UnsignedTransaction
import database.MintDAO
import database.UsersDAO
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import contracts.Mint
import util.NodePoolDataSource
import org.ergoplatform.appkit.BlockchainContext
import org.ergoplatform.appkit.Address
import org.ergoplatform.appkit.ExplorerAndPoolUnspentBoxesLoader
import org.ergoplatform.appkit.BoxOperations
import org.ergoplatform.appkit.impl.ErgoTreeContract
import play.api.libs.json.Json
import org.ergoplatform.appkit.NetworkType
import org.ergoplatform.appkit.scalaapi.ErgoValueBuilder
import java.nio.charset.StandardCharsets
import sigma.Colls
import util.Pratir
import org.ergoplatform.appkit.Eip4Token
import org.ergoplatform.appkit.impl.Eip4TokenBuilder
import scala.collection.JavaConverters._
import play.api.Logging
import database.SalesDAO
import sigma.Coll
import shapeless.TypeCase
import shapeless.Typeable
import org.ergoplatform.appkit.impl.NodeDataSourceImpl
import org.ergoplatform.sdk.ErgoToken

object NFTCollectionStatus extends Enumeration {
  type NFTCollectionStatus = Value
  val INITIALIZED, MINTING, MINTING_NFTS, MINTED, REFUNDING, REFUNDED, FAILED =
    Value

  implicit val readsNFTCollectionStatus =
    Reads.enumNameReads(NFTCollectionStatus)
  implicit val writesNFTCollectionStatus = Writes.enumNameWrites
  implicit val statusMapper =
    MappedColumnType.base[NFTCollectionStatus, String](
      e => e.toString,
      s => NFTCollectionStatus.withName(s)
    )
}

final case class NFTCollection(
    id: UUID,
    artistId: UUID,
    name: String,
    tokenId: String,
    description: String,
    bannerImageUrl: String,
    featuredImageUrl: String,
    collectionLogoUrl: String,
    category: String,
    mintingExpiry: Long, // unix timestamp of last date of expiry. If no expiry, must be -1. May not be undefined
    rarities: JsValue,
    availableTraits: JsValue,
    saleId: Option[UUID],
    status: NFTCollectionStatus.Value,
    mintingTxId: String,
    createdAt: Instant,
    updatedAt: Instant
) extends Logging {

  def artist(usersdao: UsersDAO) = {
    val artistOption =
      Await.result(usersdao.getUserById(artistId), Duration.Inf)
    artistOption.get
  }

  def mintContract(artist: User) =
    new ErgoTreeContract(Mint.contract(artist.address), NetworkType.MAINNET)

  def handleInitialized(
      ergoClient: ErgoClient,
      mintdao: MintDAO,
      usersdao: UsersDAO
  ) = {
    mint(ergoClient, mintdao, usersdao)
  }

  def getAvailableTraits = {
    val _traits: Option[Seq[AvailableTrait]] =
      Json.fromJson[Seq[AvailableTrait]](availableTraits).asOpt

    _traits match {
      case None           => Seq[AvailableTrait]()
      case Some(myTraits) => myTraits
    }
  }

  def mintNFTs(
      ergoClient: ErgoClient,
      mintdao: MintDAO,
      salesdao: SalesDAO,
      usersdao: UsersDAO
  ) = {
    val nftMintsInMempool = Await.result(mintdao.getNFTsMinting, Duration.Inf)

    val mimMax = 30

    val _artist = artist(usersdao)
    val _mintContract = mintContract(_artist)

    val collection = this

    if (nftMintsInMempool.size < mimMax) {
      val nftsToBeMinted = Await.result(
        mintdao.getNFTsInitialized(id, mimMax - nftMintsInMempool.size + 1),
        Duration.Inf
      )
      if (nftsToBeMinted.size == 0) {
        Await.result(
          mintdao.updateCollectionStatus(
            id,
            tokenId,
            NFTCollectionStatus.MINTED,
            mintingTxId
          ),
          Duration.Inf
        )
      } else {
        var issuerBox =
          NodePoolDataSource
            .getAllUnspentBoxesFor(
              _mintContract.toAddress(),
              ergoClient
                .getDataSource()
                .asInstanceOf[NodeDataSourceImpl]
            )
            .asScala
            .filter(ib =>
              if (ib.getTokens().size() > 0) {
                ib.getTokens()
                  .asScala
                  .exists(et => et.getId.toString().equals(tokenId))
              } else {
                false
              }
            )(0)

        ergoClient.execute(
          new java.util.function.Function[BlockchainContext, Unit] {
            override def apply(ctx: BlockchainContext): Unit = {
              nftsToBeMinted.indices
                .take(mimMax)
                .foreach(i => {
                  if (
                    !nftsToBeMinted(i)
                      .registersMatch(collection, issuerBox) || issuerBox
                      .getValue() < (nftsToBeMinted.size + 1 - i) * 2000000L
                  ) {
                    logger.info(
                      "Issuerbox does not match NFT metadata so preparation tx is being generated"
                    )
                    val newIssuerBox = ctx
                      .newTxBuilder()
                      .outBoxBuilder()
                      .contract(_mintContract)
                      .tokens(new ErgoToken(tokenId, 1L))
                      .value((nftsToBeMinted.size + 1 - i) * 2000000L)
                      .registers(
                        nftsToBeMinted(i).issuerBoxRegisters(collection): _*
                      )
                      .build()

                    val boxesLoader = new ExplorerAndPoolUnspentBoxesLoader()
                      .withAllowChainedTx(true)

                    val boxOperations = BoxOperations
                      .createForSender(
                        _mintContract.toAddress(),
                        ctx
                      )
                      .withInputBoxesLoader(boxesLoader)
                      .withMaxInputBoxesToSelect(100)
                      .withFeeAmount(1000000L)
                      .withAmountToSpend(newIssuerBox.getValue())
                      .withTokensToSpend(newIssuerBox.getTokens())

                    val unsignedPrepareTx =
                      boxOperations.buildTxWithDefaultInputs(tb =>
                        tb.addOutputs(newIssuerBox)
                      )

                    val signedPrepareTx = Pratir.sign(ctx, unsignedPrepareTx)
                    ctx.sendTransaction(signedPrepareTx)
                    issuerBox = signedPrepareTx.getOutputsToSpend().get(0)
                  }
                  val unsignedMintTx = nftsToBeMinted(i).mint(
                    issuerBox,
                    ergoClient,
                    mintdao,
                    salesdao,
                    usersdao,
                    if (nftsToBeMinted.length > i + 1)
                      Some(nftsToBeMinted(i + 1))
                    else None
                  )
                  val signedMintTx = Pratir.sign(ctx, unsignedMintTx)
                  ctx.sendTransaction(signedMintTx)
                  Await.result(
                    mintdao.updateNFTStatus(
                      nftsToBeMinted(i).id,
                      issuerBox.getId().toString(),
                      NFTStatus.MINTING,
                      signedMintTx.getId()
                    ),
                    Duration.Inf
                  )
                  issuerBox = signedMintTx.getOutputsToSpend().get(0)
                })
            }
          }
        )
      }
    }
  }

  implicit def collByteIsTypeable: Typeable[Coll[Byte]] =
    new Typeable[Coll[Byte]] {
      private val typByte = Typeable[Byte]

      def cast(t: Any): Option[Coll[Byte]] = {
        if (t == null) None
        else if (
          t.isInstanceOf[Coll[_]] && t
            .asInstanceOf[Coll[_]]
            .forall(e =>
              typByte.cast(e) match {
                case None    => true
                case Some(v) => true
              }
            )
        ) {
          Some(t.asInstanceOf[Coll[Byte]])
        } else None
      }

      def describe: String = s"Coll[${typByte.describe}]"
    }

  val collByte = TypeCase[Coll[Byte]]

  def mint(ergoClient: ErgoClient, mintdao: MintDAO, usersdao: UsersDAO) = {
    val _artist = artist(usersdao)
    val _mintContract = mintContract(_artist)
    val nfts = Await.result(mintdao.getNFTsForCollection(id), Duration.Inf)
    val nanoErgNeeded = 2000000L * (nfts.size + 4) - 1000000L
    ergoClient.execute(
      new java.util.function.Function[BlockchainContext, Unit] {
        override def apply(ctx: BlockchainContext): Unit = {
          val balance = Pratir.balance(
            NodePoolDataSource
              .getAllUnspentBoxesFor(
                _mintContract.toAddress(),
                ctx
                  .getDataSource()
                  .asInstanceOf[NodeDataSourceImpl]
              )
              .asScala
              .filter(ib =>
                if (ib.getRegisters().size() > 0) {
                  ib.getRegisters().get(0).getValue() match {
                    case collByte(cb) =>
                      try {
                        new String(
                          ib.getRegisters()
                            .get(0)
                            .getValue()
                            .asInstanceOf[Coll[Byte]]
                            .toArray,
                          StandardCharsets.UTF_8
                        ).equals(id.toString())
                      } catch {
                        case e: Exception => false
                      }
                    case _ => false
                  }
                } else {
                  false
                }
              )
          )
          if (balance._1 >= nanoErgNeeded + 1000000L) {
            val collectionIssuerBox = ctx
              .newTxBuilder()
              .outBoxBuilder()
              .contract(_mintContract)
              .registers(
                ErgoValueBuilder.buildFor(1),
                // ["URL_TO_COLLECTION_LOGO", "URL_TO_COLLECTION_FEATURED_IMAGE","URL_TO_COLLECTION_BANNER_IMAGE", "COLLECTION_CATEGORY"]
                ErgoValueBuilder.buildFor(
                  Colls.fromArray(
                    Array(
                      Colls.fromArray(
                        collectionLogoUrl.getBytes(StandardCharsets.UTF_8)
                      ),
                      Colls.fromArray(
                        featuredImageUrl.getBytes(StandardCharsets.UTF_8)
                      ),
                      Colls.fromArray(
                        bannerImageUrl.getBytes(StandardCharsets.UTF_8)
                      ),
                      Colls.fromArray(category.getBytes(StandardCharsets.UTF_8))
                    )
                  )
                ),
                ErgoValueBuilder.buildFor(
                  Colls.fromArray(
                    _artist.getSocials
                      .map(social =>
                        (
                          Colls.fromArray(
                            social.socialNetwork
                              .getBytes(StandardCharsets.UTF_8)
                          ),
                          Colls.fromArray(
                            social.url.getBytes(StandardCharsets.UTF_8)
                          )
                        )
                      )
                      .toArray
                  )
                )
              )
              .value(nanoErgNeeded)
              .build()

            val boxesLoader =
              new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)

            val boxOperations = BoxOperations
              .createForSender(_mintContract.toAddress(), ctx)
              .withInputBoxesLoader(boxesLoader)
              .withFeeAmount(1000000L)
              .withAmountToSpend(nanoErgNeeded)

            val createIssuerBoxTx = boxOperations.buildTxWithDefaultInputs(tb =>
              tb.addOutputs(collectionIssuerBox)
            )
            val signedCreateIssuerBoxTx = Pratir.sign(ctx, createIssuerBoxTx)
            val collectionMintBox = ctx
              .newTxBuilder()
              .outBoxBuilder()
              .mintToken(
                new Eip4Token(
                  signedCreateIssuerBoxTx
                    .getOutputsToSpend()
                    .get(0)
                    .getId()
                    .toString(),
                  1L,
                  name,
                  description,
                  0,
                  ErgoValueBuilder.buildFor(
                    Colls.fromArray(Array[Byte](1.toByte, 4.toByte))
                  ),
                  ErgoValueBuilder.buildFor(Colls.fromArray(Array[Byte]())),
                  ErgoValueBuilder.buildFor(Colls.fromArray(Array[Byte]()))
                )
              )
              .value(nanoErgNeeded - 1000000L)
              .contract(_mintContract)
              .build()

            val mintCollectionTokenTx = ctx
              .newTxBuilder()
              .addInputs(signedCreateIssuerBoxTx.getOutputsToSpend().get(0))
              .addOutputs(collectionMintBox)
              .fee(1000000L)
              .sendChangeTo(_mintContract.toAddress())
              .build()

            val signedMintCollectionTokenTx =
              Pratir.sign(ctx, mintCollectionTokenTx)

            ctx.sendTransaction(signedCreateIssuerBoxTx)
            ctx.sendTransaction(signedMintCollectionTokenTx)

            Await.result(
              mintdao.updateCollectionStatus(
                id,
                signedCreateIssuerBoxTx
                  .getOutputsToSpend()
                  .get(0)
                  .getId()
                  .toString(),
                NFTCollectionStatus.MINTING,
                signedMintCollectionTokenTx.getId()
              ),
              Duration.Inf
            )
          }
        }
      }
    )
  }

  def followUp(
      ergoClient: ErgoClient,
      mintdao: MintDAO,
      usersdao: UsersDAO,
      retries: Int = 10
  ): Unit = {
    if (retries < 0) {
      Await.result(
        mintdao
          .updateCollectionStatus(id, "", NFTCollectionStatus.INITIALIZED, ""),
        Duration.Inf
      )
    } else {
      val mempoolTxState =
        NodePoolDataSource.getUnconfirmedTransactionState(
          mintingTxId,
          ergoClient
            .getDataSource()
            .asInstanceOf[NodeDataSourceImpl]
        )
      // If the tx is no longer in the mempool we need to ensure it is confirmed and set the state accordingly
      if (mempoolTxState == 404) {
        val _artist = artist(usersdao)
        val _mintContract = mintContract(_artist)
        val balance = Pratir.balance(
          ergoClient
            .getDataSource()
            .getUnspentBoxesFor(_mintContract.toAddress(), 0, 100)
            .asScala
        )
        if (balance._2.contains(tokenId)) {
          Await.result(
            mintdao.updateCollectionStatus(
              id,
              tokenId,
              NFTCollectionStatus.MINTING_NFTS,
              mintingTxId
            ),
            Duration.Inf
          )
        } else {
          logger.info(s"""Transaction lost, waiting 10 seconds...""")
          Thread.sleep(10000)
          followUp(ergoClient, mintdao, usersdao, retries - 1)
        }
      }
    }
  }

  def mintBootstrap(
      ergoClient: ErgoClient,
      mintdao: MintDAO,
      usersdao: UsersDAO
  ): UnsignedTransaction = {
    val nfts = Await.result(mintdao.getNFTsForCollection(id), Duration.Inf)
    val nanoErgNeeded = 2000000L * (nfts.size + 4)
    val _artist = artist(usersdao)
    val _mintContract = mintContract(_artist)
    ergoClient.execute(
      new java.util.function.Function[BlockchainContext, UnsignedTransaction] {
        override def apply(ctx: BlockchainContext): UnsignedTransaction = {

          val outBox =
            ctx
              .newTxBuilder()
              .outBoxBuilder()
              .value(nanoErgNeeded)
              .registers(
                ErgoValueBuilder.buildFor(
                  Colls.fromArray(
                    id.toString().getBytes(StandardCharsets.UTF_8)
                  )
                )
              )
              .contract(_mintContract)
              .build()

          val boxesLoader =
            new ExplorerAndPoolUnspentBoxesLoader().withAllowChainedTx(true)

          val boxOperations = BoxOperations
            .createForSender(Address.create(_artist.address), ctx)
            .withInputBoxesLoader(boxesLoader)
            .withFeeAmount(1000000L)
            .withAmountToSpend(nanoErgNeeded)

          boxOperations.buildTxWithDefaultInputs(tb => tb.addOutputs(outBox))
        }
      }
    )
  }
}

object NFTCollection {
  implicit val json = Json.format[NFTCollection]
}

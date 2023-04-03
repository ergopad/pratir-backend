package controllers

import database.SalesDAO
import database.MintDAO

import java.util.UUID
import java.time.Instant
import javax.inject._

import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughTokensException
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughErgsException
import org.ergoplatform.appkit.InputBoxesSelectionException.NotEnoughCoinsForChangeException
import org.ergoplatform.appkit.NetworkType

import play.api.db.slick.DatabaseConfigProvider
import play.api.mvc.ControllerComponents
import play.api.libs.json.JsError
import play.api.libs.json.JsSuccess
import play.api.mvc.BaseController
import play.api.db.slick.HasDatabaseConfigProvider
import play.api.mvc
import play.api.libs.json.Json

import scala.concurrent.ExecutionContext
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import slick.jdbc.JdbcProfile

import util.RestApiErgoClientWithNodePoolDataSource
import util.NFTStorageClient

import models._

@Singleton
class MintController @Inject() (
    protected val dbConfigProvider: DatabaseConfigProvider,
    val controllerComponents: ControllerComponents,
    val nftStorageClient: NFTStorageClient,
)(implicit ec: ExecutionContext)
    extends BaseController
    with HasDatabaseConfigProvider[JdbcProfile] {
    implicit val nftCollectionJson = Json.format[NFTCollection]
    implicit val artistJson = Json.format[Artist]
    implicit val nftJson = Json.format[NFT]
    implicit val fileUploadResponseJson = Json.format[FileUploadResponse]

    def getAllCollections(): mvc.Action[mvc.AnyContent] = Action.async { implicit request =>
        val mintdao = new MintDAO(dbConfigProvider)
        mintdao.getAllCollections.map(collection => Ok(Json.toJson(collection)))
    }

    def createArtist() = Action { implicit request =>
        try {
            val content = request.body
            val jsonObject = content.asJson
            val mintdao = new MintDAO(dbConfigProvider)
            Json.fromJson[NewArtist](jsonObject.get) match {
                case je: JsError => BadRequest(JsError.toJson(je))
                case js: JsSuccess[NewArtist] =>
                    val newArtist = js.value
                    val artistId = UUID.randomUUID()
                    val artistAdded = Artist(
                      artistId,
                      newArtist.address,
                      newArtist.name,
                      newArtist.website,
                      newArtist.tagline,
                      newArtist.avatarUrl,
                      newArtist.bannerUrl,
                      Json.toJson(newArtist.social),
                      Instant.now(),
                      Instant.now()
                    )
                    Await.result(mintdao.insertArtist(artistAdded), Duration.Inf)
                    Created(Json.toJson(artistAdded))
            }
        } catch {
            case e: Exception => BadRequest(Json.toJson(e.getMessage()))
        }
    }

    def mintCollection(_collectionId: String) = Action { implicit request =>
        val collectionId = UUID.fromString(_collectionId)
        val mintdao = new MintDAO(dbConfigProvider)
        val collection = Await.result(mintdao.getCollection(collectionId), Duration.Inf)
        val ergoClient = RestApiErgoClientWithNodePoolDataSource.create(
          sys.env.get("ERGO_NODE").get,
          NetworkType.MAINNET,
          "",
          sys.env.get("ERGO_EXPLORER").get
        )
        try {
            Ok(Json.toJson(MUnsignedTransaction(collection.mintBootstrap(ergoClient, mintdao))))
        } catch {
            case nete: NotEnoughTokensException =>
                BadRequest("The wallet did not contain the tokens required for bootstrapping")
            case neee: NotEnoughErgsException =>
                BadRequest("Not enough erg in wallet for bootstrapping")
            case necfc: NotEnoughCoinsForChangeException =>
                BadRequest(
                  "Not enough erg for change box, try consolidating your utxos to remove this error"
                )
            case e: Exception => BadRequest(e.getMessage())
        }
    }

    def createCollection() = Action { implicit request =>
        try {
            val content = request.body
            val jsonObject = content.asJson
            val mintdao = new MintDAO(dbConfigProvider)
            val salesdao = new SalesDAO(dbConfigProvider)
            Json.fromJson[NewNFTCollection](jsonObject.get) match {
                case je: JsError => BadRequest(JsError.toJson(je))
                case js: JsSuccess[NewNFTCollection] =>
                    val collectionAdded = mintdao.insertCollection(js.value)
                    js.value.saleId match {
                        case Some(saleId) =>
                            mintdao.insertNFTs(
                              Await
                                  .result(salesdao.getSale(saleId), Duration.Inf)
                                  .packTokensToMint(salesdao, collectionAdded.id)
                            )
                        case None => None
                    }
                    Created(Json.toJson(collectionAdded))
            }
        } catch {
            case e: Exception => BadRequest(Json.toJson(e.getMessage()))
        }
    }

    def createNFTs() = Action { implicit request =>
        val content = request.body
        val jsonObject = content.asJson
        val mintdao = new MintDAO(dbConfigProvider)

        Json.fromJson[Seq[NewNFT]](jsonObject.get) match {
            case je: JsError => BadRequest(JsError.toJson(je))
            case js: JsSuccess[Seq[NewNFT]] =>
                val nftsAdded = mintdao.insertNewNFTs(js.value)
                Created(Json.toJson(nftsAdded))
        }
    }

    def uploadFile = Action(parse.multipartFormData) { request =>
        try {
            request.body
                .file("fileobject")
                .map { fileobject =>
                    // upload time
                    val ipfsUrl = nftStorageClient.upload(fileobject.ref)
                    Ok(Json.toJson(FileUploadResponse("ok", "File Uploaded", Some(ipfsUrl))))
                }
                .getOrElse {
                    NotFound("File Not Found")
                }
        } catch {
            case e: Exception => BadRequest(e.getMessage)
        }
    }
}

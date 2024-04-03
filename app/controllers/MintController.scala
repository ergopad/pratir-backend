package controllers

import database.SalesDAO
import database.MintDAO
import database.UsersDAO

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

import util.Pratir
import util.NFTStorageClient

import models._
import org.ergoplatform.appkit.RestApiErgoClient
import play.api.Logging

@Singleton
class MintController @Inject() (
    val mintdao: MintDAO,
    val salesdao: SalesDAO,
    val usersdao: UsersDAO,
    val nftStorageClient: NFTStorageClient,
    val controllerComponents: ControllerComponents,
    protected val dbConfigProvider: DatabaseConfigProvider
)(implicit ec: ExecutionContext)
    extends BaseController
    with HasDatabaseConfigProvider[JdbcProfile]
    with Logging {

  def getAllCollections(): mvc.Action[mvc.AnyContent] = Action.async {
    implicit request =>
      mintdao.getAllCollections.map(collection => Ok(Json.toJson(collection)))
  }

  def getCollection(collectionId: String): mvc.Action[mvc.AnyContent] =
    Action { implicit request =>
      try {
        val uuid =
          if (Pratir.isValidUUID(collectionId)) UUID.fromString(collectionId)
          else
            mintdao
              .getCollectionIdBySlug(collectionId)
              .getOrElse(UUID.randomUUID)
        val collection = Await.result(mintdao.getCollection(uuid), Duration.Inf)
        Ok(Json.toJson(collection))
      } catch {
        case e: Exception => {
          logger.error("Caught unexpected error", e);
          InternalServerError(
            Json.toJson(
              ErrorResponse(
                500,
                e.getClass().getCanonicalName(),
                e.getMessage()
              )
            )
          )
        }
      }
    }

  def getAllCollectionsFiltered(
      address: Option[String]
  ): mvc.Action[mvc.AnyContent] = Action.async { implicit request =>
    mintdao
      .getAllCollectionsFiltered(address)
      .map(collection => Ok(Json.toJson(collection)))
  }

  def mintCollection(_collectionId: String) = Action { implicit request =>
    try {
      val collectionId = UUID.fromString(_collectionId)
      val collection =
        Await.result(mintdao.getCollection(collectionId), Duration.Inf)
      val ergoClient = RestApiErgoClient.create(
        sys.env.get("ERGO_NODE").get,
        NetworkType.MAINNET,
        "",
        sys.env.get("ERGO_EXPLORER").get
      )

      Ok(
        Json.toJson(
          MUnsignedTransaction(
            collection.mintBootstrap(ergoClient, mintdao, usersdao)
          )
        )
      )
    } catch {
      case nete: NotEnoughTokensException =>
        BadRequest(
          Json.toJson(
            ErrorResponse(
              400,
              "NotEnoughTokensException",
              "The wallet did not contain the tokens required for minting"
            )
          )
        )
      case neee: NotEnoughErgsException =>
        BadRequest(
          Json.toJson(
            ErrorResponse(
              400,
              "NotEnoughErgsException",
              "Not enough erg in wallet for minting"
            )
          )
        )
      case necfc: NotEnoughCoinsForChangeException =>
        BadRequest(
          Json.toJson(
            ErrorResponse(
              400,
              "NotEnoughCoinsForChangeException",
              "Not enough erg for change box, try consolidating your utxos to remove minting"
            )
          )
        )
      case e: Exception => {
        logger.error("Caught unexpected error", e);
        InternalServerError(
          Json.toJson(
            ErrorResponse(
              500,
              e.getClass().getCanonicalName(),
              e.getMessage()
            )
          )
        )
      }
    }
  }

  def createCollection() = Action { implicit request =>
    try {
      val content = request.body
      val jsonObject = content.asJson
      Json.fromJson[NewNFTCollection](jsonObject.get) match {
        case je: JsError => BadRequest(JsError.toJson(je))
        case js: JsSuccess[NewNFTCollection] =>
          val collectionAdded = mintdao.insertCollection(js.value)
          js.value.saleId match {
            case Some(saleId) => {
              val packCollectionAdded = mintdao.insertCollection(
                js.value.copy(name = js.value + " Packs")
              )
              mintdao.insertNFTs(
                Await
                  .result(salesdao.getSale(saleId), Duration.Inf)
                  ._1
                  ._1
                  .packTokensToMint(salesdao, packCollectionAdded.id)
              )
              Created(Json.toJson(Seq(collectionAdded, packCollectionAdded)))
            }
            case None => Created(Json.toJson(Seq(collectionAdded)))
          }
      }
    } catch {
      case e: Exception => {
        logger.error("Caught unexpected error", e);
        InternalServerError(
          Json.toJson(
            ErrorResponse(
              500,
              e.getClass().getCanonicalName(),
              e.getMessage()
            )
          )
        )
      }
    }
  }

  def createNFTs() = Action { implicit request =>
    try {
      val content = request.body
      val jsonObject = content.asJson

      Json.fromJson[Seq[NewNFT]](jsonObject.get) match {
        case je: JsError => BadRequest(JsError.toJson(je))
        case js: JsSuccess[Seq[NewNFT]] =>
          val nftsAdded = mintdao.insertNewNFTs(js.value)
          Created(Json.toJson(nftsAdded))
      }
    } catch {
      case e: Exception => {
        logger.error("Caught unexpected error", e);
        InternalServerError(
          Json.toJson(
            ErrorResponse(
              500,
              e.getClass().getCanonicalName(),
              e.getMessage()
            )
          )
        )
      }
    }
  }

  def uploadFile = Action(parse.multipartFormData) { request =>
    try {
      request.body
        .file("fileobject")
        .map { fileobject =>
          // upload time
          val ipfsUrl = nftStorageClient.upload(fileobject.ref)
          Ok(
            Json.toJson(
              FileUploadResponse("ok", "File Uploaded", Some(ipfsUrl))
            )
          )
        }
        .getOrElse {
          NotFound("File Not Found")
        }
    } catch {
      case e: Exception => {
        logger.error("Caught unexpected error", e);
        InternalServerError(
          Json.toJson(
            ErrorResponse(
              500,
              e.getClass().getCanonicalName(),
              e.getMessage()
            )
          )
        )
      }
    }
  }
}

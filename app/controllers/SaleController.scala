package controllers;

import play.api.mvc._
import javax.inject._
import slick.lifted.TableQuery
import database._
import play.api.libs.json._
import play.api.db.slick._
import slick.jdbc.PostgresProfile.api._
import slick.jdbc.JdbcProfile
import scala.concurrent.ExecutionContext
import models._
import java.util.UUID
import scala.concurrent.duration.Duration
import scala.concurrent.Await
import _root_.util.Pratir

@Singleton
class SaleController @Inject()(protected val dbConfigProvider: DatabaseConfigProvider, val controllerComponents: ControllerComponents)(implicit ec: ExecutionContext)
extends BaseController
 with HasDatabaseConfigProvider[JdbcProfile] {
    implicit val saleJson = Json.format[Sale]
    implicit val newTokenForSaleJson = Json.format[NewTokenForSale]
    implicit val newPriceJson = Json.format[NewPrice]
    implicit val newPackEntryJson = Json.format[NewPackEntry]
    implicit val newPackJson = Json.format[NewPack]
    implicit val newSaleJson = Json.format[NewSale]
    implicit val priceJson = Json.format[Price]
    implicit val packEntryJson = Json.format[PackEntry]
    implicit val packFullJson = Json.format[PackFull]
    implicit val tokenForSaleJson = Json.format[TokenForSale]
    implicit val saleFullJson = Json.format[SaleFull]

    def getAll(): Action[AnyContent] = Action.async { implicit request =>
        val salesdao = new SalesDAO(dbConfigProvider)
        salesdao.getAll.map(sale => Ok(Json.toJson(sale)))
    }

    def getSale(_saleId: String) = Action {
        implicit request =>
        val saleId = UUID.fromString(_saleId)
        val salesdao = new SalesDAO(dbConfigProvider)
        val sale = Await.result(salesdao.getSale(saleId), Duration.Inf)
        val tokens = Await.result(salesdao.getTokensForSale(saleId), Duration.Inf)
        val packs = Await.result(salesdao.getPacks(saleId), Duration.Inf).map(
            p => {
                val price = Await.result(salesdao.getPrice(p.id), Duration.Inf)
                val content = Await.result(salesdao.getPackEntries(p.id), Duration.Inf)
                PackFull(p.id, p.name, price.toArray, content.toArray)
            }
        )
        Ok(Json.toJson(SaleFull(sale.id, sale.name, sale.description, sale.startTime, sale.endTime, sale.sellerWallet, Pratir.getSaleAddress(sale).toString(), packs.toArray, tokens.toArray)))
    }

    def createSale() = Action { implicit request =>
        val content = request.body
        val jsonObject = content.asJson
        val sale: Option[NewSale] = 
            jsonObject.flatMap( 
                Json.fromJson[NewSale](_).asOpt 
            ) 
        
        sale match {
            case None => BadRequest
            case Some(newSale) =>
                val saleId = UUID.randomUUID()
                val saleAdded = Sale(saleId, newSale.name, newSale.description, newSale.startTime, newSale.endTime, newSale.sellerWallet, SaleStatus.PENDING)
                val tokensAdded = newSale.tokens.map((token: NewTokenForSale) =>
                    TokenForSale(UUID.randomUUID(),token.tokenId,token.amount,token.rarity,token.category,saleId)
                )
                val packsDBIO = DBIO.seq(newSale.packs.flatMap((pack: NewPack) => {
                    val packId = UUID.randomUUID()
                    Seq[DBIOAction[Any,slick.dbio.NoStream,?]](
                        Packs.packs += Pack(packId, pack.name, saleId),
                        PackEntries.packEntries ++=
                            pack.content.map(entry =>
                                PackEntry(UUID.randomUUID(), entry.category, entry.amount, packId)
                            ),
                        Prices.prices ++=
                            pack.price.map(p =>
                                Price(UUID.randomUUID(), p.tokenId, p.amount, packId)
                            )
                    )
                }):_*)
                Await.result(db.run(DBIO.seq(
                    Sales.sales += saleAdded,
                    TokensForSale.tokensForSale ++= tokensAdded,
                    packsDBIO
                )),Duration.Inf)
                Created(Json.toJson(saleAdded))
        }
    }
}

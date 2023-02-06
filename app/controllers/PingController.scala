package controllers;

import javax.inject._

import play.api.mvc._
import play.api.libs.json._

import models._
import database._

import scala.concurrent.ExecutionContext


@Singleton
class PingController @Inject()(val controllerComponents: ControllerComponents)(implicit ec: ExecutionContext)
extends BaseController {
    implicit val pingResponseJson = Json.format[PingResponse]

    def ping(): Action[AnyContent] = Action { implicit request =>
        Ok(Json.toJson(PingResponse("ok", "Hello World!")))
    }
}

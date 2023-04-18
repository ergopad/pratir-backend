package controllers

import javax.inject._

import play.api.mvc._
import play.api.libs.json.Json

import models._
import database._

import scala.concurrent.ExecutionContext

@Singleton
class PingController @Inject() (val controllerComponents: ControllerComponents)(
    implicit ec: ExecutionContext
) extends BaseController {

  def ping(): Action[AnyContent] = Action { implicit request =>
    Ok(Json.toJson(PingResponse(status = "ok", message = "Hello World!")))
  }
}

package controllers;

import java.util.UUID

import javax.inject._

import play.api.libs.json._
import play.api.mvc._
import play.api.db.slick._

import models._
import database._

import scala.concurrent.ExecutionContext
import scala.concurrent.Await
import scala.concurrent.duration.Duration

import slick.jdbc.PostgresProfile.api._
import slick.jdbc.JdbcProfile


@Singleton
class UserController @Inject()(protected val dbConfigProvider: DatabaseConfigProvider, val controllerComponents: ControllerComponents)(implicit ec: ExecutionContext)
extends BaseController with HasDatabaseConfigProvider[JdbcProfile] {
    implicit val userJson = Json.format[User]
    implicit val newUserJson = Json.format[NewUser]

    def getAll(): Action[AnyContent] = Action.async { implicit request =>
        val usersDao = new UsersDAO(dbConfigProvider)
        usersDao.getAll.map(user => Ok(Json.toJson(user)))
    }

    def getUser(address: String) = Action { implicit request =>
        val usersDao = new UsersDAO(dbConfigProvider)
        val user = Await.result(usersDao.getUser(address), Duration.Inf)
        Ok(Json.toJson(user.getOrElse(User(UUID.randomUUID, address, address, "", ""))))
    }
}

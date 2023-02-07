package controllers

import java.util.UUID

import javax.inject._

import play.api.Logging
import play.api.libs.json._
import play.api.mvc._

import models._
import database._

import scala.concurrent.ExecutionContext
import scala.concurrent.Await
import scala.concurrent.duration.Duration

@Singleton
class UserController @Inject() (
    val usersDao: UsersDAO,
    val controllerComponents: ControllerComponents
)(implicit ec: ExecutionContext)
    extends BaseController
    with Logging {
    implicit val userJson = Json.format[User]
    implicit val newUserJson = Json.format[UpdateUser]

    def getAll(): Action[AnyContent] = Action.async { implicit request =>
        usersDao.getAll.map(user => Ok(Json.toJson(user)))
    }

    def getUser(address: String) = Action { implicit request =>
        val user = Await.result(usersDao.getUser(address), Duration.Inf)
        val mockId = UUID.randomUUID
        Ok(Json.toJson(user.getOrElse(User(mockId, address, address, "", ""))))
    }

    def updateUser() = Action { implicit request =>
        val content = request.body
        val jsonObject = content.asJson
        val req: Option[UpdateUser] = jsonObject.flatMap(Json.fromJson[UpdateUser](_).asOpt)

        req match {
            case None => BadRequest
            case Some(user) =>
                val uuid = UUID.randomUUID
                val userUpdate =
                    User(uuid, user.address, user.name, user.pfpUrl, user.tagline)
                // check if existing user
                val checkUser = Await.result(usersDao.getUser(user.address), Duration.Inf)
                if (checkUser.isDefined)
                    Await.result(usersDao.updateUser(userUpdate), Duration.Inf)
                else
                    Await.result(usersDao.createUser(userUpdate), Duration.Inf)
                // return updated user
                val res = Await.result(usersDao.getUser(user.address), Duration.Inf)
                Ok(Json.toJson(res.get))
        }
    }
}

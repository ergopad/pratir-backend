package database

import java.util.UUID

import javax.inject.Inject
import javax.inject.Singleton

import play.api.db.slick.DatabaseConfigProvider
import play.api.db.slick.HasDatabaseConfigProvider
import play.api.Logging
import play.api.libs.json.JsValue

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import slick.jdbc.JdbcProfile
import database.JsonPostgresProfile.api._

import models._

@Singleton
class UsersDAO @Inject() (protected val dbConfigProvider: DatabaseConfigProvider)(
    implicit ec: ExecutionContext
) extends HasDatabaseConfigProvider[JdbcProfile]
    with Logging {

    def getAll: Future[Seq[User]] = {
        val query = Users.users.result
        db.run(query)
    }

    def getUser(address: String): Future[Option[User]] = {
        val query = Users.users.filter(_.address === address).result.headOption
        db.run(query)
    }

    def createUser(user: User): Future[Any] = {
        val query = Users.users += user
        db.run(query)
    }

    def updateUser(user: User): Future[Any] = {
        val query =
            for (dbUser <- Users.users if dbUser.address === user.address)
                yield (dbUser.name, dbUser.pfpUrl, dbUser.bannerUrl, dbUser.tagline, dbUser.website, dbUser.socials)
        db.run(query.update(user.name, user.pfpUrl, user.bannerUrl, user.tagline, user.website, user.socials)) map { _ > 0 }
    }

    def getAuthRequest(id: UUID): Future[Option[AuthRequest]] = {
        val query = AuthRequests.authRequests.filter(_.id === id).result.headOption
        db.run(query)
    }

    def getAuthRequestByToken(token: String): Future[Option[AuthRequest]] = {
        val query = AuthRequests.authRequests.filter(_.verificationToken === token).result.headOption
        db.run(query)
    }

    def createAuthRequest(authRequest: AuthRequest): Future[Any] = {
        val query = AuthRequests.authRequests += authRequest
        db.run(query)
    }

    def setAuthVerificationToken(id: UUID, token: String): Future[Any] = {
        val query =
            for (dbAuthRequest <- AuthRequests.authRequests if dbAuthRequest.id === id)
                yield (dbAuthRequest.verificationToken)
        db.run(query.update(Some(token))) map { _ > 0 }
    }

    def deleteAuthRequest(id: UUID): Future[Any] = {
        val query = AuthRequests.authRequests.filter(_.id === id)
        db.run(query.delete)
    }
}

object Users {
    class Users(tag: Tag) extends Table[User](tag, "users") {
        def id = column[UUID]("id", O.PrimaryKey)
        def address = column[String]("address")
        def name = column[String]("name")
        def pfpUrl = column[String]("pfp_url")
        def bannerUrl = column[String]("banner_url")
        def tagline = column[String]("tagline")
        def website = column[String]("website")
        def socials = column[JsValue]("socials")
        def * = (id, address, name, pfpUrl, bannerUrl, tagline, website, socials) <> (User.tupled, User.unapply)
        def addressIndex = index("users_address_index", (address), unique = true)
    }

    val users = TableQuery[Users]
}

object AuthRequests {
    class AuthRequests(tag: Tag) extends Table[AuthRequest](tag, "auth_requests") {
        def id = column[UUID]("id", O.PrimaryKey)
        def address = column[String]("address")
        def signingMessage = column[String]("signing_message")
        def verificationToken = column[Option[String]]("verification_token")
        def * = (id, address, signingMessage, verificationToken) <> (AuthRequest.tupled, AuthRequest.unapply)
    }

    val authRequests = TableQuery[AuthRequests]
}

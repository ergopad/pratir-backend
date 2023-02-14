package database

import java.util.UUID

import javax.inject.Inject
import javax.inject.Singleton

import play.api.db.slick.DatabaseConfigProvider
import play.api.db.slick.HasDatabaseConfigProvider
import play.api.Logging

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import slick.jdbc.JdbcProfile
import slick.jdbc.PostgresProfile.api._

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
                yield (dbUser.name, dbUser.pfpUrl, dbUser.tagline)
        db.run(query.update(user.name, user.pfpUrl, user.tagline)) map { _ > 0 }
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
    class Users(tag: Tag) extends Table[User](tag, "USERS") {
        def id = column[UUID]("ID", O.PrimaryKey)
        def address = column[String]("ADDRESS")
        def name = column[String]("NAME")
        def pfpUrl = column[String]("PFP_URL")
        def tagline = column[String]("TAGLINE")
        def * = (id, address, name, pfpUrl, tagline) <> (User.tupled, User.unapply)
        def addressIndex = index("USERS_ADDRESS_INDEX", (address), unique = true)
    }

    val users = TableQuery[Users]
}

object AuthRequests {
    class AuthRequests(tag: Tag) extends Table[AuthRequest](tag, "AUTH_REQUESTS") {
        def id = column[UUID]("ID", O.PrimaryKey)
        def address = column[String]("ADDRESS")
        def signingMessage = column[String]("SIGNING_MESSGAGE")
        def verificationToken = column[Option[String]]("VERIFICATION_TOKEN")
        def * = (id, address, signingMessage, verificationToken) <> (AuthRequest.tupled, AuthRequest.unapply)
    }

    val authRequests = TableQuery[AuthRequests]
}

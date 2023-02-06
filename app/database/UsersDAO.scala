package database

import java.time._
import java.util.UUID

import javax.inject._

import play.api.db.slick.DatabaseConfigProvider
import play.api.db.slick.HasDatabaseConfigProvider
import play.api.Logging

import scala.concurrent.ExecutionContext
import scala.concurrent.Future

import slick.jdbc.JdbcProfile
import slick.jdbc.PostgresProfile.api._

import models._


@Singleton
class UsersDAO @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile] with Logging {

    def getAll: Future[Seq[User]] = {
        val query = Users.users.result
        db.run(query)
    }

    def getUser(address: String): Future[Option[User]] = {
        val query = Users.users.filter(_.address === address).result.headOption
        db.run(query)
    }

    // def newTokenOrder(id: UUID, userAddress: String, saleId: UUID, packId: UUID): Future[Any] = {
    //     logger.info(s"""Registering new token order for ${saleId.toString()}""")
    //     db.run(DBIO.seq(TokenOrders.tokenOrders += TokenOrder(id, userAddress, saleId, packId, "", "", TokenOrderStatus.INITIALIZED, Instant.now(), Instant.now())))
    // }
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

package tasks

import javax.inject.Inject
import javax.inject.Named

import akka.actor.ActorRef
import akka.actor.ActorSystem

import scala.concurrent.ExecutionContext
import scala.concurrent.duration._
import database._
import slick.jdbc.JdbcProfile
import play.api.db.slick._
import scala.concurrent.ExecutionContext.Implicits.global
import slick.basic.DatabasePublisher
import slick.jdbc.PostgresProfile.api._
import scala.concurrent.Await

class StartupTask @Inject() (protected val dbConfigProvider: DatabaseConfigProvider, actorSystem: ActorSystem)
extends  HasDatabaseConfigProvider[JdbcProfile]{
  actorSystem.scheduler.scheduleOnce(delay = 5.seconds)(
    try {
      println("Running startup")
      val schema = 
        TableQuery[Sales.Sales].schema ++
        TableQuery[HighlightedSales.HighlightedSales].schema ++
        TableQuery[TokensForSale.TokensForSale].schema ++
        TableQuery[Packs.Packs].schema ++
        TableQuery[Prices.Prices].schema ++
        TableQuery[PackEntries.PackEntries].schema ++
        TableQuery[TokenOrders.TokenOrders].schema ++
        TableQuery[Users.Users].schema ++
        TableQuery[AuthRequests.AuthRequests].schema ++
        TableQuery[Collections.Collections].schema ++
        TableQuery[NFTs.NFTs].schema
      // the block of code that will be executed
      Await.result(db.run(DBIO.seq(
          //schema.dropIfExists,
          schema.create
      )), Duration.Inf)
      println("Startup done")
    } catch {
      case e: Exception => println(e)
    }
  )
}

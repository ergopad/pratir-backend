package database

import javax.inject._
import play.api.db.slick.DatabaseConfigProvider
import scala.concurrent.ExecutionContext
import play.api.db.slick.HasDatabaseConfigProvider
import slick.jdbc.JdbcProfile
import play.api.Logging
import java.util.UUID
import play.api.libs.json.JsValue
import models.NFTCollection
import java.time.Instant
import database.JsonPostgresProfile.api._
import models.NFTCollectionStatus
import models.Artist
import models.NFTStatus
import models.NFT
import scala.concurrent.Future
import models.NewNFTCollection
import scala.concurrent.Await
import scala.concurrent.duration.Duration
import models.NewNFT

@Singleton
class MintDAO @Inject()(protected val dbConfigProvider: DatabaseConfigProvider)(implicit ec: ExecutionContext)
  extends HasDatabaseConfigProvider[JdbcProfile] with Logging {
    import profile.api._

    def getArtist(artistId: UUID) = {
        val query = Artists.artists.filter(_.id === artistId).result.head
        db.run(query)
    }

    def insertArtist(artist: Artist) = {
        db.run(DBIO.seq(Artists.artists += artist))
    }

    def getAllCollections : Future[Seq[NFTCollection]] = {
        val query = Collections.collections.sortBy(_.createdAt.desc).result
        db.run(query)        
    }

    def getCollection(collectionId: UUID): Future[NFTCollection] = {
        val query = Collections.collections.filter(_.id === collectionId).result.head
        db.run(query) 
    }

    def getUnmintedCollections() = {
        val query = Collections.collections.filter(_.status in (NFTCollectionStatus.INITIALIZED, NFTCollectionStatus.MINTING, NFTCollectionStatus.MINTING_NFTS)).sortBy(_.createdAt).result
        db.run(query)
    }

    def insertCollection(newCollection: NewNFTCollection) = {
        val collectionAdded = NFTCollection(
            UUID.randomUUID(),
            newCollection.artistId,
            newCollection.name,
            "",
            newCollection.description,
            newCollection.bannerImageUrl,
            newCollection.featuredImageUrl,
            newCollection.collectionLogoUrl,
            newCollection.category,
            newCollection.mintingExpiry,
            newCollection.rarities,
            newCollection.availableTraits,
            newCollection.saleId,
            NFTCollectionStatus.INITIALIZED,
            "",
            Instant.now(),
            Instant.now())
        Await.result(db.run(DBIO.seq(Collections.collections += collectionAdded)), Duration.Inf)
        collectionAdded
    }

    def updateCollectionStatus(collectionId: UUID, tokenId: String, status: NFTCollectionStatus.Value, mintTxId: String) = {
        logger.info(s"""Setting status for collection ${collectionId.toString()} to $status""")
        val query = Collections.collections.filter(_.id === collectionId).map(collection => (collection.tokenId, collection.status, collection.mintingTxId, collection.updatedAt)).update((tokenId, status, mintTxId, Instant.now()))
        db.run(query)
    }

    def getNFTsForCollection(collectionId: UUID): Future[Seq[NFT]] = {
        val query = NFTs.nfts.filter(_.collectionId === collectionId).result
        db.run(query)
    }

    def getNFTsMinting = {
        val query = NFTs.nfts.filter(_.status === NFTStatus.MINTING).result
        db.run(query)
    }

    def getNFTsInitialized(collectionId: UUID, take: Int) = {
        val query = NFTs.nfts.filter(nft => nft.status === NFTStatus.INITIALIZED && nft.collectionId === collectionId).sortBy(nft => (nft.createdAt,nft.id)).take(take).result
        db.run(query)
    }

    def updateNFTStatus(nftId: UUID, tokenId: String, status: NFTStatus.Value, mintTxId: String) = {
        logger.info(s"""Setting status for NFT ${nftId.toString()} to $status""")
        val query = NFTs.nfts.filter(_.id === nftId).map(nft => (nft.tokenId, nft.status, nft.mintTransactionId, nft.updatedAt)).update((tokenId, status, mintTxId, Instant.now()))
        db.run(query)
    }

    def insertNFTs(newNFTs: Seq[NewNFT]) = {
        val nftsAdded = newNFTs.map(newNFT =>
            NFT(
                UUID.randomUUID(),
                newNFT.collectionId,
                "",
                newNFT.amount,
                newNFT.name,
                newNFT.image,
                newNFT.description,
                newNFT.traits,
                newNFT.rarity,
                newNFT.explicit,
                NFTStatus.INITIALIZED,
                "",
                newNFT.royalty,
                Instant.now(),
                Instant.now()
            )
        )
        Await.result(db.run(DBIO.seq(NFTs.nfts ++= nftsAdded)), Duration.Inf)
        nftsAdded
    }
}

object Collections {
    class Collections(tag: Tag) extends Table[NFTCollection](tag, "collections") {
        def id = column[UUID]("id", O.PrimaryKey)
        def artistId = column[UUID]("artist_id")
        def name = column[String]("name")
        def tokenId = column[String]("token_id", O.Length(64))
        def description = column[String]("description")
        def bannerImageUrl = column[String]("banner_image_url")
        def featuredImageUrl = column[String]("featured_image_url")
        def collectionLogoUrl = column[String]("collection_logo_url")
        def category = column[String]("category")
        def mintingExpiry = column[Long]("minting_expiry")
        def rarities = column[JsValue]("rarities")
        def availableTraits = column[JsValue]("available_traits")
        def saleId = column[Option[UUID]]("sale_id")
        def status = column[NFTCollectionStatus.Value]("status")
        def mintingTxId = column[String]("minting_tx_id", O.Length(64))
        def createdAt = column[Instant]("created_at")
        def updatedAt = column[Instant]("updated_at")
        def artist = foreignKey("collections__artist_id_fk", artistId, Artists.artists)(_.id, onDelete=ForeignKeyAction.Cascade)
        def sale = foreignKey("collections__sale_id_fk", saleId, Sales.sales)(_.id?, onDelete=ForeignKeyAction.SetNull)
        def * = (id, artistId, name, tokenId, description, bannerImageUrl, featuredImageUrl, collectionLogoUrl, category, mintingExpiry, rarities, availableTraits, saleId,  status, mintingTxId, createdAt, updatedAt) <> (NFTCollection.tupled, NFTCollection.unapply)
    }

    val collections = TableQuery[Collections]
}

object Artists {
    class Artists(tag: Tag) extends Table[Artist](tag, "artists") {
        def id = column[UUID]("id", O.PrimaryKey)
        def address= column[String]("address")
        def name = column[String]("name")
        def website = column[String]("website")
        def tagline = column[String]("tagline")
        def avatarUrl = column[String]("avatar_url")
        def bannerUrl = column[String]("banner_url")
        def social = column[JsValue]("social")
        def createdAt = column[Instant]("created_at")
        def updatedAt = column[Instant]("updated_at")
        def * = (id, address, name, website, tagline, avatarUrl, bannerUrl, social, createdAt, updatedAt) <> (Artist.tupled, Artist.unapply)
    }

    val artists = TableQuery[Artists]
}

object NFTs {
    class NFTs(tag: Tag) extends Table[NFT](tag, "nfts") {
        def id = column[UUID]("id", O.PrimaryKey)
        def collectionId = column[UUID]("collection_id")
        def tokenId = column[String]("token_id")
        def amount = column[Long]("amount")
        def name = column[String]("name")
        def image = column[String]("image")
        def description = column[String]("description")
        def traits = column[JsValue]("traits")
        def rarity = column[String]("rarity")
        def explicit = column[Boolean]("explicit")
        def status = column[NFTStatus.Value]("status")
        def mintTransactionId = column[String]("mint_transaction_id", O.Length(64))
        def royalty = column[JsValue]("royalty")
        def createdAt = column[Instant]("created_at")
        def updatedAt = column[Instant]("updated_at")
        def collection = foreignKey("nfts__collection_id_fk", collectionId, Collections.collections)(_.id, onDelete=ForeignKeyAction.Cascade)
        def * = (id, collectionId, tokenId, amount, name, image, description, traits, rarity, explicit, status, mintTransactionId, royalty, createdAt, updatedAt) <> (NFT.tupled, NFT.unapply)
    }

    val nfts = TableQuery[NFTs]
}
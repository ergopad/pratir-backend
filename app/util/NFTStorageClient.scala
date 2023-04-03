package util

import java.io.File
import java.nio.file.Files

import javax.inject._

import play.api.Configuration
import play.api.libs.ws.WSClient

import scala.concurrent.duration.Duration
import java.util.concurrent.TimeUnit
import scala.concurrent.Await

@Singleton
class NFTStorageClient @Inject() (ws: WSClient, private val config: Configuration) {
    private val NFT_STORAGE_ENDPOINT = "https://api.nft.storage/upload/";
    private val IPFS_PREFIX = "https://cloudflare-ipfs.com/ipfs/"
    private val REQUEST_TIME_OUT = 10000 // ms
    private val accessToken = config.get[String]("nft.storage.key")

    def upload(file: File): String = {
        val request = ws
            .url(NFT_STORAGE_ENDPOINT)
            .withRequestTimeout(Duration.create(REQUEST_TIME_OUT, TimeUnit.MILLISECONDS))
            .addHttpHeaders("Authorization" -> s"Bearer $accessToken")
            .addHttpHeaders("Accept" -> "*/*")
            .addHttpHeaders("Content-Type" -> Files.probeContentType(file.toPath()))
            .post(file)
        val response = Await.result(request, Duration.Inf)
        val cidRaw = response.json.\("value").\("cid").get.toString()
        val cid = cidRaw.slice(1, cidRaw.length() - 1)
        IPFS_PREFIX + cid
    }
}

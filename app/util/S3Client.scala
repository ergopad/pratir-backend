package util

import com.amazonaws.auth.AWSStaticCredentialsProvider
import com.amazonaws.auth.BasicAWSCredentials
import com.amazonaws.services.s3.AmazonS3
import com.amazonaws.services.s3.AmazonS3ClientBuilder

import javax.inject._

import play.api.Configuration

@Singleton
class S3Client @Inject() (private val config: Configuration) {
    private val awsRegion = config.get[String]("aws.region")
    private val defaultBucket = config.get[String]("aws.s3_bucket")
    private val credentials = new BasicAWSCredentials(
      config.get[String]("aws.access_key_id"),
      config.get[String]("aws.secret_access_key")
    )
    private val s3client = AmazonS3ClientBuilder
        .standard()
        .withCredentials(new AWSStaticCredentialsProvider(credentials))
        .withRegion(awsRegion)
        .build();

    def get(): AmazonS3 = s3client

    def bucket(): String = defaultBucket

    def region(): String = awsRegion
}

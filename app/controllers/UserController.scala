package controllers

import java.math.BigInteger
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

import javax.inject._

import org.ergoplatform.appkit._

import play.api.Logging
import play.api.libs.json.Json
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
    implicit val updateUserJson = Json.format[UpdateUser]
    implicit val userAuthRequestJson = Json.format[UserAuthRequest]
    implicit val userAuthResponseJson = Json.format[UserAuthResponse]
    implicit val userVerifyRequestJson = Json.format[UserVerifyRequest]
    implicit val userVerifyResponseJson = Json.format[UserVerifyResponse]

    def getAll(): Action[AnyContent] = Action.async { implicit request =>
        usersDao.getAll.map(user => Ok(Json.toJson(user)))
    }

    def getUser(address: String): Action[AnyContent] = Action { implicit request =>
        val user = Await.result(usersDao.getUser(address), Duration.Inf)
        val mockId = UUID.randomUUID
        Ok(Json.toJson(user.getOrElse(User(mockId, address, address, "", ""))))
    }

    def updateUser(): Action[AnyContent] = Action { implicit request =>
        val content = request.body
        val jsonObject = content.asJson
        val req: Option[UpdateUser] = jsonObject.flatMap(Json.fromJson[UpdateUser](_).asOpt)

        req match {
            case None => BadRequest("Bad Request Body")
            case Some(user) =>
                // verify access
                val verificationToken = user.verificationToken
                val authRequest =
                    Await.result(usersDao.getAuthRequestByToken(verificationToken), Duration.Inf)
                if (authRequest.isDefined && authRequest.get.address.equals(user.address)) {
                    // delete verification token from db
                    Await.result(usersDao.deleteAuthRequest(authRequest.get.id), Duration.Inf)
                    // update user
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
                } else {
                    Unauthorized("Unauthorized - Token Verification Failed")
                }

        }
    }

    def auth(): Action[AnyContent] = Action { implicit request =>
        val content = request.body
        val jsonObject = content.asJson
        val req: Option[UserAuthRequest] =
            jsonObject.flatMap(Json.fromJson[UserAuthRequest](_).asOpt)

        req match {
            case None => BadRequest("Bad Request Body")
            case Some(userAuthRequest) =>
                // create ergoauth signing request
                val signingMessage = getSigningMessage()
                val verificationId = UUID.randomUUID
                val verificationUrl = "/auth/" + verificationId.toString()
                val userAuthResponse = UserAuthResponse(
                  userAuthRequest.address,
                  signingMessage,
                  verificationId.toString(),
                  verificationUrl
                )
                // persist request to verify later
                Await.result(
                  usersDao.createAuthRequest(
                    AuthRequest(verificationId, userAuthRequest.address, signingMessage, None)
                  ),
                  Duration.Inf
                )
                Ok(Json.toJson(userAuthResponse))
        }
    }

    def verify(verificationId: String): Action[AnyContent] = Action { implicit request =>
        val content = request.body
        val jsonObject = content.asJson
        val req: Option[UserVerifyRequest] =
            jsonObject.flatMap(Json.fromJson[UserVerifyRequest](_).asOpt)

        req match {
            case None => BadRequest("Bad Request Body")
            case Some(userVerifyRequest) =>
                try {
                    val authRequest = Await.result(
                      usersDao.getAuthRequest(UUID.fromString(verificationId)),
                      Duration.Inf
                    )
                    if (authRequest.isDefined) {
                        val verified = verifyErgoAuthSignedMessage(
                          authRequest.get.address,
                          authRequest.get.signingMessage,
                          userVerifyRequest.signedMessage,
                          userVerifyRequest.proof
                        )
                        if (verified) {
                            // if verification token already exists do not recreate
                            val verificationToken =
                                if (authRequest.get.verificationToken.isDefined)
                                    authRequest.get.verificationToken.get
                                else getVerificationToken()
                            // persist verification token
                            Await.result(
                              usersDao
                                  .setAuthVerificationToken(authRequest.get.id, verificationToken),
                              Duration.Inf
                            )
                            val res = UserVerifyResponse(
                              authRequest.get.address,
                              isVerified = true,
                              Some(verificationToken)
                            )
                            Ok(Json.toJson(res))
                        } else {
                            val res = UserVerifyResponse(
                              authRequest.get.address,
                              isVerified = false,
                              None
                            )
                            Ok(Json.toJson(res))
                        }
                    } else {
                        NotFound("AuthRequest Not Found")
                    }
                } catch {
                    case e: Exception => BadRequest(e.getMessage)
                }
        }
    }

    private def getSigningMessage(nrChars: Int = 24): String = {
        new BigInteger(nrChars * 5, new SecureRandom()).toString()
    }

    private def verifyErgoAuthSignedMessage(
        address: String,
        message: String,
        signedMessage: String,
        proof: String
    ): Boolean = {
        val addressSigmaProp = SigmaProp.createFromAddress(Address.create(address))
        val proofBytes = Base64.getDecoder().decode(proof)
        ErgoAuthUtils.verifyResponse(addressSigmaProp, message, signedMessage, proofBytes)
    }

    private def getVerificationToken(): String = {
        UUID.randomUUID.toString()
    }
}

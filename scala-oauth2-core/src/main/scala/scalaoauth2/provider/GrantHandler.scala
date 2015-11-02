package scalaoauth2.provider

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import org.apache.commons.codec.binary.Base64.decodeBase64

case class GrantHandlerResult(tokenType: String, accessToken: String, expiresIn: Option[Long], refreshToken: Option[String], scope: Option[String])

trait GrantHandler {
  /**
   * Controls whether client credentials are required.  Defaults to true but can be overridden to be false when needed.
   * Per the OAuth2 specification, client credentials are required for all grant types except password, where it is up
   * to the authorization provider whether to make them required or not.
   */
  def clientCredentialRequired = true

  def handleRequest[U](request: AuthorizationRequest, maybeClientCredential: Option[ClientCredential], authorizationHandler: AuthorizationHandler[U]): Future[GrantHandlerResult]

  /**
   * Returns valid access token.
   */
  protected def issueAccessToken[U](handler: AuthorizationHandler[U], authInfo: AuthInfo[U]): Future[GrantHandlerResult] = {
    handler.getStoredAccessToken(authInfo).flatMap {
      case Some(token) if shouldRefreshAccessToken(token) => token.refreshToken.map {
        handler.refreshAccessToken(authInfo, _)
      }.getOrElse {
        handler.createAccessToken(authInfo)
      }
      case Some(token) => Future.successful(token)
      case None => handler.createAccessToken(authInfo)
    }.map(createGrantHandlerResult)
  }

  protected def shouldRefreshAccessToken(token: AccessToken) = token.isExpired

  protected def createGrantHandlerResult(accessToken: AccessToken) = GrantHandlerResult(
    "Bearer",
    accessToken.token,
    accessToken.expiresIn,
    accessToken.refreshToken,
    accessToken.scope
  )

}

class RefreshToken extends GrantHandler {

  override def handleRequest[U](request: AuthorizationRequest, maybeClientCredential: Option[ClientCredential], handler: AuthorizationHandler[U]): Future[GrantHandlerResult] = {
    val clientCredential = maybeClientCredential.getOrElse(throw new InvalidRequest("Client credential is required"))
    val refreshToken = request.requireRefreshToken

    handler.findAuthInfoByRefreshToken(refreshToken).flatMap { authInfoOption =>
      val authInfo = authInfoOption.getOrElse(throw new InvalidGrant("Authorized information is not found by the refresh token"))
      if (authInfo.clientId != Some(clientCredential.clientId)) {
        throw new InvalidClient
      }

      handler.refreshAccessToken(authInfo, refreshToken).map(createGrantHandlerResult)
    }
  }
}

class Password extends GrantHandler {

  override def handleRequest[U](request: AuthorizationRequest, maybeClientCredential: Option[ClientCredential], handler: AuthorizationHandler[U]): Future[GrantHandlerResult] = {
    if (clientCredentialRequired && maybeClientCredential.isEmpty) {
      throw new InvalidRequest("Client credential is required")
    }

    //We don't want the clients to send username and password in query string. We want to use HTTP Basic Authentication

    getHeader(request).map{ userCtx =>
      val username = userCtx.username
      val password = userCtx.password

      handler.findUser(username, password).flatMap { userOption =>
        val user = userOption.getOrElse(throw new InvalidGrant("username or password is incorrect"))
        val scope = request.scope
        val clientId = maybeClientCredential.map { _.clientId }
        val authInfo = AuthInfo(user, clientId, scope, None)

        issueAccessToken(handler, authInfo)
      }
    }.getOrElse(throw new InvalidRequest("Invalid HTTP Header credential is required"))

  }

  case class userCtx(username: String, password: String)

  def getHeader(request: AuthorizationRequest): Option[userCtx] = {
    request.headers.get("Authorization").headOption.flatMap { encoded =>
      new String(decodeBase64(encoded.toString.getBytes)).split(":").toList match {
        case username :: password :: Nil => Some(userCtx(username, password))
        case _ => None
      }
    }
  }
}

class ClientCredentials extends GrantHandler {

  override def handleRequest[U](request: AuthorizationRequest, maybeClientCredential: Option[ClientCredential], handler: AuthorizationHandler[U]): Future[GrantHandlerResult] = {
    val clientCredential = maybeClientCredential.getOrElse(throw new InvalidRequest("Client credential is required"))
    val scope = request.scope

    handler.findClientUser(clientCredential, scope).flatMap { optionalUser =>
      val user = optionalUser.getOrElse(throw new InvalidGrant("client_id or client_secret or scope is incorrect"))
      val authInfo = AuthInfo(user, Some(clientCredential.clientId), scope, None)

      issueAccessToken(handler, authInfo)
    }
  }

}

class AuthorizationCode extends GrantHandler {

  override def handleRequest[U](request: AuthorizationRequest, maybeClientCredential: Option[ClientCredential], handler: AuthorizationHandler[U]): Future[GrantHandlerResult] = {
    val clientCredential = maybeClientCredential.getOrElse(throw new InvalidRequest("Client credential is required"))
    val clientId = clientCredential.clientId
    val code = request.requireCode
    val redirectUri = request.redirectUri

    handler.findAuthInfoByCode(code).flatMap { optionalAuthInfo =>
      val authInfo = optionalAuthInfo.getOrElse(throw new InvalidGrant("Authorized information is not found by the code"))
      if (authInfo.clientId != Some(clientId)) {
        throw new InvalidClient
      }

      if (authInfo.redirectUri.isDefined && authInfo.redirectUri != redirectUri) {
        throw new RedirectUriMismatch
      }

      val f = issueAccessToken(handler, authInfo)
      f onSuccess { case _ => handler.deleteAuthCode(code) }
      f
    }
  }

}

class Implicit extends GrantHandler {

  override def handleRequest[U](request: AuthorizationRequest, maybeClientCredential: Option[ClientCredential], handler: AuthorizationHandler[U]): Future[GrantHandlerResult] = {
    val clientId = request.clientId.getOrElse(throw new InvalidRequest("Client id is required"))

    handler.findUser(request).flatMap { userOption =>
      val user = userOption.getOrElse(throw new InvalidGrant("user cannot be authenticated"))
      val scope = request.scope
      val authInfo = AuthInfo(user, Some(clientId), scope, None)

      issueAccessToken(handler, authInfo)
    }
  }

  /**
   * Implicit grant doesn't support refresh token
   */
  protected override def shouldRefreshAccessToken(accessToken: AccessToken) = false

  /**
   * Implicit grant must not return refresh token
   */
  protected override def createGrantHandlerResult(accessToken: AccessToken) = super.createGrantHandlerResult(accessToken).copy(refreshToken = None)

}

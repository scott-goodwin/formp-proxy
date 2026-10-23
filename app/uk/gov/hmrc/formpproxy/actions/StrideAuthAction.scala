/*
 * Copyright 2026 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.formpproxy.actions

import play.api.Logging
import play.api.libs.json.Json
import play.api.mvc.*
import play.api.mvc.Results.*
import uk.gov.hmrc.auth.core.*
import uk.gov.hmrc.auth.core.AuthProvider.PrivilegedApplication
import uk.gov.hmrc.auth.core.authorise.Predicate
import uk.gov.hmrc.auth.core.retrieve.v2.Retrievals.*
import uk.gov.hmrc.auth.core.retrieve.{Credentials, ~}
import uk.gov.hmrc.formpproxy.sdlt.models.auth.StrideRequest
import uk.gov.hmrc.http.HeaderCarrier
import uk.gov.hmrc.play.http.HeaderCarrierConverter

import scala.concurrent.{ExecutionContext, Future}

class StrideAuthAction(
  roles: Seq[String],
  val authConnector: AuthConnector,
  val parser: BodyParsers.Default
)(implicit val executionContext: ExecutionContext)
    extends ActionBuilder[StrideRequest, AnyContent]
    with AuthorisedFunctions
    with Logging {

  require(roles.nonEmpty, "StrideAuthAction needs at least one role")

  private val rolesPredicate: Predicate = roles.map(role => Enrolment(role): Predicate).reduce(_ or _)

  override def invokeBlock[A](request: Request[A], block: StrideRequest[A] => Future[Result]): Future[Result] = {
    implicit val hc: HeaderCarrier = HeaderCarrierConverter.fromRequest(request)

    authorised(AuthProviders(PrivilegedApplication) and rolesPredicate)
      .retrieve(credentials and authorisedEnrolments) {
        case Some(Credentials(providerId, _)) ~ Enrolments(enrolments) =>
          block(StrideRequest(providerId, enrolments.map(_.key), request))
        case _                                                         =>
          logger.warn(s"[StrideAuthAction][${request.uri}] Could not retrieve credentials")
          Future.successful(Forbidden(Json.obj("error" -> "Forbidden")))
      }
      .recover {
        case _: NoActiveSession        =>
          logger.info(s"[StrideAuthAction][${request.uri}] No active Stride session")
          Unauthorized(Json.obj("error" -> "No active Stride session"))
        case e: AuthorisationException =>
          logger.warn(s"[StrideAuthAction][${request.uri}] Unauthorised: ${e.getMessage}")
          Forbidden(Json.obj("error" -> "Forbidden"))
      }
  }
}

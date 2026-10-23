/*
 * Copyright 2026 HM Revenue & Customs
 *
 */

package uk.gov.hmrc.formpproxy.actions

import play.api.Configuration
import play.api.mvc.BodyParsers
import uk.gov.hmrc.auth.core.AuthConnector

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@Singleton
class StrideAuthActionProvider @Inject() (
  authConnector: AuthConnector,
  parser: BodyParsers.Default,
  config: Configuration
)(implicit ec: ExecutionContext) {

  lazy val sdltAdhocQuery: StrideAuthAction =
    new StrideAuthAction(config.get[Seq[String]]("stride.roles.sdltAdhocQuery"), authConnector, parser)
}


package uk.gov.hmrc.formpproxy.sdlt.models.auth

import play.api.mvc.{Request, WrappedRequest}

final case class StrideRequest[A](
                                   providerId: String,
                                   roles: Set[String],
                                   request: Request[A]
                                 ) extends WrappedRequest[A](request)
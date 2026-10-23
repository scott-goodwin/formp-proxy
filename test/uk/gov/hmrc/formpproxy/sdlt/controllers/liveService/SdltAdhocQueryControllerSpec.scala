/*
 * Copyright 2026 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.formpproxy.sdlt.controllers.liveService

import org.mockito.Mockito.{verify, verifyNoInteractions, verifyNoMoreInteractions, when}
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.freespec.AnyFreeSpec
import org.scalatest.matchers.must.Matchers
import org.scalatestplus.mockito.MockitoSugar
import play.api.http.Status.{BAD_REQUEST, FORBIDDEN, OK}
import play.api.libs.json.{JsObject, JsValue, Json}
import play.api.mvc.Results.Forbidden
import play.api.mvc.{BodyParsers, ControllerComponents, Request, Result}
import play.api.test.FakeRequest
import play.api.test.Helpers.{ACCEPT, CONTENT_TYPE, JSON, contentAsJson, contentType, defaultAwaitTimeout, status, stubControllerComponents}
import uk.gov.hmrc.auth.core.AuthConnector
import uk.gov.hmrc.formpproxy.actions.{StrideAuthAction, StrideAuthActionProvider}
import uk.gov.hmrc.formpproxy.sdlt.models.auth.StrideRequest
import uk.gov.hmrc.formpproxy.sdlt.repositories.SdltAdhocQueryRepository

import java.sql.SQLException
import scala.concurrent.{ExecutionContext, Future}

class SdltAdhocQueryControllerSpec extends AnyFreeSpec with Matchers with ScalaFutures with MockitoSugar {

  private val govTalkSql =
    "SELECT PROTOCOL_STATUS, CORRELATIONID, FORMRESULTID FROM SDLT_FILE_DATA.GOVTALK_STATUS " +
      "WHERE CORRELATIONID = '16669A8CB08049CC96AAFFF97A94F1BE' AND PROTOCOL_STATUS = 'dataPoll'"

  "SdltAdhocQueryController run" - {

    "invalid payload" - {

      "must return BadRequest when `sql` is missing" in new Setup {
        val res = controller.run()(makeRequest(Json.obj()))

        status(res) mustBe BAD_REQUEST
        (contentAsJson(res) \ "error").as[String] mustBe "Missing 'sql'"
        verifyNoInteractions(mockRepo)
      }

      "must return BadRequest when `sql` is not a string" in new Setup {
        val res = controller.run()(makeRequest(Json.obj("sql" -> 123)))

        status(res) mustBe BAD_REQUEST
        (contentAsJson(res) \ "error").as[String] mustBe "Missing 'sql'"
        verifyNoInteractions(mockRepo)
      }

      "must return BadRequest for unrelated json" in new Setup {
        val res = controller.run()(makeRequest(Json.obj("foo" -> "bar")))

        status(res) mustBe BAD_REQUEST
        (contentAsJson(res) \ "error").as[String] mustBe "Missing 'sql'"
        verifyNoInteractions(mockRepo)
      }
    }

    "valid payload" - {

      "must return Ok with rowCount and rows when the query returns rows" in new Setup {
        val rows: Seq[JsObject] = Seq(
          Json.obj(
            "PROTOCOL_STATUS" -> "dataPoll",
            "CORRELATIONID"   -> "16669A8CB08049CC96AAFFF97A94F1BE",
            "FORMRESULTID"    -> "FR-1"
          ),
          Json.obj(
            "PROTOCOL_STATUS" -> "dataPoll",
            "CORRELATIONID"   -> "16669A8CB08049CC96AAFFF97A94F1BE",
            "FORMRESULTID"    -> "FR-2"
          )
        )

        when(mockRepo.runQuery(govTalkSql)).thenReturn(Future.successful(rows))

        val res: Future[Result] = controller.run()(makeRequest(Json.obj("sql" -> govTalkSql)))

        status(res) mustBe OK
        contentType(res) mustBe Some(JSON)

        val json = contentAsJson(res)
        (json \ "rowCount").as[Int] mustBe 2
        (json \ "rows").as[Seq[JsObject]] mustBe rows
        ((json \ "rows")(0) \ "FORMRESULTID").as[String] mustBe "FR-1"

        verify(mockRepo).runQuery(govTalkSql)
        verifyNoMoreInteractions(mockRepo)
      }

      "must return Ok with rowCount 0 and empty rows when nothing matches" in new Setup {
        when(mockRepo.runQuery(govTalkSql)).thenReturn(Future.successful(Seq.empty))

        val res = controller.run()(makeRequest(Json.obj("sql" -> govTalkSql)))

        status(res) mustBe OK
        (contentAsJson(res) \ "rowCount").as[Int] mustBe 0
        (contentAsJson(res) \ "rows").as[Seq[JsObject]] mustBe empty

        verify(mockRepo).runQuery(govTalkSql)
        verifyNoMoreInteractions(mockRepo)
      }

      "must preserve null column values in rows" in new Setup {
        val rows = Seq(Json.obj("STATUS" -> "STARTED", "DECLARATION" -> play.api.libs.json.JsNull))
        val sql  = "SELECT STATUS, DECLARATION FROM SDLT_FILE_DATA.RETURN"

        when(mockRepo.runQuery(sql)).thenReturn(Future.successful(rows))

        val res = controller.run()(makeRequest(Json.obj("sql" -> sql)))

        status(res) mustBe OK
        ((contentAsJson(res) \ "rows")(0) \ "DECLARATION").toOption mustBe Some(play.api.libs.json.JsNull)
      }

      "must pass the sql to the repository unchanged" in new Setup {
        val sql = "SELECT 1 FROM DUAL;"
        when(mockRepo.runQuery(sql)).thenReturn(Future.successful(Seq.empty))

        status(controller.run()(makeRequest(Json.obj("sql" -> sql)))) mustBe OK

        verify(mockRepo).runQuery(sql)
        verifyNoMoreInteractions(mockRepo)
      }

      "must return BadRequest with the Oracle message when the query fails with SQLException" in new Setup {
        when(mockRepo.runQuery(govTalkSql))
          .thenReturn(Future.failed(new SQLException("ORA-00942: table or view does not exist")))

        val res = controller.run()(makeRequest(Json.obj("sql" -> govTalkSql)))

        status(res) mustBe BAD_REQUEST
        (contentAsJson(res) \ "error").as[String] must include("ORA-00942")

        verify(mockRepo).runQuery(govTalkSql)
        verifyNoMoreInteractions(mockRepo)
      }

      "must return BadRequest when the read-only user is refused a write" in new Setup {
        val sql = "DELETE FROM SDLT_FILE_DATA.GOVTALK_STATUS WHERE 1 = 0"
        when(mockRepo.runQuery(sql))
          .thenReturn(Future.failed(new SQLException("ORA-01031: insufficient privileges")))

        val res = controller.run()(makeRequest(Json.obj("sql" -> sql)))

        status(res) mustBe BAD_REQUEST
        (contentAsJson(res) \ "error").as[String] must include("ORA-01031")
      }

      "must not handle unexpected (non-SQL) exceptions" in new Setup {
        when(mockRepo.runQuery(govTalkSql))
          .thenReturn(Future.failed(new RuntimeException("Pool exhausted")))

        val res = controller.run()(makeRequest(Json.obj("sql" -> govTalkSql)))

        res.failed.futureValue mustBe a[RuntimeException]
      }
    }

    "authorisation" - {

      "must return Forbidden and never touch the repository when Stride auth fails" in new Setup {
        override def strideResult: Option[Result] = Some(Forbidden(Json.obj("error" -> "Forbidden")))

        val res = controller.run()(makeRequest(Json.obj("sql" -> govTalkSql)))

        status(res) mustBe FORBIDDEN
        verifyNoInteractions(mockRepo)
      }
    }
  }

  private class FakeStrideAuthAction(
    parser: BodyParsers.Default,
    authConnector: AuthConnector,
    result: Option[Result]
  )(implicit ec: ExecutionContext)
      extends StrideAuthAction(Seq("sdlt_adhoc_query"), authConnector, parser) {

    override def invokeBlock[A](request: Request[A], block: StrideRequest[A] => Future[Result]): Future[Result] =
      result.fold(block(StrideRequest("stride-user-123", Set("sdlt_adhoc_query"), request)))(Future.successful)
  }

  private trait Setup {
    implicit val ec: ExecutionContext       = scala.concurrent.ExecutionContext.global
    private val cc: ControllerComponents    = stubControllerComponents()
    private val parser: BodyParsers.Default = new BodyParsers.Default(cc.parsers)

    def strideResult: Option[Result] = None

    val mockRepo: SdltAdhocQueryRepository = mock[SdltAdhocQueryRepository]
    private val mockStrideProvider         = mock[StrideAuthActionProvider]

    when(mockStrideProvider.sdltAdhocQuery)
      .thenReturn(new FakeStrideAuthAction(parser, mock[AuthConnector], strideResult))

    val controller = new SdltAdhocQueryController(mockRepo, mockStrideProvider, cc)

    def makeRequest(body: JsValue): FakeRequest[JsValue] =
      FakeRequest("POST", "/formp-proxy/sdlt/admin/query")
        .withHeaders(CONTENT_TYPE -> JSON, ACCEPT -> JSON)
        .withBody(body)
  }
}

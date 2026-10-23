///*
// * Copyright 2026 HM Revenue & Customs
// *
// * Licensed under the Apache License, Version 2.0 (the "License");
// * you may not use this file except in compliance with the License.
// * You may obtain a copy of the License at
// *
// *     http://www.apache.org/licenses/LICENSE-2.0
// *
// * Unless required by applicable law or agreed to in writing, software
// * distributed under the License is distributed on an "AS IS" BASIS,
// * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// * See the License for the specific language governing permissions and
// * limitations under the License.
// */
//
//package uk.gov.hmrc.formpproxy.sdlt.DONOTDELETE
//
//import com.github.tomakehurst.wiremock.client.WireMock
//import org.scalatest.concurrent.{IntegrationPatience, ScalaFutures}
//import org.scalatest.matchers.must.Matchers
//import org.scalatest.wordspec.AnyWordSpec
//import play.api.http.Status.*
//import play.api.libs.json.{JsObject, Json}
//import uk.gov.hmrc.formpproxy.itutil.{ApplicationWithWiremock, AuthStub}
//import uk.gov.hmrc.http.HttpResponse
//
//import scala.util.Try
//
//class AdhocQueryIntegrationSpec
//  extends AnyWordSpec
//    with Matchers
//    with ScalaFutures
//    with IntegrationPatience
//    with ApplicationWithWiremock {
//
//  private val queryEndpoint        = "sdlt/admin/query"
//  private val createReturnEndpoint = "create/return"
//
//  private def uniqueStorn(): String =
//    System.currentTimeMillis().toString.takeRight(10)
//
//  private def query(sql: String) =
//    postJson(queryEndpoint, Json.obj("sql" -> sql))
//
//  private def show(label: String, res: HttpResponse): Unit = {
//    println(s"\n\n=== $label ===")
//    println(s"Status: ${res.status}")
//
//    Try(res.json).toOption match {
//      case None       =>
//        println(s"Body (not JSON): '${res.body}'")
//      case Some(json) =>
//        println("Full response body:")
//        println(Json.prettyPrint(json))
//
//        (json \ "rowCount").asOpt[Int].foreach(c => println(s"rowCount: $c"))
//        (json \ "error").asOpt[String].foreach(e => println(s"error: ${e.trim}"))
//
//        (json \ "rows").asOpt[Seq[JsObject]].foreach { rows =>
//          rows.zipWithIndex.foreach { case (row, i) =>
//            println(s"Row ${i + 1}:")
//            row.fields.foreach { case (col, value) => println(s"  $col -> $value") }
//          }
//        }
//    }
//  }
//
//  "Ad-hoc query endpoint" should {
//
//    "return rows for a simple SELECT" in {
//      StrideAuthStub.authorised()
//
//      val res = query("SELECT 1 AS ONE, 'hello' AS GREETING FROM DUAL")
//      show("Simple SELECT", res)
//
//      res.status mustBe OK
//      (res.json \ "rowCount").as[Int] mustBe 1
//      val row = (res.json \ "rows")(0).as[JsObject]
//      (row \ "ONE").as[String] mustBe "1"
//      (row \ "GREETING").as[String] mustBe "hello"
//    }
//
//    "accept a trailing semicolon" in {
//      StrideAuthStub.authorised()
//
//      val res = query("SELECT 1 AS ONE FROM DUAL;")
//      show("Trailing semicolon", res)
//
//      res.status mustBe OK
//    }
//
//    "run the GovTalk status query" in {
//      StrideAuthStub.authorised()
//
//      val res = query(
//        """SELECT PROTOCOL_STATUS, CORRELATIONID, FORMRESULTID
//          |  FROM SDLT_FILE_DATA.GOVTALK_STATUS
//          | WHERE CORRELATIONID = '16669A8CB08049CC96AAFFF97A94F1BE'
//          |   AND PROTOCOL_STATUS = 'dataPoll'""".stripMargin
//      )
//      show("GovTalk status query", res)
//
//      res.status mustBe OK
//    }
//
//    "create a return through the normal API then find it with an ad-hoc query" in {
//      AuthStub.authorised()
//      val storn = uniqueStorn()
//
//      val returnRes = postJson(
//        createReturnEndpoint,
//        Json.obj(
//          "stornId"              -> storn,
//          "purchaserIsCompany"   -> "N",
//          "surNameOrCompanyName" -> "Smith",
//          "houseNumber"          -> 100,
//          "addressLine1"         -> "High Street",
//          "addressLine2"         -> "Anytown",
//          "postcode"             -> "AB12 3CD",
//          "transactionType"      -> "RESIDENTIAL"
//        )
//      )
//      show(s"STEP 1: Create Return for storn $storn (read-write pool)", returnRes)
//      returnRes.status mustBe CREATED
//      val returnResourceRef = (returnRes.json \ "returnResourceRef").as[String]
//
//      StrideAuthStub.authorised()
//      val res = query(
//        s"SELECT * FROM SDLT_FILE_DATA.RETURN WHERE STORN = '$storn'"
//      )
//      show("STEP 2: Query it back with SELECT * (read-only pool)", res)
//
//      res.status mustBe OK
//      (res.json \ "rowCount").as[Int] mustBe 1
//      ((res.json \ "rows")(0) \ "RETURN_RESOURCE_REF").as[String] mustBe returnResourceRef
//    }
//
//    "refuse to write, because the read-only user has no DML rights" in {
//      StrideAuthStub.authorised()
//
//      // WHERE 1 = 0 means nothing is deleted even if this somehow ran on the wrong pool
//      val res = query("DELETE FROM SDLT_FILE_DATA.GOVTALK_STATUS WHERE 1 = 0")
//      show("DELETE attempt (should be refused)", res)
//
//      res.status mustBe BAD_REQUEST
//      // ORA-01031 on Oracle 19c/XE, ORA-41900 on Oracle 23
//      (res.json \ "error").as[String] must (include("ORA-01031") or include("ORA-41900"))
//    }
//
//    "not see other services' schemas" in {
//      StrideAuthStub.authorised()
//
//      val res = query(
//        "SELECT DISTINCT OWNER FROM ALL_TABLES WHERE OWNER IN ('CIS_FILE_DATA', 'CHARITIES_DATA', 'NOVA')"
//      )
//      show("Other schemas visible (should be none)", res)
//
//      res.status mustBe OK
//      (res.json \ "rowCount").as[Int] mustBe 0
//    }
//
//    "return 400 for invalid SQL" in {
//      StrideAuthStub.authorised()
//
//      val res = query("SELEC nonsense")
//      show("Invalid SQL", res)
//
//      res.status mustBe BAD_REQUEST
//      (res.json \ "error").as[String] must include("ORA-")
//    }
//
//    "return 400 when sql is missing" in {
//      StrideAuthStub.authorised()
//
//      val res = postJson(queryEndpoint, Json.obj())
//      show("Missing sql field", res)
//
//      res.status mustBe BAD_REQUEST
//    }
//
//    "return 401 with no Stride session" in {
//      StrideAuthStub.noSession()
//
//      val res = query("SELECT 1 FROM DUAL")
//      show("No Stride session", res)
//
//      res.status mustBe UNAUTHORIZED
//    }
//
//    "return 403 without the required role" in {
//      StrideAuthStub.insufficientRole()
//
//      val res = query("SELECT 1 FROM DUAL")
//      show("Wrong Stride role", res)
//
//      res.status mustBe FORBIDDEN
//    }
//  }
//
//  private object StrideAuthStub {
//
//    private val authoriseUrl = "/auth/authorise"
//
//    def authorised(
//                    providerId: String = "stride-user-123",
//                    roles: Seq[String] = Seq("sdlt_adhoc_query")
//                  ): Unit =
//      WireMock.stubFor(
//        WireMock
//          .post(WireMock.urlPathEqualTo(authoriseUrl))
//          .willReturn(
//            WireMock.okJson(
//              Json
//                .obj(
//                  "optionalCredentials"  -> Json.obj(
//                    "providerId"   -> providerId,
//                    "providerType" -> "PrivilegedApplication"
//                  ),
//                  "authorisedEnrolments" -> roles.map(role =>
//                    Json.obj("key" -> role, "identifiers" -> Json.arr(), "state" -> "Activated")
//                  )
//                )
//                .toString
//            )
//          )
//      )
//
//    def noSession(): Unit        = unauthorised("MissingBearerToken")
//    def insufficientRole(): Unit = unauthorised("InsufficientEnrolments")
//
//    private def unauthorised(reason: String): Unit =
//      WireMock.stubFor(
//        WireMock
//          .post(WireMock.urlPathEqualTo(authoriseUrl))
//          .willReturn(
//            WireMock
//              .aResponse()
//              .withStatus(401)
//              .withHeader("WWW-Authenticate", s"""MDTP detail="$reason"""")
//          )
//      )
//  }
//}
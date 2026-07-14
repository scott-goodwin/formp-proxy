/*
 * Copyright 2025 HM Revenue & Customs
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

package uk.gov.hmrc.formpproxy.sdlt.repositories

import oracle.jdbc.OracleTypes
import org.mockito.ArgumentMatchers.{any as anyArg, eq as eqTo}
import org.mockito.Mockito.*
import play.api.db.Database
import uk.gov.hmrc.formpproxy.base.SpecBase
import uk.gov.hmrc.formpproxy.sdlt.models.*
import uk.gov.hmrc.formpproxy.sdlt.models.returns.{ReturnForPurge, ReturnSummary, ReturnsForPurgeResponse, SdltReturnRecordResponse}
import uk.gov.hmrc.formpproxy.sdlt.models.vendor.*
import uk.gov.hmrc.formpproxy.sdlt.models.purchaser.*
import uk.gov.hmrc.formpproxy.sdlt.models.agents.*
import uk.gov.hmrc.formpproxy.sdlt.models.land.*
import uk.gov.hmrc.formpproxy.sdlt.models.transaction.*
import uk.gov.hmrc.formpproxy.sdlt.models.residency.*
import uk.gov.hmrc.formpproxy.sdlt.models.lease.*
import uk.gov.hmrc.formpproxy.sdlt.models.taxCalculation.*
import uk.gov.hmrc.formpproxy.sdlt.models.submission.*

import java.sql.*

final class SdltFormpRepositorySpec extends SpecBase with SdltFormpRepoDataHelper {

  trait ReturnsFixture {
    val db   = mock[Database]
    val conn = mock[Connection]
    val cs   = mock[CallableStatement]

    val resRetSummary = mock[ResultSet]

  }

  "sdltCreateReturn" - {

    "call Create_Return stored procedure with correct parameters and return submission ID" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call RETURN_PROCS.Create_Return(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")))
        .thenReturn(cs)
      when(cs.getLong(11)).thenReturn(100001L)

      val repo = new SdltFormpRepository(db)

      val request = CreateReturnRequest(
        stornId = "STORN12345",
        purchaserIsCompany = "NO",
        surNameOrCompanyName = "Smith",
        houseNumber = Some(42),
        addressLine1 = "High Street",
        addressLine2 = Some("Kensington"),
        addressLine3 = Some("London"),
        addressLine4 = None,
        postcode = Some("SW1A 1AA"),
        transactionType = "RESIDENTIAL"
      )

      val result = repo.sdltCreateReturn(request).futureValue

      result mustBe "100001"

      verify(conn).prepareCall("{ call RETURN_PROCS.Create_Return(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setString(2, "NO")
      verify(cs).setString(3, "Smith")
      verify(cs).setString(4, "42")
      verify(cs).setString(5, "High Street")
      verify(cs).setString(6, "Kensington")
      verify(cs).setString(7, "London")
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setString(9, "SW1A 1AA")
      verify(cs).setString(10, "RESIDENTIAL")
      verify(cs).registerOutParameter(11, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()
    }

    "call Create_Return with company purchaser" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getLong(11)).thenReturn(100002L)

      val repo = new SdltFormpRepository(db)

      val request = CreateReturnRequest(
        stornId = "STORN99999",
        purchaserIsCompany = "YES",
        surNameOrCompanyName = "ABC Property Ltd",
        houseNumber = Some(100),
        addressLine1 = "Business Park",
        addressLine2 = Some("Westminster"),
        addressLine3 = Some("London"),
        addressLine4 = None,
        postcode = Some("W1B 2EL"),
        transactionType = "NON_RESIDENTIAL"
      )

      val result = repo.sdltCreateReturn(request).futureValue

      result mustBe "100002"

      verify(cs).setString(1, "STORN99999")
      verify(cs).setString(2, "YES")
      verify(cs).setString(3, "ABC Property Ltd")
      verify(cs).execute()
    }

    "handle optional fields being None" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getLong(11)).thenReturn(100003L)

      val repo = new SdltFormpRepository(db)

      val request = CreateReturnRequest(
        stornId = "STORN88888",
        purchaserIsCompany = "NO",
        surNameOrCompanyName = "Johnson",
        houseNumber = None,
        addressLine1 = "Oak Street",
        addressLine2 = None,
        addressLine3 = None,
        addressLine4 = None,
        postcode = None,
        transactionType = "RESIDENTIAL"
      )

      val result = repo.sdltCreateReturn(request).futureValue

      result mustBe "100003"

      verify(cs).setNull(4, Types.VARCHAR)
      verify(cs).setString(5, "Oak Street")
      verify(cs).setNull(6, Types.VARCHAR)
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).execute()
    }
  }

  "sdltGetReturn" - {

    "call Get_Return_With_Residency and process all cursors successfully" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      val rsOrg       = mock[ResultSet]
      val rsReturn    = mock[ResultSet]
      val rsPurchaser = mock[ResultSet]
      val rsCompany   = mock[ResultSet]
      val rsVendor    = mock[ResultSet]
      val rsLand      = mock[ResultSet]
      val rsTrans     = mock[ResultSet]
      val rsRetAgent  = mock[ResultSet]
      val rsAgent     = mock[ResultSet]
      val rsLease     = mock[ResultSet]
      val rsTax       = mock[ResultSet]
      val rsSub       = mock[ResultSet]
      val rsSubErr    = mock[ResultSet]
      val rsRes       = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(
          eqTo("{ call RETURN_PROCS.Get_Return_With_Residency(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
        )
      )
        .thenReturn(cs)

      when(cs.getObject(eqTo(3), eqTo(classOf[ResultSet]))).thenReturn(rsOrg)
      when(cs.getObject(eqTo(4), eqTo(classOf[ResultSet]))).thenReturn(rsReturn)
      when(cs.getObject(eqTo(5), eqTo(classOf[ResultSet]))).thenReturn(rsPurchaser)
      when(cs.getObject(eqTo(6), eqTo(classOf[ResultSet]))).thenReturn(rsCompany)
      when(cs.getObject(eqTo(7), eqTo(classOf[ResultSet]))).thenReturn(rsVendor)
      when(cs.getObject(eqTo(8), eqTo(classOf[ResultSet]))).thenReturn(rsLand)
      when(cs.getObject(eqTo(9), eqTo(classOf[ResultSet]))).thenReturn(rsTrans)
      when(cs.getObject(eqTo(10), eqTo(classOf[ResultSet]))).thenReturn(rsRetAgent)
      when(cs.getObject(eqTo(11), eqTo(classOf[ResultSet]))).thenReturn(rsAgent)
      when(cs.getObject(eqTo(12), eqTo(classOf[ResultSet]))).thenReturn(rsLease)
      when(cs.getObject(eqTo(13), eqTo(classOf[ResultSet]))).thenReturn(rsTax)
      when(cs.getObject(eqTo(14), eqTo(classOf[ResultSet]))).thenReturn(rsSub)
      when(cs.getObject(eqTo(15), eqTo(classOf[ResultSet]))).thenReturn(rsSubErr)
      when(cs.getObject(eqTo(16), eqTo(classOf[ResultSet]))).thenReturn(rsRes)

      when(rsOrg.next()).thenReturn(true, false)
      when(rsOrg.getString("IS_RETURN_USER")).thenReturn("YES")
      when(rsOrg.getString("DO_NOT_DISPLAY_WELCOME_PAGE")).thenReturn("NO")
      when(rsOrg.getString("STORN")).thenReturn("STORN12345")
      when(rsOrg.getString("VERSION")).thenReturn("1")

      when(rsReturn.next()).thenReturn(true, false)
      when(rsReturn.getString("RETURN_ID")).thenReturn("100001")
      when(rsReturn.getString("STORN")).thenReturn("STORN12345")
      when(rsReturn.getString("STATUS")).thenReturn("STARTED")
      when(rsReturn.getString("RETURN_RESOURCE_REF")).thenReturn("100001")

      when(rsPurchaser.next()).thenReturn(true, false)
      when(rsPurchaser.getString("PURCHASER_ID")).thenReturn("1")
      when(rsPurchaser.getString("IS_COMPANY")).thenReturn("NO")
      when(rsPurchaser.getString("SURNAME")).thenReturn("Smith")

      when(rsCompany.next()).thenReturn(false)
      when(rsVendor.next()).thenReturn(true, false)
      when(rsVendor.getString("VENDOR_ID")).thenReturn("1")
      when(rsLand.next()).thenReturn(true, false)
      when(rsLand.getString("LAND_ID")).thenReturn("1")
      when(rsTrans.next()).thenReturn(true, false)
      when(rsTrans.getString("TRANSACTION_ID")).thenReturn("1")
      when(rsTrans.getBigDecimal("TOTAL_CONSIDERATION")).thenReturn(new java.math.BigDecimal("250000.00"))
      when(rsRetAgent.next()).thenReturn(false)
      when(rsAgent.next()).thenReturn(false)
      when(rsLease.next()).thenReturn(false)
      when(rsTax.next()).thenReturn(true, false)
      when(rsTax.getString("TAX_CALCULATION_ID")).thenReturn("1")
      when(rsTax.getString("TAX_DUE")).thenReturn("2500.00")
      when(rsTax.getString("TAX_DUE_PREMIUM")).thenReturn("7500.00")
      when(rsTax.getString("TAX_DUE_NPV")).thenReturn("1000.00")
      when(rsTax.getString("CALC_TAX_RATE1")).thenReturn("3%")
      when(rsTax.getString("CALC_TAX_RATE2")).thenReturn("1%")
      when(rsTax.getString("CALC_TOTAL_TAX_PENALTY_DUE")).thenReturn("8500.00")
      when(rsTax.getString("CALC_TOTAL_NPV_TAX")).thenReturn("1000.00")
      when(rsTax.getString("CALC_TOTAL_PREMIUM_TAX")).thenReturn("7500.00")
      when(rsSub.next()).thenReturn(true, false)
      when(rsSub.getString("SUBMISSION_ID")).thenReturn("1")
      when(rsSub.getString("SUBMISSION_STATUS")).thenReturn("STARTED")
      when(rsSubErr.next()).thenReturn(false)
      when(rsRes.next()).thenReturn(true, false)
      when(rsRes.getString("RESIDENCY_ID")).thenReturn("1")

      val repo = new SdltFormpRepository(db)

      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      result.stornId mustBe Some("STORN12345")
      result.returnResourceRef mustBe Some("100001")
      result.sdltOrganisation must not be None
      result.sdltOrganisation.get.storn mustBe Some("STORN12345")
      result.returnInfo       must not be None
      result.returnInfo.get.status mustBe Some("STARTED")
      result.purchaser        must not be None
      result.purchaser.get    must have size 1
      result.companyDetails mustBe None
      result.vendor           must not be None
      result.land             must not be None
      result.transaction      must not be None
      result.taxCalculation   must not be None
      result.taxCalculation.get.taxDue mustBe Some("2500.00")
      result.taxCalculation.get.taxDuePremium mustBe Some("7500.00")
      result.taxCalculation.get.taxDueNPV mustBe Some("1000.00")
      result.taxCalculation.get.calcTaxRate1 mustBe Some("3%")
      result.taxCalculation.get.calcTaxRate2 mustBe Some("1%")
      result.taxCalculation.get.calcTotalTaxPenaltyDue mustBe Some("8500.00")
      result.taxCalculation.get.calcTotalNPVTax mustBe Some("1000.00")
      result.taxCalculation.get.calcTotalPremiumTax mustBe Some("7500.00")
      result.submission       must not be None
      result.residency        must not be None

      verify(conn).prepareCall(
        "{ call RETURN_PROCS.Get_Return_With_Residency(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
      )
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle null cursors gracefully" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      when(cs.getObject(anyArg[Int], eqTo(classOf[ResultSet]))).thenReturn(null)

      val repo = new SdltFormpRepository(db)

      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      result.stornId mustBe Some("STORN12345")
      result.returnResourceRef mustBe Some("100001")
      result.sdltOrganisation mustBe None
      result.returnInfo mustBe None
      result.purchaser mustBe None
      result.vendor mustBe None
      result.transaction mustBe None

      verify(cs).execute()
    }

    "handle empty result sets" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      (3 to 16).foreach { pos =>
        val rs = mock[ResultSet]
        when(cs.getObject(eqTo(pos), eqTo(classOf[ResultSet]))).thenReturn(rs)
        when(rs.next()).thenReturn(false)
      }

      val repo = new SdltFormpRepository(db)

      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      result.stornId mustBe Some("STORN12345")
      result.returnResourceRef mustBe Some("100001")
      result.sdltOrganisation mustBe None
      result.purchaser mustBe None
      result.vendor mustBe None

      verify(cs).execute()
    }

    "process multiple purchasers correctly" in {
      val db          = mock[Database]
      val conn        = mock[Connection]
      val cs          = mock[CallableStatement]
      val rsPurchaser = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      when(cs.getObject(eqTo(5), eqTo(classOf[ResultSet]))).thenReturn(rsPurchaser)
      when(rsPurchaser.next()).thenReturn(true, true, false)
      when(rsPurchaser.getString("PURCHASER_ID")).thenReturn("1", "2")
      when(rsPurchaser.getString("SURNAME")).thenReturn("Smith", "Jones")

      (3 to 16).foreach { pos =>
        if (pos != 5) {
          when(cs.getObject(eqTo(pos), eqTo(classOf[ResultSet]))).thenReturn(null)
        }
      }

      val repo = new SdltFormpRepository(db)

      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      result.purchaser     must not be None
      result.purchaser.get must have size 2
      result.purchaser.get.head.surname mustBe Some("Smith")
      result.purchaser.get.last.surname mustBe Some("Jones")
    }

    "convert returnResourceRef string to Long correctly" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getObject(anyArg[Int], eqTo(classOf[ResultSet]))).thenReturn(null)

      val repo = new SdltFormpRepository(db)

      repo.sdltGetReturn("999999", "STORN12345").futureValue

      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 999999L)
    }

    "close all result sets properly" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val resultSets = (3 to 16).map { pos =>
        val rs = mock[ResultSet]
        when(cs.getObject(eqTo(pos), eqTo(classOf[ResultSet]))).thenReturn(rs)
        when(rs.next()).thenReturn(false)
        rs
      }

      val repo = new SdltFormpRepository(db)

      repo.sdltGetReturn("100001", "STORN12345").futureValue

      resultSets.foreach { rs =>
        verify(rs).close()
      }
      verify(cs).close()
    }

    "handle company purchaser with company details" in {
      val db          = mock[Database]
      val conn        = mock[Connection]
      val cs          = mock[CallableStatement]
      val rsPurchaser = mock[ResultSet]
      val rsCompany   = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      when(cs.getObject(eqTo(5), eqTo(classOf[ResultSet]))).thenReturn(rsPurchaser)
      when(rsPurchaser.next()).thenReturn(true, false)
      when(rsPurchaser.getString("PURCHASER_ID")).thenReturn("1")
      when(rsPurchaser.getString("IS_COMPANY")).thenReturn("YES")
      when(rsPurchaser.getString("COMPANY_NAME")).thenReturn("ABC Property Ltd")

      when(cs.getObject(eqTo(6), eqTo(classOf[ResultSet]))).thenReturn(rsCompany)
      when(rsCompany.next()).thenReturn(true, false)
      when(rsCompany.getString("COMPANY_DETAILS_ID")).thenReturn("1")
      when(rsCompany.getString("UTR")).thenReturn("1234567890")
      when(rsCompany.getString("COMPANY_TYPE_OTHERCOMPANY")).thenReturn("YES")

      (3 to 16).foreach { pos =>
        if (pos != 5 && pos != 6) {
          when(cs.getObject(eqTo(pos), eqTo(classOf[ResultSet]))).thenReturn(null)
        }
      }

      val repo = new SdltFormpRepository(db)

      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      result.purchaser      must not be None
      result.purchaser.get.head.isCompany mustBe Some("YES")
      result.purchaser.get.head.companyName mustBe Some("ABC Property Ltd")
      result.companyDetails must not be None
      result.companyDetails.get.UTR mustBe Some("1234567890")
    }

    "process ReturnAgent data correctly" in {
      val db         = mock[Database]
      val conn       = mock[Connection]
      val cs         = mock[CallableStatement]
      val rsRetAgent = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      when(cs.getObject(eqTo(10), eqTo(classOf[ResultSet]))).thenReturn(rsRetAgent)
      when(rsRetAgent.next()).thenReturn(true, false)
      when(rsRetAgent.getString("RETURN_AGENT_ID")).thenReturn("1")
      when(rsRetAgent.getString("RETURN_ID")).thenReturn("100001")
      when(rsRetAgent.getString("AGENT_TYPE")).thenReturn("SOLICITOR")
      when(rsRetAgent.getString("NAME")).thenReturn("Legal Partners LLP")
      when(rsRetAgent.getString("HOUSE_NUMBER")).thenReturn("10")
      when(rsRetAgent.getString("ADDRESS_1")).thenReturn("Law Street")
      when(rsRetAgent.getString("ADDRESS_2")).thenReturn("Legal Quarter")
      when(rsRetAgent.getString("ADDRESS_3")).thenReturn("London")
      when(rsRetAgent.getString("ADDRESS_4")).thenReturn(null)
      when(rsRetAgent.getString("POSTCODE")).thenReturn("LE1 1AW")
      when(rsRetAgent.getString("PHONE")).thenReturn("02012345678")
      when(rsRetAgent.getString("EMAIL")).thenReturn("agent@legal.com")
      when(rsRetAgent.getString("DX_ADDRESS")).thenReturn("DX 12345")
      when(rsRetAgent.getString("REFERENCE")).thenReturn("REF123")
      when(rsRetAgent.getString("IS_AUTHORISED")).thenReturn("YES")

      (3 to 16).foreach { pos =>
        if (pos != 10) {
          when(cs.getObject(eqTo(pos), eqTo(classOf[ResultSet]))).thenReturn(null)
        }
      }

      val repo = new SdltFormpRepository(db)

      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      result.returnAgent     must not be None
      result.returnAgent.get must have size 1
      val agent = result.returnAgent.get.head
      agent.returnAgentID mustBe Some("1")
      agent.agentType mustBe Some("SOLICITOR")
      agent.name mustBe Some("Legal Partners LLP")
      agent.houseNumber mustBe Some("10")
      agent.address1 mustBe Some("Law Street")
      agent.postcode mustBe Some("LE1 1AW")
      agent.email mustBe Some("agent@legal.com")
      agent.isAuthorised mustBe Some("YES")
    }

    "process multiple ReturnAgents correctly" in {
      val db         = mock[Database]
      val conn       = mock[Connection]
      val cs         = mock[CallableStatement]
      val rsRetAgent = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      when(cs.getObject(eqTo(10), eqTo(classOf[ResultSet]))).thenReturn(rsRetAgent)
      when(rsRetAgent.next()).thenReturn(true, true, false)
      when(rsRetAgent.getString("RETURN_AGENT_ID")).thenReturn("1", "2")
      when(rsRetAgent.getString("NAME")).thenReturn("Agent One", "Agent Two")

      (3 to 16).foreach { pos =>
        if (pos != 10) {
          when(cs.getObject(eqTo(pos), eqTo(classOf[ResultSet]))).thenReturn(null)
        }
      }

      val repo = new SdltFormpRepository(db)

      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      result.returnAgent     must not be None
      result.returnAgent.get must have size 2
      result.returnAgent.get.head.name mustBe Some("Agent One")
      result.returnAgent.get.last.name mustBe Some("Agent Two")
    }

    "process Agent data correctly" in {
      val db      = mock[Database]
      val conn    = mock[Connection]
      val cs      = mock[CallableStatement]
      val rsAgent = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      when(cs.getObject(eqTo(11), eqTo(classOf[ResultSet]))).thenReturn(rsAgent)
      when(rsAgent.next()).thenReturn(true, false)
      when(rsAgent.getString("AGENT_ID")).thenReturn("1")
      when(rsAgent.getString("STORN")).thenReturn("STORN12345")
      when(rsAgent.getString("NAME")).thenReturn("Agent Name")
      when(rsAgent.getString("HOUSE_NUMBER")).thenReturn("100")
      when(rsAgent.getString("ADDRESS_1")).thenReturn("Agent Street")
      when(rsAgent.getString("ADDRESS_2")).thenReturn("Agent Town")
      when(rsAgent.getString("ADDRESS_3")).thenReturn(null)
      when(rsAgent.getString("ADDRESS_4")).thenReturn(null)
      when(rsAgent.getString("POSTCODE")).thenReturn("AG1 1NT")
      when(rsAgent.getString("PHONE")).thenReturn("01234567890")
      when(rsAgent.getString("EMAIL")).thenReturn("agent@test.com")
      when(rsAgent.getString("DX_ADDRESS")).thenReturn("DX123")
      when(rsAgent.getString("AGENT_RESOURCE_REF")).thenReturn("AGENTREF1")

      (3 to 16).foreach { pos =>
        if (pos != 11) {
          when(cs.getObject(eqTo(pos), eqTo(classOf[ResultSet]))).thenReturn(null)
        }
      }

      val repo = new SdltFormpRepository(db)

      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      // Updated to work with Seq
      result.agent mustBe defined
      result.agent.get must have size 1

      val agent = result.agent.get.head
      agent.agentId mustBe Some("1")
      agent.storn mustBe Some("STORN12345")
      agent.name mustBe Some("Agent Name")
      agent.houseNumber mustBe Some("100")
      agent.address1 mustBe Some("Agent Street")
      agent.postcode mustBe Some("AG1 1NT")
      agent.email mustBe Some("agent@test.com")
      agent.agentResourceReference mustBe Some("AGENTREF1")
    }

    "process Lease data correctly with all fields" in {
      val db      = mock[Database]
      val conn    = mock[Connection]
      val cs      = mock[CallableStatement]
      val rsLease = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      when(cs.getObject(eqTo(12), eqTo(classOf[ResultSet]))).thenReturn(rsLease)
      when(rsLease.next()).thenReturn(true, false)
      when(rsLease.getString("LEASE_ID")).thenReturn("1")
      when(rsLease.getString("RETURN_ID")).thenReturn("100001")
      when(rsLease.getString("IS_ANNUAL_RENT_OVER_1000")).thenReturn("YES")
      when(rsLease.getString("BREAK_CLAUSE_TYPE")).thenReturn("TENANT")
      when(rsLease.getString("CONTRACT_START_DATE")).thenReturn("2025-01-01")
      when(rsLease.getString("CONTRACT_END_DATE")).thenReturn("2030-12-31")
      when(rsLease.getString("LEASE_TYPE")).thenReturn("NEW")
      when(rsLease.getString("MARKET_RENT")).thenReturn("15000")
      when(rsLease.getString("NET_PRESENT_VALUE")).thenReturn("50000")
      when(rsLease.getString("STARTING_RENT")).thenReturn("12000")
      when(rsLease.getString("SERVICE_CHARGE")).thenReturn("2000")
      when(rsLease.getString("VAT_AMOUNT")).thenReturn("2400")

      (3 to 16).foreach { pos =>
        if (pos != 12) {
          when(cs.getObject(eqTo(pos), eqTo(classOf[ResultSet]))).thenReturn(null)
        }
      }

      val repo = new SdltFormpRepository(db)

      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      result.lease must not be None
      val lease = result.lease.get
      lease.leaseID mustBe Some("1")
      lease.returnID mustBe Some("100001")
      lease.isAnnualRentOver1000 mustBe Some("YES")
      lease.breakClauseType mustBe Some("TENANT")
      lease.contractStartDate mustBe Some("2025-01-01")
      lease.contractEndDate mustBe Some("2030-12-31")
      lease.leaseType mustBe Some("NEW")
      lease.marketRent mustBe Some("15000")
      lease.netPresentValue mustBe Some("50000")
      lease.startingRent mustBe Some("12000")
      lease.serviceCharge mustBe Some("2000")
      lease.VATAmount mustBe Some("2400")
    }

    "process SubmissionErrorDetails correctly (single row)" in {
      val db       = mock[Database]
      val conn     = mock[Connection]
      val cs       = mock[CallableStatement]
      val rsSubErr = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }
      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      when(cs.getObject(eqTo(15), eqTo(classOf[ResultSet]))).thenReturn(rsSubErr)
      (3 to 16).foreach { pos =>
        if (pos != 15) when(cs.getObject(eqTo(pos), eqTo(classOf[ResultSet]))).thenReturn(null)
      }

      when(rsSubErr.next()).thenReturn(true, false)
      when(rsSubErr.getString("ERROR_DETAIL_ID")).thenReturn("E1")
      when(rsSubErr.getString("RETURN_ID")).thenReturn("100001")
      when(rsSubErr.getString("POSITION")).thenReturn("FIELD:postcode")
      when(rsSubErr.getString("ERROR_MESSAGE")).thenReturn("Invalid postcode format")
      when(rsSubErr.getString("STORN")).thenReturn("STORN12345")
      when(rsSubErr.getString("SUBMISSION_ID")).thenReturn("SUB123")

      val repo   = new SdltFormpRepository(db)
      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      result.submissionErrorDetails mustBe defined
      val e = result.submissionErrorDetails.value
      e.errorDetailID mustBe Some("E1")
      e.returnID mustBe Some("100001")
      e.position mustBe Some("FIELD:postcode")
      e.errorMessage mustBe Some("Invalid postcode format")
      e.storn mustBe Some("STORN12345")
      e.submissionID mustBe Some("SUB123")
    }

    "process SubmissionErrorDetails when multiple rows are returned (uses first row)" in {
      val db       = mock[Database]
      val conn     = mock[Connection]
      val cs       = mock[CallableStatement]
      val rsSubErr = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }
      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      when(cs.getObject(eqTo(15), eqTo(classOf[ResultSet]))).thenReturn(rsSubErr)
      (3 to 16).foreach { pos =>
        if (pos != 15) when(cs.getObject(eqTo(pos), eqTo(classOf[ResultSet]))).thenReturn(null)
      }

      when(rsSubErr.next()).thenReturn(true, true, false)
      when(rsSubErr.getString("ERROR_DETAIL_ID")).thenReturn("E1", "E2")
      when(rsSubErr.getString("RETURN_ID")).thenReturn("100001", "100001")
      when(rsSubErr.getString("POSITION")).thenReturn("FIELD:surname", "FIELD:address1")
      when(rsSubErr.getString("ERROR_MESSAGE")).thenReturn("Surname missing", "Address line 1 required")
      when(rsSubErr.getString("STORN")).thenReturn("STORN12345", "STORN12345")
      when(rsSubErr.getString("SUBMISSION_ID")).thenReturn("SUB123", "SUB123")

      val repo   = new SdltFormpRepository(db)
      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      result.submissionErrorDetails mustBe defined
      val e = result.submissionErrorDetails.value
      e.errorDetailID mustBe Some("E1")
      e.position mustBe Some("FIELD:surname")
      e.errorMessage mustBe Some("Surname missing")
    }

    "map null fields in SubmissionErrorDetails to None" in {
      val db       = mock[Database]
      val conn     = mock[Connection]
      val cs       = mock[CallableStatement]
      val rsSubErr = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }
      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      when(cs.getObject(eqTo(15), eqTo(classOf[ResultSet]))).thenReturn(rsSubErr)
      (3 to 16).foreach { pos =>
        if (pos != 15) when(cs.getObject(eqTo(pos), eqTo(classOf[ResultSet]))).thenReturn(null)
      }

      when(rsSubErr.next()).thenReturn(true, false)
      when(rsSubErr.getString("ERROR_DETAIL_ID")).thenReturn(null)
      when(rsSubErr.getString("RETURN_ID")).thenReturn("100001")
      when(rsSubErr.getString("POSITION")).thenReturn(null)
      when(rsSubErr.getString("ERROR_MESSAGE")).thenReturn(null)
      when(rsSubErr.getString("STORN")).thenReturn("STORN12345")
      when(rsSubErr.getString("SUBMISSION_ID")).thenReturn(null)

      val repo   = new SdltFormpRepository(db)
      val result = repo.sdltGetReturn("100001", "STORN12345").futureValue

      result.submissionErrorDetails mustBe defined
      val e = result.submissionErrorDetails.value
      e.errorDetailID mustBe None
      e.returnID mustBe Some("100001")
      e.position mustBe None
      e.errorMessage mustBe None
      e.storn mustBe Some("STORN12345")
      e.submissionID mustBe None
    }

  }

  "sdltGetReturns" - {
    "call::query_return - return 2 rows :: success" in new ReturnsFixture {

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]);
        f(conn)
      }

      when(
        conn.prepareCall(
          eqTo("{ call RETURN_PROCS.query_return(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
        )
      ).thenReturn(cs)

      when(cs.getLong(eqTo(13))).thenReturn(1017L)
      when(cs.getObject(eqTo(12), eqTo(classOf[ResultSet]))).thenReturn(resRetSummary)

      // Fetch data
      when(resRetSummary.next()).thenReturn(true, true, false) // read 2 rows
      when(resRetSummary.getString("return_resource_ref")).thenReturn("REF01", "REF02")
      when(resRetSummary.getString("utrn")).thenReturn("UTR001", "UTR003")
      when(resRetSummary.getString("status")).thenReturn("SUBMITTED", "SUBMITTED")
      when(resRetSummary.getString("submitted_date")).thenReturn("2025-01-01", "2025-02-03")

      when(resRetSummary.getString("name")).thenReturn("purchaserName1", "purchaserName2")

      when(resRetSummary.getString("address")).thenReturn("Address 11", "Address 22")
      when(resRetSummary.getString("agent")).thenReturn("Agent 11", "Agent 22")

      val repo = new SdltFormpRepository(db)

      val result = repo.sdltGetReturns(requestReturns).futureValue

      result.returnSummaryCount mustBe Some(1017)
      result.returnSummaryList.length mustBe 2

      result.returnSummaryList mustBe expectedReturnsSummary

      verify(conn).prepareCall(
        eqTo("{ call RETURN_PROCS.query_return(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
      )
      verify(cs).getLong(13)
      verify(cs).execute()
      verify(cs).close()
    }
    "call::query_return - return empty result :: success" in new ReturnsFixture {
      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]);
        f(conn)
      }

      when(
        conn.prepareCall(
          eqTo("{ call RETURN_PROCS.query_return(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
        )
      ).thenReturn(cs)

      when(cs.getLong(eqTo(13))).thenReturn(0L)
      when(cs.getObject(eqTo(12), eqTo(classOf[ResultSet]))).thenReturn(resRetSummary)

      // Fetch data
      when(resRetSummary.next()).thenReturn(false) // read 2 rows
      val repo = new SdltFormpRepository(db)

      val result: SdltReturnRecordResponse = repo.sdltGetReturns(requestReturns).futureValue
      result.returnSummaryCount mustBe Some(0)
      result.returnSummaryList.length mustBe 0
      result.returnSummaryList mustBe expectedReturnsSummaryEmpty
    }
    "call::query_return - generates correct sorting parameters" in new ReturnsFixture {
      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]);
        f(conn)
      }

      when(
        conn.prepareCall(
          eqTo("{ call RETURN_PROCS.query_return(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
        )
      ).thenReturn(cs)

      when(cs.getLong(eqTo(13))).thenReturn(1017L)
      when(cs.getObject(eqTo(12), eqTo(classOf[ResultSet]))).thenReturn(resRetSummary)

      // Fetch data
      when(resRetSummary.next()).thenReturn(true, true, false)
      when(resRetSummary.getString("return_resource_ref")).thenReturn("REF01", "REF02")
      when(resRetSummary.getString("utrn")).thenReturn("UTR001", "UTR003")
      when(resRetSummary.getString("status")).thenReturn("SUBMITTED", "SUBMITTED")
      when(resRetSummary.getString("submitted_date")).thenReturn("2025-01-01", "2025-02-03")

      when(resRetSummary.getString("name")).thenReturn("purchaserName1", "purchaserName2")

      when(resRetSummary.getString("address")).thenReturn("Address 11", "Address 22")
      when(resRetSummary.getString("agent")).thenReturn("Agent 11", "Agent 22")

      val repo                             = new SdltFormpRepository(db)
      val result: SdltReturnRecordResponse = repo.sdltGetReturns(requestReturns).futureValue

      verify(cs).setString(8, "submitted_date")
      verify(cs).setString(9, "DESC")
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltGetReturnsForPurge" - {
    "call get_returns_for_purge and map each cursor row to a ReturnForPurge" in new ReturnsFixture {

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]);
        f(conn)
      }

      when(
        conn.prepareCall(
          eqTo("{ call RETURN_PROCS.get_returns_for_purge(?, ?) }")
        )
      ).thenReturn(cs)

      when(cs.getObject(eqTo(2), eqTo(classOf[ResultSet]))).thenReturn(resRetSummary)

      when(resRetSummary.next()).thenReturn(true, true, false)
      when(resRetSummary.getString("storn")).thenReturn("STORN12345", "STORN12345")
      when(resRetSummary.getString("return_resource_ref")).thenReturn("REF01", "REF02")
      when(resRetSummary.getString("status")).thenReturn("SUBMITTED", "STARTED")

      val repo = new SdltFormpRepository(db)

      val result = repo.sdltGetReturnsForPurge(requestReturnsForPurge).futureValue

      result.returnsForPurge.length mustBe 2
      result.returnsForPurge mustBe expectedReturnsForPurge

      verify(conn).prepareCall(
        eqTo("{ call RETURN_PROCS.get_returns_for_purge(?, ?) }")
      )
      verify(cs).setDate(1, Date.valueOf(requestReturnsForPurge.purgeDate))
      verify(cs).execute()
      verify(cs).close()
    }

    "call get_returns_for_purge and return an empty list when no returns are due for purge" in new ReturnsFixture {

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]);
        f(conn)
      }

      when(
        conn.prepareCall(
          eqTo("{ call RETURN_PROCS.get_returns_for_purge(?, ?) }")
        )
      ).thenReturn(cs)

      when(cs.getObject(eqTo(2), eqTo(classOf[ResultSet]))).thenReturn(resRetSummary)

      when(resRetSummary.next()).thenReturn(false)

      val repo = new SdltFormpRepository(db)

      val result: ReturnsForPurgeResponse = repo.sdltGetReturnsForPurge(requestReturnsForPurge).futureValue
      result.returnsForPurge.length mustBe 0
      result.returnsForPurge mustBe expectedReturnsForPurgeEmpty
    }
  }

  "sdltDeleteReturn" - {

    "call delete_return stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call RETURN_PROCS.delete_return(?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteReturnRequest(
        storn = "STORN12345",
        returnResourceRef = "100001"
      )

      val result = repo.sdltDeleteReturn(request).futureValue

      result.deleted mustBe true

      verify(conn).prepareCall("{ call RETURN_PROCS.delete_return(?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltCreateVendor" - {

    "call Create_Vendor stored procedure with correct parameters and return vendor IDs" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call VENDOR_PROCS.Create_Vendor(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")))
        .thenReturn(cs)
      when(cs.getLong(14)).thenReturn(100001L)
      when(cs.getLong(15)).thenReturn(1L)

      val repo = new SdltFormpRepository(db)

      val request = CreateVendorRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        title = Some("Mr"),
        forename1 = Some("John"),
        forename2 = Some("James"),
        name = "Smith",
        houseNumber = Some("123"),
        addressLine1 = "Main Street",
        addressLine2 = Some("Apartment 4B"),
        addressLine3 = Some("City Center"),
        addressLine4 = Some("Greater London"),
        postcode = Some("SW1A 1AA"),
        isRepresentedByAgent = "NO"
      )

      val result = repo.sdltCreateVendor(request).futureValue

      result.vendorResourceRef mustBe "100001"
      result.vendorId mustBe "1"

      verify(conn).prepareCall("{ call VENDOR_PROCS.Create_Vendor(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "Mr")
      verify(cs).setString(4, "John")
      verify(cs).setString(5, "James")
      verify(cs).setString(6, "Smith")
      verify(cs).setString(7, "123")
      verify(cs).setString(8, "Main Street")
      verify(cs).setString(9, "Apartment 4B")
      verify(cs).setString(10, "City Center")
      verify(cs).setString(11, "Greater London")
      verify(cs).setString(12, "SW1A 1AA")
      verify(cs).setString(13, "NO")
      verify(cs).registerOutParameter(14, Types.NUMERIC)
      verify(cs).registerOutParameter(15, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle optional fields being None" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getLong(14)).thenReturn(100002L)
      when(cs.getLong(15)).thenReturn(2L)

      val repo = new SdltFormpRepository(db)

      val request = CreateVendorRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        title = None,
        forename1 = None,
        forename2 = None,
        name = "Company Vendor Ltd",
        houseNumber = None,
        addressLine1 = "Business Park",
        addressLine2 = None,
        addressLine3 = None,
        addressLine4 = None,
        postcode = None,
        isRepresentedByAgent = "YES"
      )

      val result = repo.sdltCreateVendor(request).futureValue

      result.vendorResourceRef mustBe "100002"
      result.vendorId mustBe "2"

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setNull(3, Types.VARCHAR)
      verify(cs).setNull(4, Types.VARCHAR)
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setString(6, "Company Vendor Ltd")
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setString(8, "Business Park")
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setString(13, "YES")
      verify(cs).execute()
    }
  }

  "sdltUpdateVendor" - {

    "call Update_Vendor stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call VENDOR_PROCS.Update_Vendor(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")))
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateVendorRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        title = Some("Mrs"),
        forename1 = Some("Jane"),
        forename2 = None,
        name = "Doe",
        houseNumber = Some("456"),
        addressLine1 = "Oak Avenue",
        addressLine2 = Some("Suite 10"),
        addressLine3 = None,
        addressLine4 = None,
        postcode = Some("W1A 1AA"),
        isRepresentedByAgent = "YES",
        vendorResourceRef = "100001",
        nextVendorId = Some("100002")
      )

      val result = repo.sdltUpdateVendor(request).futureValue

      result.updated mustBe true

      verify(conn).prepareCall("{ call VENDOR_PROCS.Update_Vendor(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "Mrs")
      verify(cs).setString(4, "Jane")
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setString(6, "Doe")
      verify(cs).setString(7, "456")
      verify(cs).setString(8, "Oak Avenue")
      verify(cs).setString(9, "Suite 10")
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setString(12, "W1A 1AA")
      verify(cs).setString(13, "YES")
      verify(cs).setLong(14, 100001L)
      verify(cs).setString(15, "100002")
      verify(cs).execute()
      verify(cs).close()
    }

    "handle minimal update with no optional fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateVendorRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        title = None,
        forename1 = None,
        forename2 = None,
        name = "Updated Vendor Ltd",
        houseNumber = None,
        addressLine1 = "New Business Park",
        addressLine2 = None,
        addressLine3 = None,
        addressLine4 = None,
        postcode = None,
        isRepresentedByAgent = "NO",
        vendorResourceRef = "100002",
        nextVendorId = None
      )

      val result = repo.sdltUpdateVendor(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setNull(3, Types.VARCHAR)
      verify(cs).setNull(4, Types.VARCHAR)
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setString(6, "Updated Vendor Ltd")
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setString(8, "New Business Park")
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setString(13, "NO")
      verify(cs).setLong(14, 100002L)
      verify(cs).setNull(15, Types.VARCHAR)
      verify(cs).execute()
    }
  }

  "sdltDeleteVendor" - {

    "call Delete_Vendor stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call VENDOR_PROCS.Delete_Vendor(?, ?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteVendorRequest(
        storn = "STORN12345",
        vendorResourceRef = "100001",
        returnResourceRef = "100001"
      )

      val result = repo.sdltDeleteVendor(request).futureValue

      result.deleted mustBe true

      verify(conn).prepareCall("{ call VENDOR_PROCS.Delete_Vendor(?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setLong(3, 100001L)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle different vendor resource references" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteVendorRequest(
        storn = "STORN99999",
        vendorResourceRef = "999999",
        returnResourceRef = "100002"
      )

      val result = repo.sdltDeleteVendor(request).futureValue

      result.deleted mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setLong(3, 999999L)
      verify(cs).execute()
    }
  }

  "sdltCreateReturnAgent" - {

    "call Create_Return_Agent stored procedure with correct parameters and return agent ID" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(
          eqTo("{ call RETURN_AGENT_PROCS.Create_Return_Agent(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
        )
      )
        .thenReturn(cs)
      when(cs.getLong(16)).thenReturn(100001L)

      val repo = new SdltFormpRepository(db)

      val request = CreateReturnAgentRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        agentType = "SOLICITOR",
        name = "Smith & Partners LLP",
        houseNumber = Some("10"),
        addressLine1 = "Legal District",
        addressLine2 = Some("Business Quarter"),
        addressLine3 = Some("Manchester"),
        addressLine4 = Some("Greater Manchester"),
        postcode = "M1 1AA",
        phoneNumber = Some("0161234567"),
        email = Some("agent@smithpartners.com"),
        agentReference = Some("AGT123456"),
        isAuthorised = Some("YES")
      )

      val result = repo.sdltCreateReturnAgent(request).futureValue

      result.returnAgentID mustBe "100001"

      verify(conn).prepareCall(
        "{ call RETURN_AGENT_PROCS.Create_Return_Agent(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
      )
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "SOLICITOR")
      verify(cs).setString(4, "Smith & Partners LLP")
      verify(cs).setString(5, "10")
      verify(cs).setString(6, "Legal District")
      verify(cs).setString(7, "Business Quarter")
      verify(cs).setString(8, "Manchester")
      verify(cs).setString(9, "Greater Manchester")
      verify(cs).setString(10, "M1 1AA")
      verify(cs).setString(11, "0161234567")
      verify(cs).setString(12, "agent@smithpartners.com")
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).setString(14, "AGT123456")
      verify(cs).setString(15, "YES")
      verify(cs).registerOutParameter(16, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle optional fields being None" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getLong(16)).thenReturn(100002L)

      val repo = new SdltFormpRepository(db)

      val request = CreateReturnAgentRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        agentType = "ACCOUNTANT",
        name = "Quick Accounting",
        houseNumber = None,
        addressLine1 = "High Street",
        addressLine2 = None,
        addressLine3 = None,
        addressLine4 = None,
        postcode = "EC1A 1BB",
        phoneNumber = None,
        email = None,
        agentReference = None,
        isAuthorised = None
      )

      val result = repo.sdltCreateReturnAgent(request).futureValue

      result.returnAgentID mustBe "100002"

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setString(3, "ACCOUNTANT")
      verify(cs).setString(4, "Quick Accounting")
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setString(6, "High Street")
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setString(10, "EC1A 1BB")
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).setNull(14, Types.VARCHAR)
      verify(cs).setNull(15, Types.VARCHAR)
      verify(cs).execute()
    }
  }

  "sdltUpdateReturnAgent" - {

    "call UPDATE_RETURN_AGENT stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(
          eqTo("{ call RETURN_AGENT_PROCS.UPDATE_RETURN_AGENT(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
        )
      )
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateReturnAgentRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        agentType = "SOLICITOR",
        name = "Updated Smith & Partners LLP",
        houseNumber = Some("20"),
        addressLine1 = "New Legal District",
        addressLine2 = Some("Updated Quarter"),
        addressLine3 = Some("Manchester"),
        addressLine4 = None,
        postcode = "M2 2BB",
        phoneNumber = Some("0161999888"),
        email = Some("updated@smithpartners.com"),
        agentReference = Some("AGT999999"),
        isAuthorised = Some("YES")
      )

      val result = repo.sdltUpdateReturnAgent(request).futureValue

      result.updated mustBe true

      verify(conn).prepareCall(
        "{ call RETURN_AGENT_PROCS.UPDATE_RETURN_AGENT(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
      )
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "SOLICITOR")
      verify(cs).setString(4, "Updated Smith & Partners LLP")
      verify(cs).setString(5, "20")
      verify(cs).setString(6, "New Legal District")
      verify(cs).setString(7, "Updated Quarter")
      verify(cs).setString(8, "Manchester")
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setString(10, "M2 2BB")
      verify(cs).setString(11, "0161999888")
      verify(cs).setString(12, "updated@smithpartners.com")
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).setString(14, "AGT999999")
      verify(cs).setString(15, "YES")
      verify(cs).execute()
      verify(cs).close()
    }

    "handle minimal update with no optional fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateReturnAgentRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        agentType = "ACCOUNTANT",
        name = "Updated Accounting",
        houseNumber = None,
        addressLine1 = "New High Street",
        addressLine2 = None,
        addressLine3 = None,
        addressLine4 = None,
        postcode = "EC2A 2BB",
        phoneNumber = None,
        email = None,
        agentReference = None,
        isAuthorised = None
      )

      val result = repo.sdltUpdateReturnAgent(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setString(3, "ACCOUNTANT")
      verify(cs).setString(4, "Updated Accounting")
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setString(6, "New High Street")
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setString(10, "EC2A 2BB")
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).setNull(14, Types.VARCHAR)
      verify(cs).setNull(15, Types.VARCHAR)
      verify(cs).execute()
    }
  }

  "sdltDeleteReturnAgent" - {

    "call Delete_Return_Agent stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call RETURN_AGENT_PROCS.Delete_Return_Agent(?, ?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteReturnAgentRequest(
        storn = "STORN12345",
        returnResourceRef = "100001",
        agentType = "SOLICITOR"
      )

      val result = repo.sdltDeleteReturnAgent(request).futureValue

      result.deleted mustBe true

      verify(conn).prepareCall("{ call RETURN_AGENT_PROCS.Delete_Return_Agent(?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "SOLICITOR")
      verify(cs).execute()
      verify(cs).close()
    }

    "handle different agent types" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteReturnAgentRequest(
        storn = "STORN99999",
        returnResourceRef = "100002",
        agentType = "ACCOUNTANT"
      )

      val result = repo.sdltDeleteReturnAgent(request).futureValue

      result.deleted mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setString(3, "ACCOUNTANT")
      verify(cs).execute()
    }
  }

  "sdltUpdateReturnVersion" - {

    "call Update_Version_Number stored procedure with correct parameters and return new version" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call RETURN_PROCS.Update_Version_Number(?, ?, ?) }"))).thenReturn(cs)
      when(cs.getInt(3)).thenReturn(2)

      val repo = new SdltFormpRepository(db)

      val request = ReturnVersionUpdateRequest(
        storn = "STORN12345",
        returnResourceRef = "100001",
        currentVersion = "1"
      )

      val result = repo.sdltUpdateReturnVersion(request).futureValue

      result.newVersion mustBe 2

      verify(conn).prepareCall("{ call RETURN_PROCS.Update_Version_Number(?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setLong(3, 1L)
      verify(cs).registerOutParameter(3, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle version 0 to version 1" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getInt(3)).thenReturn(1)

      val repo = new SdltFormpRepository(db)

      val request = ReturnVersionUpdateRequest(
        storn = "STORN11111",
        returnResourceRef = "100003",
        currentVersion = "0"
      )

      val result = repo.sdltUpdateReturnVersion(request).futureValue

      result.newVersion mustBe 1

      verify(cs).setString(1, "STORN11111")
      verify(cs).setLong(2, 100003L)
      verify(cs).setLong(3, 0L)
      verify(cs).execute()
    }

    "handle higher version numbers" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getInt(3)).thenReturn(6)

      val repo = new SdltFormpRepository(db)

      val request = ReturnVersionUpdateRequest(
        storn = "STORN99999",
        returnResourceRef = "100002",
        currentVersion = "5"
      )

      val result = repo.sdltUpdateReturnVersion(request).futureValue

      result.newVersion mustBe 6

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setLong(3, 5L)
      verify(cs).registerOutParameter(3, Types.NUMERIC)
      verify(cs).execute()
    }

    "handle very high version numbers" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getInt(3)).thenReturn(11)

      val repo = new SdltFormpRepository(db)

      val request = ReturnVersionUpdateRequest(
        storn = "STORN77777",
        returnResourceRef = "999999",
        currentVersion = "10"
      )

      val result = repo.sdltUpdateReturnVersion(request).futureValue

      result.newVersion mustBe 11

      verify(cs).setString(1, "STORN77777")
      verify(cs).setLong(2, 999999L)
      verify(cs).setLong(3, 10L)
      verify(cs).execute()
    }
  }

  "sdltGetOrganisation" - {

    "call Get_SDLT_Organisation stored procedure and map organisation and agents correctly" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      val rsOrg    = mock[ResultSet]
      val rsAgents = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SDLT_ORGANISATION_PROCS.Get_SDLT_Organisation(?, ?, ?) }")))
        .thenReturn(cs)

      when(cs.getObject(eqTo(2), eqTo(classOf[ResultSet]))).thenReturn(rsOrg)
      when(cs.getObject(eqTo(3), eqTo(classOf[ResultSet]))).thenReturn(rsAgents)

      when(rsOrg.next()).thenReturn(true, false)
      when(rsOrg.getString("IS_RETURN_USER")).thenReturn("YES")
      when(rsOrg.getString("DO_NOT_DISPLAY_WELCOME_PAGE")).thenReturn("NO")
      when(rsOrg.getString("STORN")).thenReturn("STORN12345")
      when(rsOrg.getString("VERSION")).thenReturn("1")

      when(rsAgents.next()).thenReturn(true, false)
      when(rsAgents.getString("AGENT_ID")).thenReturn("AGT001")
      when(rsAgents.getString("STORN")).thenReturn("STORN12345")
      when(rsAgents.getString("NAME")).thenReturn("Smith & Co Solicitors")
      when(rsAgents.getString("HOUSE_NUMBER")).thenReturn("10")
      when(rsAgents.getString("ADDRESS_1")).thenReturn("Downing Street")
      when(rsAgents.getString("ADDRESS_2")).thenReturn("Westminster")
      when(rsAgents.getString("ADDRESS_3")).thenReturn("London")
      when(rsAgents.getString("ADDRESS_4")).thenReturn(null)
      when(rsAgents.getString("POSTCODE")).thenReturn("SW1A 2AA")
      when(rsAgents.getString("PHONE")).thenReturn("02071234567")
      when(rsAgents.getString("EMAIL")).thenReturn("info@smithco.co.uk")
      when(rsAgents.getString("DX_ADDRESS")).thenReturn("DX 12345 London")
      when(rsAgents.getString("AGENT_RESOURCE_REF")).thenReturn("AGT-RES-001")

      val repo   = new SdltFormpRepository(db)
      val result = repo.sdltGetOrganisation("STORN12345").futureValue

      result.storn mustBe Some("STORN12345")
      result.version mustBe Some("1")
      result.isReturnUser mustBe Some("YES")
      result.doNotDisplayWelcomePage mustBe Some("NO")

      result.agents must have size 1
      val agent = result.agents.head
      agent.agentId mustBe Some("AGT001")
      agent.storn mustBe Some("STORN12345")
      agent.name mustBe Some("Smith & Co Solicitors")
      agent.houseNumber mustBe Some("10")
      agent.address1 mustBe Some("Downing Street")
      agent.address2 mustBe Some("Westminster")
      agent.address3 mustBe Some("London")
      agent.address4 mustBe None
      agent.postcode mustBe Some("SW1A 2AA")
      agent.phone mustBe Some("02071234567")
      agent.email mustBe Some("info@smithco.co.uk")
      agent.dxAddress mustBe Some("DX 12345 London")
      agent.agentResourceReference mustBe Some("AGT-RES-001")

      verify(conn).prepareCall("{ call SDLT_ORGANISATION_PROCS.Get_SDLT_Organisation(?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).execute()
      verify(cs).close()
      verify(rsOrg).close()
      verify(rsAgents).close()
    }

    "handle null cursors gracefully" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getObject(anyArg[Int], eqTo(classOf[ResultSet]))).thenReturn(null)

      val repo   = new SdltFormpRepository(db)
      val result = repo.sdltGetOrganisation("STORN99999").futureValue

      result.storn mustBe Some("STORN99999")
      result.version mustBe None
      result.isReturnUser mustBe None
      result.doNotDisplayWelcomePage mustBe None
      result.agents mustBe Seq.empty

      verify(cs).setString(1, "STORN99999")
      verify(cs).execute()
    }

    "handle empty organisation and agent result sets" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      val rsOrg    = mock[ResultSet]
      val rsAgents = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      when(cs.getObject(eqTo(2), eqTo(classOf[ResultSet]))).thenReturn(rsOrg)
      when(cs.getObject(eqTo(3), eqTo(classOf[ResultSet]))).thenReturn(rsAgents)

      when(rsOrg.next()).thenReturn(false)
      when(rsAgents.next()).thenReturn(false)

      val repo   = new SdltFormpRepository(db)
      val result = repo.sdltGetOrganisation("STORN00000").futureValue

      result.storn mustBe Some("STORN00000")
      result.version mustBe None
      result.isReturnUser mustBe None
      result.doNotDisplayWelcomePage mustBe None
      result.agents mustBe empty

      verify(cs).execute()
      verify(rsOrg).close()
      verify(rsAgents).close()
    }
  }

  "sdltUpdatePredefinedAgent" - {

    "call Update_Predefined_Agent stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call AGENT_PROCS.Update_Agent(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? ) }")))
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdatePredefinedAgentRequest(
        agentResourceReference = "001",
        storn = "STN001",
        agentName = "Smith & Co Solicitors",
        houseNumber = None,
        addressLine1 = Some("12 High Street"),
        addressLine2 = Some("London"),
        addressLine3 = Some("Greater London"),
        addressLine4 = None,
        postcode = Some("SW1A 1AA"),
        phone = Some("02071234567"),
        email = Some("info@smithco.co.uk"),
        dxAddress = None
      )

      val result = repo.sdltUpdatePredefinedAgent(request).futureValue

      result.updated mustBe true

      verify(conn).prepareCall("{ call AGENT_PROCS.Update_Agent(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? ) }")
      verify(cs).setString(1, request.storn)
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltDeletePredefinedAgent" - {

    "call Delete_Predefined_Agent stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call AGENT_PROCS.Delete_Agent(?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeletePredefinedAgentRequest(
        storn = "STN001",
        agentReferenceNumber = "100001"
      )

      val result = repo.sdltDeletePredefinedAgent(request).futureValue

      result.deleted mustBe true

      verify(conn).prepareCall("{ call AGENT_PROCS.Delete_Agent(?, ?) }")
      verify(cs).setString(1, "STN001")
      verify(cs).setLong(2, 100001L)
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltCreatePredefinedAgent" - {
    "call AGENT_PROCS.CREATE_AGENT stored procedure and store createPredefinedAgent and return createPredefinedAgentResponse" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call AGENT_PROCS.create_agent(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")))
        .thenReturn(cs)
      when(cs.getLong(12)).thenReturn(100011L)
      when(cs.getLong(13)).thenReturn(12L)

      val repo = new SdltFormpRepository(db)

      val request = CreatePredefinedAgentRequest(
        storn = "STORN12345",
        agentName = "Andrew",
        houseNumber = None,
        addressLine1 = Some("Main Street"),
        addressLine2 = Some("Apartment 4B"),
        addressLine3 = Some("City Center"),
        addressLine4 = Some("Greater London"),
        postcode = Some("SW1A 1AA"),
        phone = Some("1234"),
        email = Some("andrew@email.com"),
        dxAddress = None
      )

      val result = repo.sdltCreatePredefinedAgent(request).futureValue

      result.agentId mustBe Some("100011")
      result.agentResourceRef mustBe Some("12")

      verify(conn).prepareCall("{ call AGENT_PROCS.create_agent(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setString(2, "Andrew")
      verify(cs).setNull(3, Types.VARCHAR)
      verify(cs).setString(4, "Main Street")
      verify(cs).setString(5, "Apartment 4B")
      verify(cs).setString(6, "City Center")
      verify(cs).setString(7, "Greater London")
      verify(cs).setString(8, "SW1A 1AA")
      verify(cs).setString(9, "1234")
      verify(cs).setString(10, "andrew@email.com")
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).registerOutParameter(12, Types.NUMERIC)
      verify(cs).registerOutParameter(13, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()

    }

    "handle optional fields being None" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call AGENT_PROCS.create_agent(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")))
        .thenReturn(cs)
      when(cs.getLong(12)).thenReturn(1000112L)
      when(cs.getLong(13)).thenReturn(12L)

      val repo = new SdltFormpRepository(db)

      val request = CreatePredefinedAgentRequest(
        storn = "STORN12345",
        agentName = "Andrew",
        houseNumber = None,
        addressLine1 = None,
        addressLine2 = None,
        addressLine3 = None,
        addressLine4 = None,
        postcode = None,
        phone = None,
        email = None,
        dxAddress = None
      )

      val result = repo.sdltCreatePredefinedAgent(request).futureValue

      result.agentId mustBe Some("1000112")
      result.agentResourceRef mustBe Some("12")

      verify(conn).prepareCall("{ call AGENT_PROCS.create_agent(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setString(2, "Andrew")
      verify(cs).setNull(3, Types.VARCHAR)
      verify(cs).setNull(4, Types.VARCHAR)
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setNull(6, Types.VARCHAR)
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).registerOutParameter(12, Types.NUMERIC)
      verify(cs).registerOutParameter(13, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()

    }
  }

  "sdltCreatePurchaser" - {

    "call Create_Purchaser stored procedure with correct parameters and return purchaser IDs" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(
          eqTo(
            "{ call PURCHASER_PROCS.Create_Purchaser(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
          )
        )
      )
        .thenReturn(cs)
      when(cs.getLong(25)).thenReturn(100001L)
      when(cs.getLong(26)).thenReturn(1L)

      val repo = new SdltFormpRepository(db)

      val request = CreatePurchaserRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        isCompany = Some("NO"),
        isTrustee = Some("NO"),
        isConnectedToVendor = Some("NO"),
        isRepresentedByAgent = Some("NO"),
        title = Some("Mr"),
        surname = Some("Smith"),
        forename1 = Some("John"),
        forename2 = Some("James"),
        companyName = None,
        houseNumber = Some("123"),
        address1 = Some("Main Street"),
        address2 = Some("Apartment 4B"),
        address3 = Some("City Center"),
        address4 = Some("Greater London"),
        postcode = Some("SW1A 1AA"),
        phone = Some("07777123456"),
        nino = Some("AB123456C"),
        isUkCompany = None,
        hasNino = Some("YES"),
        dateOfBirth = Some("1980-01-15"),
        registrationNumber = None,
        placeOfRegistration = None
      )

      val result = repo.sdltCreatePurchaser(request).futureValue

      result.purchaserResourceRef mustBe "100001"
      result.purchaserId mustBe "1"

      verify(conn).prepareCall(
        "{ call PURCHASER_PROCS.Create_Purchaser(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
      )
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "NO")
      verify(cs).setString(4, "NO")
      verify(cs).setString(5, "NO")
      verify(cs).setString(6, "NO")
      verify(cs).setString(7, "Mr")
      verify(cs).setString(8, "Smith")
      verify(cs).setString(9, "John")
      verify(cs).setString(10, "James")
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setString(12, "123")
      verify(cs).setString(13, "Main Street")
      verify(cs).setString(14, "Apartment 4B")
      verify(cs).setString(15, "City Center")
      verify(cs).setString(16, "Greater London")
      verify(cs).setString(17, "SW1A 1AA")
      verify(cs).setString(18, "07777123456")
      verify(cs).setString(19, "AB123456C")
      verify(cs).setString(20, "YES")
      verify(cs).setString(21, "1980-01-15")
      verify(cs).setNull(22, Types.VARCHAR)
      verify(cs).setNull(23, Types.VARCHAR)
      verify(cs).setNull(24, Types.VARCHAR)
      verify(cs).registerOutParameter(25, Types.NUMERIC)
      verify(cs).registerOutParameter(26, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle company purchaser with optional fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getLong(25)).thenReturn(100002L)
      when(cs.getLong(26)).thenReturn(2L)

      val repo = new SdltFormpRepository(db)

      val request = CreatePurchaserRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        isCompany = Some("YES"),
        isTrustee = Some("NO"),
        isConnectedToVendor = Some("NO"),
        isRepresentedByAgent = Some("NO"),
        title = None,
        surname = None,
        forename1 = None,
        forename2 = None,
        companyName = Some("Tech Corp Ltd"),
        houseNumber = None,
        address1 = Some("Business Park"),
        address2 = None,
        address3 = None,
        address4 = None,
        postcode = Some("EC1A 1BB"),
        phone = Some("02012345678"),
        nino = None,
        isUkCompany = Some("YES"),
        hasNino = Some("NO"),
        dateOfBirth = None,
        registrationNumber = Some("12345678"),
        placeOfRegistration = None
      )

      val result = repo.sdltCreatePurchaser(request).futureValue

      result.purchaserResourceRef mustBe "100002"
      result.purchaserId mustBe "2"

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setString(3, "YES")
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setString(11, "Tech Corp Ltd")
      verify(cs).setString(22, "YES")
      verify(cs).setString(23, "12345678")
      verify(cs).execute()
    }
  }

  "sdltUpdatePurchaser" - {

    "call Update_Purchaser stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(
          eqTo(
            "{ call PURCHASER_PROCS.Update_Purchaser(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
          )
        )
      )
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdatePurchaserRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        purchaserResourceRef = "100001",
        isCompany = Some("NO"),
        isTrustee = Some("NO"),
        isConnectedToVendor = Some("YES"),
        isRepresentedByAgent = Some("YES"),
        title = Some("Mrs"),
        surname = Some("Doe"),
        forename1 = Some("Jane"),
        forename2 = None,
        companyName = None,
        houseNumber = Some("456"),
        address1 = Some("Oak Avenue"),
        address2 = Some("Suite 10"),
        address3 = None,
        address4 = None,
        postcode = Some("W1A 1AA"),
        phone = Some("07777654321"),
        nino = Some("CD987654B"),
        nextPurchaserId = Some("100002"),
        isUkCompany = None,
        hasNino = Some("YES"),
        dateOfBirth = Some("1985-05-20"),
        registrationNumber = None,
        placeOfRegistration = None
      )

      val result = repo.sdltUpdatePurchaser(request).futureValue

      result.updated mustBe true

      verify(conn).prepareCall(
        "{ call PURCHASER_PROCS.Update_Purchaser(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
      )
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setLong(3, 100001L)
      verify(cs).setString(4, "NO")
      verify(cs).setString(5, "NO")
      verify(cs).setString(6, "YES")
      verify(cs).setString(7, "YES")
      verify(cs).setString(8, "Mrs")
      verify(cs).setString(9, "Doe")
      verify(cs).setString(10, "Jane")
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setString(13, "456")
      verify(cs).setString(14, "Oak Avenue")
      verify(cs).setString(15, "Suite 10")
      verify(cs).setNull(16, Types.VARCHAR)
      verify(cs).setNull(17, Types.VARCHAR)
      verify(cs).setString(18, "W1A 1AA")
      verify(cs).setString(19, "07777654321")
      verify(cs).setString(20, "CD987654B")
      verify(cs).setString(21, "100002")
      verify(cs).setString(22, "YES")
      verify(cs).setString(23, "1985-05-20")
      verify(cs).setNull(24, Types.VARCHAR)
      verify(cs).setNull(25, Types.VARCHAR)
      verify(cs).setNull(26, Types.VARCHAR)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle minimal update with no optional fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdatePurchaserRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        purchaserResourceRef = "100002",
        isCompany = Some("YES"),
        isTrustee = Some("NO"),
        isConnectedToVendor = Some("NO"),
        isRepresentedByAgent = Some("NO"),
        title = None,
        surname = None,
        forename1 = None,
        forename2 = None,
        companyName = Some("Updated Corp"),
        houseNumber = None,
        address1 = Some("New Street"),
        address2 = None,
        address3 = None,
        address4 = None,
        postcode = None,
        phone = None,
        nino = None,
        nextPurchaserId = None,
        isUkCompany = Some("YES"),
        hasNino = Some("NO"),
        dateOfBirth = None,
        registrationNumber = Some("87654321"),
        placeOfRegistration = None
      )

      val result = repo.sdltUpdatePurchaser(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setLong(3, 100002L)
      verify(cs).setString(4, "YES")
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setString(12, "Updated Corp")
      verify(cs).setString(14, "New Street")
      verify(cs).execute()
    }
  }

  "sdltDeletePurchaser" - {

    "call Delete_Purchaser stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call PURCHASER_PROCS.Delete_Purchaser(?, ?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeletePurchaserRequest(
        storn = "STORN12345",
        purchaserResourceRef = "100001",
        returnResourceRef = "100001"
      )

      val result = repo.sdltDeletePurchaser(request).futureValue

      result.deleted mustBe true

      verify(conn).prepareCall("{ call PURCHASER_PROCS.Delete_Purchaser(?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setLong(3, 100001L)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle different purchaser resource references" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeletePurchaserRequest(
        storn = "STORN99999",
        purchaserResourceRef = "999999",
        returnResourceRef = "100002"
      )

      val result = repo.sdltDeletePurchaser(request).futureValue

      result.deleted mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setLong(3, 999999L)
      verify(cs).execute()
    }
  }

  "sdltCreateCompanyDetails" - {

    "call Create_Company_Details stored procedure with correct parameters and return company details ID" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(
          eqTo(
            "{ call PURCHASER_PROCS.Create_Company_Details(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
          )
        )
      )
        .thenReturn(cs)
      when(cs.getLong(21)).thenReturn(100001L)

      val repo = new SdltFormpRepository(db)

      val request = CreateCompanyDetailsRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        purchaserResourceRef = "100001",
        utr = Some("1234567890"),
        vatReference = Some("GB123456789"),
        compTypeBank = Some("NO"),
        compTypeBuilder = Some("NO"),
        compTypeBuildsoc = Some("NO"),
        compTypeCentgov = Some("NO"),
        compTypeIndividual = Some("NO"),
        compTypeInsurance = Some("NO"),
        compTypeLocalauth = Some("NO"),
        compTypeOcharity = Some("NO"),
        compTypeOcompany = Some("YES"),
        compTypeOfinancial = Some("NO"),
        compTypePartship = Some("NO"),
        compTypeProperty = Some("NO"),
        compTypePubliccorp = Some("NO"),
        compTypeSoletrader = Some("NO"),
        compTypePenfund = Some("NO")
      )

      val result = repo.sdltCreateCompanyDetails(request).futureValue

      result.companyDetailsId mustBe "100001"

      verify(conn).prepareCall(
        "{ call PURCHASER_PROCS.Create_Company_Details(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
      )
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setLong(3, 100001L)
      verify(cs).setString(4, "1234567890")
      verify(cs).setString(5, "GB123456789")
      verify(cs).setString(6, "NO")
      verify(cs).setString(7, "NO")
      verify(cs).setString(8, "NO")
      verify(cs).setString(9, "NO")
      verify(cs).setString(10, "NO")
      verify(cs).setString(11, "NO")
      verify(cs).setString(12, "NO")
      verify(cs).setString(13, "NO")
      verify(cs).setString(14, "YES")
      verify(cs).setString(15, "NO")
      verify(cs).setString(16, "NO")
      verify(cs).setString(17, "NO")
      verify(cs).setString(18, "NO")
      verify(cs).setString(19, "NO")
      verify(cs).setString(20, "NO")
      verify(cs).registerOutParameter(21, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle optional fields being None" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getLong(21)).thenReturn(100002L)

      val repo = new SdltFormpRepository(db)

      val request = CreateCompanyDetailsRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        purchaserResourceRef = "100002",
        utr = None,
        vatReference = None,
        compTypeBank = None,
        compTypeBuilder = None,
        compTypeBuildsoc = None,
        compTypeCentgov = None,
        compTypeIndividual = None,
        compTypeInsurance = None,
        compTypeLocalauth = None,
        compTypeOcharity = None,
        compTypeOcompany = None,
        compTypeOfinancial = None,
        compTypePartship = None,
        compTypeProperty = None,
        compTypePubliccorp = None,
        compTypeSoletrader = None,
        compTypePenfund = None
      )

      val result = repo.sdltCreateCompanyDetails(request).futureValue

      result.companyDetailsId mustBe "100002"

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setLong(3, 100002L)
      verify(cs).setNull(4, Types.VARCHAR)
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setNull(6, Types.VARCHAR)
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).setNull(14, Types.VARCHAR)
      verify(cs).setNull(15, Types.VARCHAR)
      verify(cs).setNull(16, Types.VARCHAR)
      verify(cs).setNull(17, Types.VARCHAR)
      verify(cs).setNull(18, Types.VARCHAR)
      verify(cs).setNull(19, Types.VARCHAR)
      verify(cs).setNull(20, Types.VARCHAR)
      verify(cs).execute()
    }
  }

  "sdltUpdateCompanyDetails" - {

    "call Update_Company_Details stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(
          eqTo(
            "{ call PURCHASER_PROCS.Update_Company_Details(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
          )
        )
      )
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateCompanyDetailsRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        purchaserResourceRef = "100001",
        utr = Some("9876543210"),
        vatReference = Some("GB987654321"),
        compTypeBank = Some("NO"),
        compTypeBuilder = Some("YES"),
        compTypeBuildsoc = Some("NO"),
        compTypeCentgov = Some("NO"),
        compTypeIndividual = Some("NO"),
        compTypeInsurance = Some("NO"),
        compTypeLocalauth = Some("NO"),
        compTypeOcharity = Some("NO"),
        compTypeOcompany = Some("YES"),
        compTypeOfinancial = Some("NO"),
        compTypePartship = Some("NO"),
        compTypeProperty = Some("YES"),
        compTypePubliccorp = Some("NO"),
        compTypeSoletrader = Some("NO"),
        compTypePenfund = Some("NO")
      )

      val result = repo.sdltUpdateCompanyDetails(request).futureValue

      result.updated mustBe true

      verify(conn).prepareCall(
        "{ call PURCHASER_PROCS.Update_Company_Details(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
      )
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setLong(3, 100001L)
      verify(cs).setString(4, "9876543210")
      verify(cs).setString(5, "GB987654321")
      verify(cs).setString(6, "NO")
      verify(cs).setString(7, "YES")
      verify(cs).setString(8, "NO")
      verify(cs).setString(14, "YES")
      verify(cs).setString(17, "YES")
      verify(cs).execute()
      verify(cs).close()
    }

    "handle minimal update with no optional fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateCompanyDetailsRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        purchaserResourceRef = "100002",
        utr = None,
        vatReference = None,
        compTypeBank = None,
        compTypeBuilder = None,
        compTypeBuildsoc = None,
        compTypeCentgov = None,
        compTypeIndividual = None,
        compTypeInsurance = None,
        compTypeLocalauth = None,
        compTypeOcharity = None,
        compTypeOcompany = None,
        compTypeOfinancial = None,
        compTypePartship = None,
        compTypeProperty = None,
        compTypePubliccorp = None,
        compTypeSoletrader = None,
        compTypePenfund = None
      )

      val result = repo.sdltUpdateCompanyDetails(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setLong(3, 100002L)
      verify(cs).setNull(4, Types.VARCHAR)
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).execute()
    }
  }

  "sdltDeleteCompanyDetails" - {

    "call Delete_Company_Details stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call PURCHASER_PROCS.Delete_Company_Details(?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteCompanyDetailsRequest(
        storn = "STORN12345",
        returnResourceRef = "100001"
      )

      val result = repo.sdltDeleteCompanyDetails(request).futureValue

      result.deleted mustBe true

      verify(conn).prepareCall("{ call PURCHASER_PROCS.Delete_Company_Details(?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle different return resource references" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteCompanyDetailsRequest(
        storn = "STORN99999",
        returnResourceRef = "100002"
      )

      val result = repo.sdltDeleteCompanyDetails(request).futureValue

      result.deleted mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).execute()
    }
  }

  "sdltCreateLand" - {

    "call Create_Land stored procedure with correct parameters and return land IDs" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(
          eqTo("{ call LAND_PROCS.Create_Land(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
        )
      )
        .thenReturn(cs)
      when(cs.getLong(18)).thenReturn(1L)
      when(cs.getLong(19)).thenReturn(100001L)

      val repo = new SdltFormpRepository(db)

      val request = CreateLandRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        propertyType = "RESIDENTIAL",
        interestTransferredCreated = "FREEHOLD",
        houseNumber = Some("123"),
        addressLine1 = "Main Street",
        addressLine2 = Some("Apartment 4B"),
        addressLine3 = Some("City Center"),
        addressLine4 = Some("Greater London"),
        postcode = Some("SW1A 1AA"),
        landArea = Some("500"),
        areaUnit = Some("SQUARE_METERS"),
        localAuthorityNumber = Some("LA12345"),
        mineralRights = Some("YES"),
        nlpgUprn = Some("100012345678"),
        willSendPlansByPost = Some("NO"),
        titleNumber = Some("TN123456")
      )

      val result = repo.sdltCreateLand(request).futureValue

      result.landResourceRef mustBe "100001"
      result.landId mustBe "1"

      verify(conn).prepareCall(
        "{ call LAND_PROCS.Create_Land(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
      )
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "RESIDENTIAL")
      verify(cs).setString(4, "FREEHOLD")
      verify(cs).setString(5, "123")
      verify(cs).setString(6, "Main Street")
      verify(cs).setString(7, "Apartment 4B")
      verify(cs).setString(8, "City Center")
      verify(cs).setString(9, "Greater London")
      verify(cs).setString(10, "SW1A 1AA")
      verify(cs).setString(11, "500")
      verify(cs).setString(12, "SQUARE_METERS")
      verify(cs).setString(13, "LA12345")
      verify(cs).setString(14, "YES")
      verify(cs).setString(15, "100012345678")
      verify(cs).setString(16, "NO")
      verify(cs).setString(17, "TN123456")
      verify(cs).registerOutParameter(18, Types.NUMERIC)
      verify(cs).registerOutParameter(19, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle optional fields being None" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getLong(18)).thenReturn(2L)
      when(cs.getLong(19)).thenReturn(100002L)

      val repo = new SdltFormpRepository(db)

      val request = CreateLandRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        propertyType = "NON_RESIDENTIAL",
        interestTransferredCreated = "LEASEHOLD",
        houseNumber = None,
        addressLine1 = "Business Park",
        addressLine2 = None,
        addressLine3 = None,
        addressLine4 = None,
        postcode = None,
        landArea = None,
        areaUnit = None,
        localAuthorityNumber = None,
        mineralRights = None,
        nlpgUprn = None,
        willSendPlansByPost = None,
        titleNumber = None
      )

      val result = repo.sdltCreateLand(request).futureValue

      result.landResourceRef mustBe "100002"
      result.landId mustBe "2"

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setString(3, "NON_RESIDENTIAL")
      verify(cs).setString(4, "LEASEHOLD")
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setString(6, "Business Park")
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).setNull(14, Types.VARCHAR)
      verify(cs).setNull(15, Types.VARCHAR)
      verify(cs).setNull(16, Types.VARCHAR)
      verify(cs).setNull(17, Types.VARCHAR)
      verify(cs).execute()
    }

    "handle mixed residential property" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getLong(18)).thenReturn(3L)
      when(cs.getLong(19)).thenReturn(100003L)

      val repo = new SdltFormpRepository(db)

      val request = CreateLandRequest(
        stornId = "STORN88888",
        returnResourceRef = "100003",
        propertyType = "MIXED",
        interestTransferredCreated = "FREEHOLD",
        houseNumber = Some("99"),
        addressLine1 = "High Street",
        addressLine2 = Some("Town Centre"),
        addressLine3 = Some("Manchester"),
        addressLine4 = None,
        postcode = Some("M1 1AA"),
        landArea = Some("1000"),
        areaUnit = Some("SQUARE_FEET"),
        localAuthorityNumber = Some("LA99999"),
        mineralRights = Some("NO"),
        nlpgUprn = Some("100099887766"),
        willSendPlansByPost = Some("YES"),
        titleNumber = Some("TN999888")
      )

      val result = repo.sdltCreateLand(request).futureValue

      result.landResourceRef mustBe "100003"
      result.landId mustBe "3"

      verify(cs).setString(1, "STORN88888")
      verify(cs).setLong(2, 100003L)
      verify(cs).setString(3, "MIXED")
      verify(cs).setString(4, "FREEHOLD")
      verify(cs).setString(16, "YES")
      verify(cs).execute()
    }
  }

  "sdltUpdateLand" - {

    "call Update_Land stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(
          eqTo("{ call LAND_PROCS.Update_Land(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
        )
      )
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateLandRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        landResourceRef = "100001",
        propertyType = "RESIDENTIAL",
        interestTransferredCreated = "FREEHOLD",
        houseNumber = Some("456"),
        addressLine1 = "Oak Avenue",
        addressLine2 = Some("Suite 10"),
        addressLine3 = Some("Updated City"),
        addressLine4 = None,
        postcode = Some("W1A 1AA"),
        landArea = Some("750"),
        areaUnit = Some("SQUARE_METERS"),
        localAuthorityNumber = Some("LA54321"),
        mineralRights = Some("NO"),
        nlpgUprn = Some("100087654321"),
        willSendPlansByPost = Some("YES"),
        titleNumber = Some("TN654321"),
        nextLandId = Some("100002")
      )

      val result = repo.sdltUpdateLand(request).futureValue

      result.updated mustBe true

      verify(conn).prepareCall(
        "{ call LAND_PROCS.Update_Land(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
      )
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "RESIDENTIAL")
      verify(cs).setString(4, "FREEHOLD")
      verify(cs).setString(5, "456")
      verify(cs).setString(6, "Oak Avenue")
      verify(cs).setString(7, "Suite 10")
      verify(cs).setString(8, "Updated City")
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setString(10, "W1A 1AA")
      verify(cs).setString(11, "750")
      verify(cs).setString(12, "SQUARE_METERS")
      verify(cs).setString(13, "LA54321")
      verify(cs).setString(14, "NO")
      verify(cs).setString(15, "100087654321")
      verify(cs).setString(16, "YES")
      verify(cs).setString(17, "TN654321")
      verify(cs).setLong(18, 100001L)
      verify(cs).setString(19, "100002")
      verify(cs).execute()
      verify(cs).close()
    }

    "handle minimal update with no optional fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateLandRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        landResourceRef = "100002",
        propertyType = "NON_RESIDENTIAL",
        interestTransferredCreated = "LEASEHOLD",
        houseNumber = None,
        addressLine1 = "Updated Business Park",
        addressLine2 = None,
        addressLine3 = None,
        addressLine4 = None,
        postcode = None,
        landArea = None,
        areaUnit = None,
        localAuthorityNumber = None,
        mineralRights = None,
        nlpgUprn = None,
        willSendPlansByPost = None,
        titleNumber = None,
        nextLandId = None
      )

      val result = repo.sdltUpdateLand(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setString(3, "NON_RESIDENTIAL")
      verify(cs).setString(4, "LEASEHOLD")
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setString(6, "Updated Business Park")
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).setNull(14, Types.VARCHAR)
      verify(cs).setNull(15, Types.VARCHAR)
      verify(cs).setNull(16, Types.VARCHAR)
      verify(cs).setNull(17, Types.VARCHAR)
      verify(cs).setLong(18, 100002L)
      verify(cs).setNull(19, Types.VARCHAR)
      verify(cs).execute()
    }

    "handle update with only required fields changed" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateLandRequest(
        stornId = "STORN77777",
        returnResourceRef = "100003",
        landResourceRef = "100003",
        propertyType = "MIXED",
        interestTransferredCreated = "FREEHOLD",
        houseNumber = Some("1"),
        addressLine1 = "New Street",
        addressLine2 = None,
        addressLine3 = None,
        addressLine4 = None,
        postcode = Some("NE1 1AA"),
        landArea = None,
        areaUnit = None,
        localAuthorityNumber = None,
        mineralRights = None,
        nlpgUprn = None,
        willSendPlansByPost = None,
        titleNumber = None,
        nextLandId = None
      )

      val result = repo.sdltUpdateLand(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN77777")
      verify(cs).setLong(2, 100003L)
      verify(cs).setString(3, "MIXED")
      verify(cs).setString(4, "FREEHOLD")
      verify(cs).setString(5, "1")
      verify(cs).setString(6, "New Street")
      verify(cs).setString(10, "NE1 1AA")
      verify(cs).setLong(18, 100003L)
      verify(cs).execute()
    }
  }

  "sdltDeleteLand" - {

    "call Delete_Land stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call LAND_PROCS.Delete_Land(?, ?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteLandRequest(
        storn = "STORN12345",
        returnResourceRef = "100001",
        landResourceRef = "100001"
      )

      val result = repo.sdltDeleteLand(request).futureValue

      result.deleted mustBe true

      verify(conn).prepareCall("{ call LAND_PROCS.Delete_Land(?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setLong(3, 100001L)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle different land resource references" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteLandRequest(
        storn = "STORN99999",
        returnResourceRef = "100002",
        landResourceRef = "999999"
      )

      val result = repo.sdltDeleteLand(request).futureValue

      result.deleted mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setLong(3, 999999L)
      verify(cs).execute()
    }

    "handle deletion of secondary land property" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteLandRequest(
        storn = "STORN88888",
        returnResourceRef = "100003",
        landResourceRef = "100004"
      )

      val result = repo.sdltDeleteLand(request).futureValue

      result.deleted mustBe true

      verify(cs).setString(1, "STORN88888")
      verify(cs).setLong(2, 100003L)
      verify(cs).setLong(3, 100004L)
      verify(cs).execute()
    }
  }

  "sdltUpdateReturn" - {

    "call Update_Return stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call RETURN_PROCS.Update_Return(?, ?, ?, ?, ?, ?, ?, ?) }")))
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateReturnRequest(
        storn = "STORN12345",
        returnResourceRef = "100001",
        mainPurchaserID = Some("1"),
        mainVendorID = Some("1"),
        mainLandID = Some("1"),
        IRMarkGenerated = Some("IRMark123456"),
        landCertForEachProp = Some("YES"),
        declaration = Some("YES")
      )

      val result = repo.sdltUpdateReturn(request).futureValue

      result.updated mustBe true

      verify(conn).prepareCall("{ call RETURN_PROCS.Update_Return(?, ?, ?, ?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "1")
      verify(cs).setString(4, "1")
      verify(cs).setString(5, "1")
      verify(cs).setString(6, "IRMark123456")
      verify(cs).setString(7, "YES")
      verify(cs).setString(8, "YES")
      verify(cs).execute()
      verify(cs).close()
    }

    "handle update with different values" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateReturnRequest(
        storn = "STORN99999",
        returnResourceRef = "100002",
        mainPurchaserID = Some("5"),
        mainVendorID = Some("3"),
        mainLandID = Some("7"),
        IRMarkGenerated = Some("IRMark999999"),
        landCertForEachProp = Some("NO"),
        declaration = Some("YES")
      )

      val result = repo.sdltUpdateReturn(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setString(3, "5")
      verify(cs).setString(4, "3")
      verify(cs).setString(5, "7")
      verify(cs).setString(6, "IRMark999999")
      verify(cs).setString(7, "NO")
      verify(cs).setString(8, "YES")
      verify(cs).execute()
    }

    "handle update with NO values for boolean fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateReturnRequest(
        storn = "STORN88888",
        returnResourceRef = "100003",
        mainPurchaserID = Some("10"),
        mainVendorID = Some("20"),
        mainLandID = Some("30"),
        IRMarkGenerated = Some("IRMark888888"),
        landCertForEachProp = Some("NO"),
        declaration = Some("NO")
      )

      val result = repo.sdltUpdateReturn(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN88888")
      verify(cs).setLong(2, 100003L)
      verify(cs).setString(3, "10")
      verify(cs).setString(4, "20")
      verify(cs).setString(5, "30")
      verify(cs).setString(6, "IRMark888888")
      verify(cs).setString(7, "NO")
      verify(cs).setString(8, "NO")
      verify(cs).execute()
    }
  }

  "sdltCreateResidency" - {

    "call CREATE_RESIDENCY stored procedure with correct parameters and return created=true" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call RESIDENCY_PROCS.CREATE_RESIDENCY(?, ?, ?, ?, ?) }")))
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = CreateResidencyRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        residency = ResidencyPayload(
          isNonUkResidents = "NO",
          isCompany = "NO",
          isCrownRelief = "NO"
        )
      )

      val result = repo.sdltCreateResidency(request).futureValue

      result.created mustBe true

      verify(conn).prepareCall("{ call RESIDENCY_PROCS.CREATE_RESIDENCY(?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "NO")
      verify(cs).setString(4, "NO")
      verify(cs).setString(5, "NO")
      verify(cs).execute()
      verify(cs).close()
    }

    "call CREATE_RESIDENCY with all YES flags" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = CreateResidencyRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        residency = ResidencyPayload(
          isNonUkResidents = "YES",
          isCompany = "YES",
          isCrownRelief = "YES"
        )
      )

      val result = repo.sdltCreateResidency(request).futureValue

      result.created mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setString(3, "YES")
      verify(cs).setString(4, "YES")
      verify(cs).setString(5, "YES")
      verify(cs).execute()
    }

    "call CREATE_RESIDENCY with mixed flags" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = CreateResidencyRequest(
        stornId = "STORN88888",
        returnResourceRef = "100003",
        residency = ResidencyPayload(
          isNonUkResidents = "YES",
          isCompany = "NO",
          isCrownRelief = "YES"
        )
      )

      val result = repo.sdltCreateResidency(request).futureValue

      result.created mustBe true

      verify(cs).setString(1, "STORN88888")
      verify(cs).setLong(2, 100003L)
      verify(cs).setString(3, "YES")
      verify(cs).setString(4, "NO")
      verify(cs).setString(5, "YES")
      verify(cs).execute()
    }
  }

  "sdltUpdateResidency" - {

    "call UPDATE_RESIDENCY stored procedure with correct parameters and return updated=true" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call RESIDENCY_PROCS.UPDATE_RESIDENCY(?, ?, ?, ?, ?) }")))
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateResidencyRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        residency = ResidencyPayload(
          isNonUkResidents = "NO",
          isCompany = "NO",
          isCrownRelief = "NO"
        )
      )

      val result = repo.sdltUpdateResidency(request).futureValue

      result.updated mustBe true

      verify(conn).prepareCall("{ call RESIDENCY_PROCS.UPDATE_RESIDENCY(?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "NO")
      verify(cs).setString(4, "NO")
      verify(cs).setString(5, "NO")
      verify(cs).execute()
      verify(cs).close()
    }

    "call UPDATE_RESIDENCY with all YES flags" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateResidencyRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        residency = ResidencyPayload(
          isNonUkResidents = "YES",
          isCompany = "YES",
          isCrownRelief = "YES"
        )
      )

      val result = repo.sdltUpdateResidency(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setString(3, "YES")
      verify(cs).setString(4, "YES")
      verify(cs).setString(5, "YES")
      verify(cs).execute()
    }

    "call UPDATE_RESIDENCY with mixed flags" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateResidencyRequest(
        stornId = "STORN88888",
        returnResourceRef = "100003",
        residency = ResidencyPayload(
          isNonUkResidents = "NO",
          isCompany = "YES",
          isCrownRelief = "NO"
        )
      )

      val result = repo.sdltUpdateResidency(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN88888")
      verify(cs).setLong(2, 100003L)
      verify(cs).setString(3, "NO")
      verify(cs).setString(4, "YES")
      verify(cs).setString(5, "NO")
      verify(cs).execute()
    }
  }

  "sdltDeleteResidency" - {

    "call DELETE_RESIDENCY stored procedure with correct parameters and return deleted=true" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call RESIDENCY_PROCS.DELETE_RESIDENCY(?, ?) }")))
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteResidencyRequest(
        storn = "STORN12345",
        returnResourceRef = "100001"
      )

      val result = repo.sdltDeleteResidency(request).futureValue

      result.deleted mustBe true

      verify(conn).prepareCall("{ call RESIDENCY_PROCS.DELETE_RESIDENCY(?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle different return resource references" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteResidencyRequest(
        storn = "STORN99999",
        returnResourceRef = "100002"
      )

      val result = repo.sdltDeleteResidency(request).futureValue

      result.deleted mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).execute()
    }

    "handle different storn formats" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteResidencyRequest(
        storn = "STORN88888",
        returnResourceRef = "100003"
      )

      val result = repo.sdltDeleteResidency(request).futureValue

      result.deleted mustBe true

      verify(cs).setString(1, "STORN88888")
      verify(cs).setLong(2, 100003L)
      verify(cs).execute()
    }
  }

  "sdltUpdateTransaction" - {

    "call UPDATE_TRANSACTION stored procedure with correct parameters and return updated=true" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(
          eqTo(
            "{ call TRANSACTION_PROCS.UPDATE_TRANSACTION(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
          )
        )
      ).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateTransactionRequest(
        storn = "STORN12345",
        returnResourceRef = "100001",
        transaction = TransactionPayload(
          claimingRelief = Some("N"),
          isLinked = Some("N"),
          totalConsider = Some("250000"),
          considerCash = Some("Y"),
          includesChattel = Some("N"),
          includesGoodwill = Some("N"),
          includesOther = Some("N"),
          includesStock = Some("N"),
          usedAsFactory = Some("N"),
          usedAsHotel = Some("N"),
          usedAsIndustrial = Some("N"),
          usedAsOffice = Some("N"),
          usedAsOther = Some("N"),
          usedAsShop = Some("N"),
          usedAsWarehouse = Some("N"),
          contractDate = Some("2025-01-15"),
          isDependOnFutureEvent = Some("N"),
          transactionDescription = Some("RESIDENTIAL"),
          newTransactionDescription = Some("RESIDENTIAL"),
          effectiveDate = Some("2025-02-01"),
          isLandExchanged = Some("N"),
          agreedDeferPay = Some("N"),
          postTransactionRulingApplied = Some("N"),
          isPursuantToPreviousOption = Some("N"),
          restAffectInt = Some("N"),
          postTransactionRulingFollowed = Some("N"),
          isPartOfSaleOfBusiness = Some("N")
        )
      )

      val result = repo.sdltUpdateTransaction(request).futureValue

      result.updated mustBe true

      verify(conn).prepareCall(
        "{ call TRANSACTION_PROCS.UPDATE_TRANSACTION(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
      )
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "N")
      verify(cs).setString(7, "N")
      verify(cs).setString(9, "250000")
      verify(cs).setString(11, "Y")
      verify(cs).setString(32, "2025-01-15")
      verify(cs).setString(34, "RESIDENTIAL")
      verify(cs).setString(36, "2025-02-01")
      verify(cs).execute()
      verify(cs).close()
    }

    "handle all None optional fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateTransactionRequest(
        storn = "STORN99999",
        returnResourceRef = "100002",
        transaction = TransactionPayload()
      )

      val result = repo.sdltUpdateTransaction(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setNull(3, Types.VARCHAR)
      verify(cs).setNull(4, Types.VARCHAR)
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setNull(6, Types.VARCHAR)
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).setNull(14, Types.VARCHAR)
      verify(cs).setNull(15, Types.VARCHAR)
      verify(cs).setNull(16, Types.VARCHAR)
      verify(cs).setNull(17, Types.VARCHAR)
      verify(cs).setNull(18, Types.VARCHAR)
      verify(cs).setNull(19, Types.VARCHAR)
      verify(cs).setNull(20, Types.VARCHAR)
      verify(cs).setNull(21, Types.VARCHAR)
      verify(cs).setNull(22, Types.VARCHAR)
      verify(cs).setNull(23, Types.VARCHAR)
      verify(cs).setNull(24, Types.VARCHAR)
      verify(cs).setNull(25, Types.VARCHAR)
      verify(cs).setNull(26, Types.VARCHAR)
      verify(cs).setNull(27, Types.VARCHAR)
      verify(cs).setNull(28, Types.VARCHAR)
      verify(cs).setNull(29, Types.VARCHAR)
      verify(cs).setNull(30, Types.VARCHAR)
      verify(cs).setNull(31, Types.VARCHAR)
      verify(cs).setNull(32, Types.VARCHAR)
      verify(cs).setNull(33, Types.VARCHAR)
      verify(cs).setNull(34, Types.VARCHAR)
      verify(cs).setNull(35, Types.VARCHAR)
      verify(cs).setNull(36, Types.VARCHAR)
      verify(cs).setNull(37, Types.VARCHAR)
      verify(cs).setNull(38, Types.VARCHAR)
      verify(cs).setNull(39, Types.VARCHAR)
      verify(cs).setNull(40, Types.VARCHAR)
      verify(cs).setNull(41, Types.VARCHAR)
      verify(cs).setNull(42, Types.VARCHAR)
      verify(cs).setNull(43, Types.VARCHAR)
      verify(cs).setNull(44, Types.VARCHAR)
      verify(cs).setNull(45, Types.VARCHAR)
      verify(cs).setNull(46, Types.VARCHAR)
      verify(cs).setNull(47, Types.VARCHAR)
      verify(cs).setNull(48, Types.VARCHAR)
      verify(cs).setNull(49, Types.VARCHAR)
      verify(cs).setNull(50, Types.VARCHAR)
      verify(cs).setNull(51, Types.VARCHAR)
      verify(cs).execute()
    }

    "handle relief fields when claiming relief" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateTransactionRequest(
        storn = "STORN88888",
        returnResourceRef = "100003",
        transaction = TransactionPayload(
          claimingRelief = Some("Y"),
          reliefAmount = Some("5000"),
          reliefReason = Some("CHARITY"),
          reliefSchemeNumber = Some("CIS123456")
        )
      )

      val result = repo.sdltUpdateTransaction(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN88888")
      verify(cs).setLong(2, 100003L)
      verify(cs).setString(3, "Y")
      verify(cs).setString(4, "5000")
      verify(cs).setString(5, "CHARITY")
      verify(cs).setString(6, "CIS123456")
      verify(cs).execute()
    }

    "handle land exchange fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateTransactionRequest(
        storn = "STORN77777",
        returnResourceRef = "100004",
        transaction = TransactionPayload(
          isLandExchanged = Some("Y"),
          exLandHouseNumber = Some("10"),
          exLandAddress1 = Some("Exchange Street"),
          exLandAddress2 = Some("Exchange Town"),
          exLandAddress3 = Some("Exchange City"),
          exLandAddress4 = None,
          exLandPostcode = Some("EX1 1CH")
        )
      )

      val result = repo.sdltUpdateTransaction(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN77777")
      verify(cs).setLong(2, 100004L)
      verify(cs).setString(37, "Y")
      verify(cs).setString(38, "10")
      verify(cs).setString(39, "Exchange Street")
      verify(cs).setString(40, "Exchange Town")
      verify(cs).setString(41, "Exchange City")
      verify(cs).setNull(42, Types.VARCHAR)
      verify(cs).setString(43, "EX1 1CH")
      verify(cs).execute()
    }

    "handle sale of business fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateTransactionRequest(
        storn = "STORN66666",
        returnResourceRef = "100005",
        transaction = TransactionPayload(
          isPartOfSaleOfBusiness = Some("Y"),
          totalConsiderationOfBusiness = Some("500000")
        )
      )

      val result = repo.sdltUpdateTransaction(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN66666")
      verify(cs).setLong(2, 100005L)
      verify(cs).setString(50, "Y")
      verify(cs).setString(51, "500000")
      verify(cs).execute()
    }
  }

  "sdltCreateLease" - {

    "call CREATE_LEASE stored procedure with correct parameters and return created=true" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      val expectedSql =
        "{ call LEASE_PROCS.CREATE_LEASE(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
          "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
          "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
          "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
          "?, ?, ?, ?, ?) }"

      when(conn.prepareCall(eqTo(expectedSql))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = CreateLeaseRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        lease = LeasePayload(
          isAnnualRentOver1000 = Some("YES"),
          contractEndDate = Some("2030-12-31"),
          contractStartDate = Some("2025-01-01"),
          leaseType = Some("COMMERCIAL"),
          netPresentValue = Some("50000"),
          totalPremiumPayable = Some("10000"),
          rentFreePeriod = Some("NO"),
          startingRent = Some("12000"),
          startingRentEndDate = Some("2026-01-01"),
          laterRentKnown = Some("YES"),
          vatAmount = Some("2400")
        )
      )

      val result = repo.sdltCreateLease(request).futureValue

      result.created mustBe true

      verify(conn).prepareCall(expectedSql)
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "YES")
      verify(cs).setNull(4, Types.VARCHAR)
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setNull(6, Types.VARCHAR)
      verify(cs).setString(7, "2030-12-31")
      verify(cs).setString(8, "2025-01-01")
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setString(10, "COMMERCIAL")
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setString(12, "50000")
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).setString(14, "10000")
      verify(cs).setNull(15, Types.VARCHAR)
      verify(cs).setString(16, "NO")
      verify(cs).setNull(17, Types.VARCHAR)
      verify(cs).setNull(18, Types.VARCHAR)
      verify(cs).setNull(19, Types.VARCHAR)
      verify(cs).setNull(20, Types.VARCHAR)
      verify(cs).setString(21, "12000")
      verify(cs).setString(22, "2026-01-01")
      verify(cs).setString(23, "YES")
      verify(cs).setNull(24, Types.VARCHAR)
      verify(cs).setNull(25, Types.VARCHAR)
      verify(cs).setNull(26, Types.VARCHAR)
      verify(cs).setNull(27, Types.VARCHAR)
      verify(cs).setNull(28, Types.VARCHAR)
      verify(cs).setNull(29, Types.VARCHAR)
      verify(cs).setNull(30, Types.VARCHAR)
      verify(cs).setNull(31, Types.VARCHAR)
      verify(cs).setNull(32, Types.VARCHAR)
      verify(cs).setNull(33, Types.VARCHAR)
      verify(cs).setNull(34, Types.VARCHAR)
      verify(cs).setNull(35, Types.VARCHAR)
      verify(cs).setNull(36, Types.VARCHAR)
      verify(cs).setNull(37, Types.VARCHAR)
      verify(cs).setNull(38, Types.VARCHAR)
      verify(cs).setNull(39, Types.VARCHAR)
      verify(cs).setNull(40, Types.VARCHAR)
      verify(cs).setNull(41, Types.VARCHAR)
      verify(cs).setNull(42, Types.VARCHAR)
      verify(cs).setNull(43, Types.VARCHAR)
      verify(cs).setString(44, "2400")
      verify(cs).registerOutParameter(45, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle all optional lease fields being None" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = CreateLeaseRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        lease = LeasePayload(
          isAnnualRentOver1000 = None,
          contractEndDate = None,
          contractStartDate = None,
          leaseType = None,
          netPresentValue = None,
          totalPremiumPayable = None,
          rentFreePeriod = None,
          startingRent = None,
          startingRentEndDate = None,
          laterRentKnown = None,
          vatAmount = None
        )
      )

      val result = repo.sdltCreateLease(request).futureValue

      result.created mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setNull(3, Types.VARCHAR)
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setNull(14, Types.VARCHAR)
      verify(cs).setNull(16, Types.VARCHAR)
      verify(cs).setNull(21, Types.VARCHAR)
      verify(cs).setNull(22, Types.VARCHAR)
      verify(cs).setNull(23, Types.VARCHAR)
      verify(cs).setNull(44, Types.VARCHAR)
      verify(cs).execute()
    }

    "handle residential lease with partial fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = CreateLeaseRequest(
        stornId = "STORN88888",
        returnResourceRef = "100003",
        lease = LeasePayload(
          isAnnualRentOver1000 = Some("NO"),
          contractEndDate = Some("2028-06-30"),
          contractStartDate = Some("2025-07-01"),
          leaseType = Some("RESIDENTIAL"),
          netPresentValue = None,
          totalPremiumPayable = None,
          rentFreePeriod = Some("YES"),
          startingRent = Some("8000"),
          startingRentEndDate = None,
          laterRentKnown = None,
          vatAmount = None
        )
      )

      val result = repo.sdltCreateLease(request).futureValue

      result.created mustBe true

      verify(cs).setString(1, "STORN88888")
      verify(cs).setLong(2, 100003L)
      verify(cs).setString(3, "NO")
      verify(cs).setString(7, "2028-06-30")
      verify(cs).setString(8, "2025-07-01")
      verify(cs).setString(10, "RESIDENTIAL")
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setNull(14, Types.VARCHAR)
      verify(cs).setString(16, "YES")
      verify(cs).setString(21, "8000")
      verify(cs).setNull(22, Types.VARCHAR)
      verify(cs).setNull(23, Types.VARCHAR)
      verify(cs).setNull(44, Types.VARCHAR)
      verify(cs).execute()
    }
  }

  "sdltUpdateLease" - {

    "call UPDATE_LEASE stored procedure with correct parameters and return updated=true" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      val expectedSql =
        "{ call LEASE_PROCS.UPDATE_LEASE(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
          "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
          "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
          "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
          "?, ?, ?, ?) }"

      when(conn.prepareCall(eqTo(expectedSql))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateLeaseRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        lease = LeasePayload(
          isAnnualRentOver1000 = Some("YES"),
          contractEndDate = Some("2030-12-31"),
          contractStartDate = Some("2025-01-01"),
          leaseType = Some("COMMERCIAL"),
          netPresentValue = Some("60000"),
          totalPremiumPayable = Some("15000"),
          rentFreePeriod = Some("YES"),
          startingRent = Some("13000"),
          startingRentEndDate = Some("2026-01-01"),
          laterRentKnown = Some("NO"),
          vatAmount = Some("2600")
        )
      )

      val result = repo.sdltUpdateLease(request).futureValue

      result.updated mustBe true

      verify(conn).prepareCall(expectedSql)
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "YES")
      verify(cs).setNull(4, Types.VARCHAR)
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setNull(6, Types.VARCHAR)
      verify(cs).setString(7, "2030-12-31")
      verify(cs).setString(8, "2025-01-01")
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setString(10, "COMMERCIAL")
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setString(12, "60000")
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).setString(14, "15000")
      verify(cs).setNull(15, Types.VARCHAR)
      verify(cs).setString(16, "YES")
      verify(cs).setNull(17, Types.VARCHAR)
      verify(cs).setNull(18, Types.VARCHAR)
      verify(cs).setNull(19, Types.VARCHAR)
      verify(cs).setNull(20, Types.VARCHAR)
      verify(cs).setString(21, "13000")
      verify(cs).setString(22, "2026-01-01")
      verify(cs).setString(23, "NO")
      verify(cs).setNull(24, Types.VARCHAR)
      verify(cs).setNull(25, Types.VARCHAR)
      verify(cs).setNull(26, Types.VARCHAR)
      verify(cs).setNull(27, Types.VARCHAR)
      verify(cs).setNull(28, Types.VARCHAR)
      verify(cs).setNull(29, Types.VARCHAR)
      verify(cs).setNull(30, Types.VARCHAR)
      verify(cs).setNull(31, Types.VARCHAR)
      verify(cs).setNull(32, Types.VARCHAR)
      verify(cs).setNull(33, Types.VARCHAR)
      verify(cs).setNull(34, Types.VARCHAR)
      verify(cs).setNull(35, Types.VARCHAR)
      verify(cs).setNull(36, Types.VARCHAR)
      verify(cs).setNull(37, Types.VARCHAR)
      verify(cs).setNull(38, Types.VARCHAR)
      verify(cs).setNull(39, Types.VARCHAR)
      verify(cs).setNull(40, Types.VARCHAR)
      verify(cs).setNull(41, Types.VARCHAR)
      verify(cs).setNull(42, Types.VARCHAR)
      verify(cs).setNull(43, Types.VARCHAR)
      verify(cs).setString(44, "2600")
      verify(cs).execute()
      verify(cs).close()
    }

    "handle minimal update with all optional fields being None" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateLeaseRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002",
        lease = LeasePayload(
          isAnnualRentOver1000 = None,
          contractEndDate = None,
          contractStartDate = None,
          leaseType = None,
          netPresentValue = None,
          totalPremiumPayable = None,
          rentFreePeriod = None,
          startingRent = None,
          startingRentEndDate = None,
          laterRentKnown = None,
          vatAmount = None
        )
      )

      val result = repo.sdltUpdateLease(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setNull(3, Types.VARCHAR)
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setNull(14, Types.VARCHAR)
      verify(cs).setNull(16, Types.VARCHAR)
      verify(cs).setNull(21, Types.VARCHAR)
      verify(cs).setNull(22, Types.VARCHAR)
      verify(cs).setNull(23, Types.VARCHAR)
      verify(cs).setNull(44, Types.VARCHAR)
      verify(cs).execute()
    }

    "handle partial update with mixed fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateLeaseRequest(
        stornId = "STORN77777",
        returnResourceRef = "100003",
        lease = LeasePayload(
          isAnnualRentOver1000 = Some("YES"),
          contractEndDate = None,
          contractStartDate = None,
          leaseType = Some("COMMERCIAL"),
          netPresentValue = None,
          totalPremiumPayable = None,
          rentFreePeriod = None,
          startingRent = Some("9500"),
          startingRentEndDate = None,
          laterRentKnown = None,
          vatAmount = None
        )
      )

      val result = repo.sdltUpdateLease(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN77777")
      verify(cs).setLong(2, 100003L)
      verify(cs).setString(3, "YES")
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setString(10, "COMMERCIAL")
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setString(21, "9500")
      verify(cs).setNull(44, Types.VARCHAR)
      verify(cs).execute()
    }
  }

  "sdltDeleteLease" - {

    "call DELETE_LEASE stored procedure with correct parameters and return deleted=true" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call LEASE_PROCS.DELETE_LEASE(?, ?) }")))
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteLeaseRequest(
        storn = "STORN12345",
        returnResourceRef = "100001"
      )

      val result = repo.sdltDeleteLease(request).futureValue

      result.deleted mustBe true

      verify(conn).prepareCall("{ call LEASE_PROCS.DELETE_LEASE(?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).execute()
      verify(cs).close()
    }

    "handle different return resource references" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteLeaseRequest(
        storn = "STORN99999",
        returnResourceRef = "100002"
      )

      val result = repo.sdltDeleteLease(request).futureValue

      result.deleted mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).execute()
    }

    "handle different storn formats" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteLeaseRequest(
        storn = "STORN-ABC-123",
        returnResourceRef = "100003"
      )

      val result = repo.sdltDeleteLease(request).futureValue

      result.deleted mustBe true

      verify(cs).setString(1, "STORN-ABC-123")
      verify(cs).setLong(2, 100003L)
      verify(cs).execute()
    }
  }

  "sdltUpdateTaxCalculation" - {

    "call Update_Tax stored procedure with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(
          eqTo("{ call TAX_CALCULATION_PROC.Update_Tax(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
        )
      )
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateTaxCalculationRequest(
        stornId = "STORN12345",
        returnResourceRef = "100001",
        amountPaid = Some("2000"),
        includesPenalty = Some("YES"),
        taxDue = Some("8000"),
        calcPenaltyDue = Some("500"),
        calcTaxDue = Some("8000"),
        calcTaxRate1 = Some("3"),
        calcTaxRate2 = Some("7"),
        calcTotalTaxPenaltyDue = Some("8500"),
        calcTotalNpvTax = Some("1000"),
        calcTotalPremiumTax = Some("7500"),
        taxDuePremium = Some("7500"),
        taxDueNpv = Some("1000"),
        honestyDeclaration = Some("YES")
      )

      val result = repo.sdltUpdateTaxCalculation(request).futureValue

      result.updated mustBe true

      verify(conn).prepareCall(
        "{ call TAX_CALCULATION_PROC.Update_Tax(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
      )
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "2000")
      verify(cs).setString(4, "YES")
      verify(cs).setString(5, "8000")
      verify(cs).setString(6, "500")
      verify(cs).setString(7, "8000")
      verify(cs).setString(8, "3")
      verify(cs).setString(9, "7")
      verify(cs).setString(10, "8500")
      verify(cs).setString(11, "1000")
      verify(cs).setString(12, "7500")
      verify(cs).setString(13, "7500")
      verify(cs).setString(14, "1000")
      verify(cs).setString(15, "YES")
      verify(cs).execute()
      verify(cs).close()
    }

    "handle minimal update with no optional fields" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateTaxCalculationRequest(
        stornId = "STORN99999",
        returnResourceRef = "100002"
      )

      val result = repo.sdltUpdateTaxCalculation(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setNull(3, Types.VARCHAR)
      verify(cs).setNull(4, Types.VARCHAR)
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setNull(6, Types.VARCHAR)
      verify(cs).setNull(7, Types.VARCHAR)
      verify(cs).setNull(8, Types.VARCHAR)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).setNull(14, Types.VARCHAR)
      verify(cs).setNull(15, Types.VARCHAR)
      verify(cs).execute()
    }

    "handle update with only partial fields changed" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateTaxCalculationRequest(
        stornId = "STORN77777",
        returnResourceRef = "100003",
        amountPaid = Some("3000"),
        taxDue = Some("3000"),
        calcTaxDue = Some("3000"),
        honestyDeclaration = Some("YES")
      )

      val result = repo.sdltUpdateTaxCalculation(request).futureValue

      result.updated mustBe true

      verify(cs).setString(1, "STORN77777")
      verify(cs).setLong(2, 100003L)
      verify(cs).setString(3, "3000")
      verify(cs).setString(5, "3000")
      verify(cs).setString(7, "3000")
      verify(cs).setString(15, "YES")
      verify(cs).execute()
    }
  }

  "sdltLockReturn" - {

    "call return_procs.lock_return with correct parameters and return success" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call return_procs.lock_return(?, ?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = LockReturnRequest(
        storn = "STORN12345",
        returnResourceRef = "100001",
        version = 1
      )

      val result = repo.sdltLockReturn(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call return_procs.lock_return(?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setLong(3, 1L)
      verify(cs).registerOutParameter(3, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltCreateSubmission" - {

    "call SUBMISSION_PROCS.CREATE_SUBMISSION with correct parameters and return success" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SUBMISSION_PROCS.CREATE_SUBMISSION(?, ?, ?, ?) }"))).thenReturn(cs)
      when(cs.getLong(4)).thenReturn(500001L)

      val repo = new SdltFormpRepository(db)

      val request = CreateSubmissionRequest(
        storn = "STORN12345",
        returnResourceRef = "100001",
        email = Some("agent@example.com")
      )

      val result = repo.sdltCreateSubmission(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call SUBMISSION_PROCS.CREATE_SUBMISSION(?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setString(3, "agent@example.com")
      verify(cs).registerOutParameter(4, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltUpdateSubmission" - {

    "call SUBMISSION_PROCS.UPDATE_SUBMISSION with all fields populated" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SUBMISSION_PROCS.UPDATE_SUBMISSION(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")))
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateSubmissionRequest(
        storn = "STORN12345",
        returnResourceRef = "100001",
        submission = SubmissionUpdate(
          IRMarkRecieved = Some("IRMARK-RCV"),
          utrn = Some("UTRN123"),
          email = Some("agent@example.com"),
          submissionRequestDate = Some("2025-01-15"),
          acceptedDate = Some("2025-01-16"),
          submittableStatus = Some("ACCEPTED"),
          govTalkErrorCode = Some("0000"),
          govTalkErrorType = Some("business"),
          govTalkErrorMessage = Some("none"),
          IRMarkSent = Some("IRMARK-SNT")
        )
      )

      val result = repo.sdltUpdateSubmission(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call SUBMISSION_PROCS.UPDATE_SUBMISSION(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setNull(3, Types.VARCHAR) // p_submission_receipt (no model field)
      verify(cs).setString(4, "IRMARK-RCV")
      verify(cs).setString(5, "UTRN123")
      verify(cs).setString(6, "agent@example.com")
      verify(cs).setDate(7, Date.valueOf("2025-01-15"))
      verify(cs).setDate(8, Date.valueOf("2025-01-16"))
      verify(cs).setString(9, "ACCEPTED")
      verify(cs).setString(10, "0000")
      verify(cs).setString(11, "business")
      verify(cs).setString(12, "none")
      verify(cs).setString(13, "IRMARK-SNT")
      verify(cs).execute()
      verify(cs).close()
    }

    "map None optional fields to NULL (VARCHAR and DATE)" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateSubmissionRequest(
        storn = "STORN99999",
        returnResourceRef = "100002",
        submission = SubmissionUpdate(
          IRMarkRecieved = None,
          utrn = None,
          email = None,
          submissionRequestDate = None,
          acceptedDate = None,
          submittableStatus = None,
          govTalkErrorCode = None,
          govTalkErrorType = None,
          govTalkErrorMessage = None,
          IRMarkSent = None
        )
      )

      val result = repo.sdltUpdateSubmission(request).futureValue

      result.success mustBe true

      verify(cs).setString(1, "STORN99999")
      verify(cs).setLong(2, 100002L)
      verify(cs).setNull(3, Types.VARCHAR)
      verify(cs).setNull(4, Types.VARCHAR)
      verify(cs).setNull(5, Types.VARCHAR)
      verify(cs).setNull(6, Types.VARCHAR)
      verify(cs).setNull(7, Types.DATE)
      verify(cs).setNull(8, Types.DATE)
      verify(cs).setNull(9, Types.VARCHAR)
      verify(cs).setNull(10, Types.VARCHAR)
      verify(cs).setNull(11, Types.VARCHAR)
      verify(cs).setNull(12, Types.VARCHAR)
      verify(cs).setNull(13, Types.VARCHAR)
      verify(cs).execute()
    }
  }

  "sdltCreateSubmissionErrorDetail" - {

    "call SUBMISSION_PROCS.CREATE_SUBMISSION_ERROR_DETAIL with correct parameters and return success" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SUBMISSION_PROCS.CREATE_SUBMISSION_ERROR_DETAIL(?, ?, ?, ?, ?) }")))
        .thenReturn(cs)
      when(cs.getLong(5)).thenReturn(700001L)

      val repo = new SdltFormpRepository(db)

      val request = CreateSubmissionErrorDetailRequest(
        storn = "STORN12345",
        returnResourceRef = "100001",
        submissionErrorDetails = SubmissionErrorDetail(
          position = "12",
          errorMessage = "Invalid postcode format"
        )
      )

      val result = repo.sdltCreateSubmissionErrorDetail(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call SUBMISSION_PROCS.CREATE_SUBMISSION_ERROR_DETAIL(?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).setLong(3, 12L)
      verify(cs).setString(4, "Invalid postcode format")
      verify(cs).registerOutParameter(5, Types.NUMERIC)
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltDeleteSubmissionErrorDetail" - {

    "call SUBMISSION_PROCS.DELETE_SUBMISSION_ERROR_DETAIL with correct parameters and return success" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SUBMISSION_PROCS.DELETE_SUBMISSION_ERROR_DETAIL(?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteSubmissionErrorDetailRequest(
        storn = "STORN12345",
        returnResourceRef = "100001"
      )

      val result = repo.sdltDeleteSubmissionErrorDetail(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call SUBMISSION_PROCS.DELETE_SUBMISSION_ERROR_DETAIL(?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setLong(2, 100001L)
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltDeleteGovTalkStatus" - {

    "call SUBMISSION_PROCS.DELETE_GOVTALK_STATUS with the result id and return success" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SUBMISSION_PROCS.DELETE_GOVTALK_STATUS(?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = DeleteGovTalkStatusRequest(resultId = "SUB123")

      val result = repo.sdltDeleteGovTalkStatus(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call SUBMISSION_PROCS.DELETE_GOVTALK_STATUS(?) }")
      verify(cs).setString(1, "SUB123")
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltInsertInitialGovTalkStatus" - {

    "call SUBMISSION_ADMIN.InsertInitialGovTalkStatus with all fields populated" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(eqTo("{ call SUBMISSION_ADMIN.InsertInitialGovTalkStatus(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"))
      )
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = InsertInitialGovTalkStatusRequest(
        userIdentifier = "STORN12345",
        formResultId = "SUB123",
        correlationId = "CORR-XYZ",
        govTalkStatus = GovTalkStatusInitial(
          formLock = "N",
          createTimestamp = "2025-01-15 10:30:00",
          endStateTimestamp = Some("2025-01-15 11:00:00"),
          lastMessageTimestamp = "2025-01-15 10:45:00",
          numberOfPolls = "3",
          pollInterval = "60",
          protocolStatus = "submission_acknowledgement",
          gatewayUrl = "https://gateway.example.com/submit"
        )
      )

      val result = repo.sdltInsertInitialGovTalkStatus(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call SUBMISSION_ADMIN.InsertInitialGovTalkStatus(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setString(2, "SUB123")
      verify(cs).setString(3, "CORR-XYZ")
      verify(cs).setString(4, "N")
      verify(cs).setTimestamp(5, Timestamp.valueOf("2025-01-15 10:30:00"))
      verify(cs).setTimestamp(6, Timestamp.valueOf("2025-01-15 11:00:00"))
      verify(cs).setTimestamp(7, Timestamp.valueOf("2025-01-15 10:45:00"))
      verify(cs).setLong(8, 3L)
      verify(cs).setLong(9, 60L)
      verify(cs).setString(10, "submission_acknowledgement")
      verify(cs).setString(11, "https://gateway.example.com/submit")
      verify(cs).execute()
      verify(cs).close()
    }

    "map a None endStateTimestamp to NULL TIMESTAMP" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = InsertInitialGovTalkStatusRequest(
        userIdentifier = "STORN99999",
        formResultId = "SUB999",
        correlationId = "CORR-999",
        govTalkStatus = GovTalkStatusInitial(
          formLock = "N",
          createTimestamp = "2025-02-01 09:00:00",
          endStateTimestamp = None,
          lastMessageTimestamp = "2025-02-01 09:00:00",
          numberOfPolls = "0",
          pollInterval = "30",
          protocolStatus = "submitted",
          gatewayUrl = "https://gateway.example.com/submit"
        )
      )

      val result = repo.sdltInsertInitialGovTalkStatus(request).futureValue

      result.success mustBe true

      verify(cs).setNull(6, Types.TIMESTAMP)
      verify(cs).setLong(8, 0L)
      verify(cs).setLong(9, 30L)
      verify(cs).execute()
    }
  }

  "sdltResetGovTalkStatus" - {

    "call SUBMISSION_ADMIN.ResetGovTalkStatusRecord with old and new protocol statuses" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(
        conn.prepareCall(eqTo("{ call SUBMISSION_ADMIN.ResetGovTalkStatusRecord(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"))
      )
        .thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = ResetGovTalkStatusRequest(
        userIdentifier = "STORN12345",
        formResultId = "SUB123",
        correlationId = "CORR-XYZ",
        govTalkStatus = GovTalkStatusReset(
          formLock = "N",
          createTimestamp = "2025-01-15 10:30:00",
          endStateTimestamp = Some("2025-01-15 11:00:00"),
          lastMessageTimestamp = "2025-01-15 10:45:00",
          numberOfPolls = "5",
          pollInterval = "60",
          protocolStatusOld = "error",
          protocolStatusNew = "submitted",
          gatewayUrl = "https://gateway.example.com/submit"
        )
      )

      val result = repo.sdltResetGovTalkStatus(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call SUBMISSION_ADMIN.ResetGovTalkStatusRecord(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setString(2, "SUB123")
      verify(cs).setString(3, "CORR-XYZ")
      verify(cs).setString(4, "N")
      verify(cs).setTimestamp(5, Timestamp.valueOf("2025-01-15 10:30:00"))
      verify(cs).setTimestamp(6, Timestamp.valueOf("2025-01-15 11:00:00"))
      verify(cs).setTimestamp(7, Timestamp.valueOf("2025-01-15 10:45:00"))
      verify(cs).setLong(8, 5L)
      verify(cs).setLong(9, 60L)
      verify(cs).setString(10, "error")
      verify(cs).setString(11, "submitted")
      verify(cs).setString(12, "https://gateway.example.com/submit")
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltUpdateGovTalkStatus" - {

    "call SUBMISSION_ADMIN.UpdateGovTalkStatus with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SUBMISSION_ADMIN.UpdateGovTalkStatus(?, ?, ?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateGovTalkStatusRequest(
        userIdentifier = "STORN12345",
        formResultId = "SUB123",
        endStateTimestamp = "2025-01-15 12:00:00",
        protocolStatus = "response"
      )

      val result = repo.sdltUpdateGovTalkStatus(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call SUBMISSION_ADMIN.UpdateGovTalkStatus(?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setString(2, "SUB123")
      verify(cs).setTimestamp(3, Timestamp.valueOf("2025-01-15 12:00:00"))
      verify(cs).setString(4, "response")
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltUpdateGovTalkStatusCorrelationId" - {

    "call SUBMISSION_ADMIN.UpdateGovTalkStatusCorr with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SUBMISSION_ADMIN.UpdateGovTalkStatusCorr(?, ?, ?, ?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateGovTalkStatusCorrelationIdRequest(
        userIdentifier = "STORN12345",
        formResultId = "SUB123",
        correlationId = "CORR-NEW",
        endStateTimestamp = "2025-01-15 12:30:00",
        protocolStatus = "acknowledgement"
      )

      val result = repo.sdltUpdateGovTalkStatusCorrelationId(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call SUBMISSION_ADMIN.UpdateGovTalkStatusCorr(?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setString(2, "SUB123")
      verify(cs).setString(3, "CORR-NEW")
      verify(cs).setTimestamp(4, Timestamp.valueOf("2025-01-15 12:30:00"))
      verify(cs).setString(5, "acknowledgement")
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltUpdateGovTalkStatusLock" - {

    "call SUBMISSION_ADMIN.UpdateGovTalkStatusLock with old/new lock ordering preserved" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SUBMISSION_ADMIN.UpdateGovTalkStatusLock(?, ?, ?, ?, ?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateGovTalkStatusLockRequest(
        userIdentifier = "STORN12345",
        formResultId = "SUB123",
        govTalkStatus = GovTalkStatusLock(
          formLockOld = "N",
          formLockNew = "Y",
          pollInterval = "90",
          gatewayUrl = "https://gateway.example.com/submit"
        )
      )

      val result = repo.sdltUpdateGovTalkStatusLock(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call SUBMISSION_ADMIN.UpdateGovTalkStatusLock(?, ?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setString(2, "SUB123")
      verify(cs).setString(3, "N") // form_lock_old
      verify(cs).setLong(4, 90L) // poll_interval
      verify(cs).setString(5, "https://gateway.example.com/submit")
      verify(cs).setString(6, "Y") // form_lock_new
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltUpdateGovTalkStatistics" - {

    "call SUBMISSION_ADMIN.UpdateGovTalkStatistics with correct parameters" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withTransaction(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SUBMISSION_ADMIN.UpdateGovTalkStatistics(?, ?, ?, ?, ?, ?) }"))).thenReturn(cs)

      val repo = new SdltFormpRepository(db)

      val request = UpdateGovTalkStatisticsRequest(
        userIdentifier = "STORN12345",
        formResultId = "SUB123",
        govTalkStatus = GovTalkStatusStatistics(
          lastMessageTimestamp = "2025-01-15 13:00:00",
          numberOfPolls = "7",
          pollInterval = "120",
          gatewayUrl = "https://gateway.example.com/submit"
        )
      )

      val result = repo.sdltUpdateGovTalkStatistics(request).futureValue

      result.success mustBe true

      verify(conn).prepareCall("{ call SUBMISSION_ADMIN.UpdateGovTalkStatistics(?, ?, ?, ?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setString(2, "SUB123")
      verify(cs).setTimestamp(3, Timestamp.valueOf("2025-01-15 13:00:00"))
      verify(cs).setLong(4, 7L)
      verify(cs).setLong(5, 120L)
      verify(cs).setString(6, "https://gateway.example.com/submit")
      verify(cs).execute()
      verify(cs).close()
    }
  }

  "sdltSelectGovTalkStatus" - {

    "call SUBMISSION_ADMIN.SelectGovTalkStatus and map the cursor row" in {
      val db       = mock[Database]
      val conn     = mock[Connection]
      val cs       = mock[CallableStatement]
      val rsStatus = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SUBMISSION_ADMIN.SelectGovTalkStatus(?, ?, ?) }"))).thenReturn(cs)
      when(cs.getObject(eqTo(3), eqTo(classOf[ResultSet]))).thenReturn(rsStatus)

      when(rsStatus.next()).thenReturn(true, false)
      when(rsStatus.getString("USER_IDENTIFIER")).thenReturn("STORN12345")
      when(rsStatus.getString("FORM_RESULT_ID")).thenReturn("SUB123")
      when(rsStatus.getString("CORRELATION_ID")).thenReturn("CORR-XYZ")
      when(rsStatus.getString("FORM_LOCK")).thenReturn("N")
      when(rsStatus.getString("CREATE_TIMESTAMP")).thenReturn("2025-01-15 10:30:00")
      when(rsStatus.getString("ENDSTATE_TIMESTAMP")).thenReturn("2025-01-15 11:00:00")
      when(rsStatus.getString("LAST_MESG_TIMESTAMP")).thenReturn("2025-01-15 10:45:00")
      when(rsStatus.getString("NUM_POLLS")).thenReturn("3")
      when(rsStatus.getString("POLL_INTERVAL")).thenReturn("60")
      when(rsStatus.getString("PROTOCOL_STATUS")).thenReturn("submitted")
      when(rsStatus.getString("GATEWAYURL")).thenReturn("https://gateway.example.com/submit")

      val repo = new SdltFormpRepository(db)

      val request = SelectGovTalkStatusRequest(
        userIdentifier = "STORN12345",
        formResultId = "SUB123"
      )

      val result = repo.sdltSelectGovTalkStatus(request).futureValue

      result.userIdentifier mustBe Some("STORN12345")
      result.formResultId mustBe Some("SUB123")
      result.correlationId mustBe Some("CORR-XYZ")
      result.formLock mustBe Some("N")
      result.createTimestamp mustBe Some("2025-01-15 10:30:00")
      result.endStateTimestamp mustBe Some("2025-01-15 11:00:00")
      result.lastMessageTimestamp mustBe Some("2025-01-15 10:45:00")
      result.numberOfPolls mustBe Some("3")
      result.pollInterval mustBe Some("60")
      result.protocolStatus mustBe Some("submitted")
      result.gatewayUrl mustBe Some("https://gateway.example.com/submit")

      verify(conn).prepareCall("{ call SUBMISSION_ADMIN.SelectGovTalkStatus(?, ?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).setString(2, "SUB123")
      verify(cs).registerOutParameter(3, OracleTypes.CURSOR)
      verify(cs).execute()
      verify(cs).close()
      verify(rsStatus).close()
    }

    "return an all-None response when the cursor is empty" in {
      val db       = mock[Database]
      val conn     = mock[Connection]
      val cs       = mock[CallableStatement]
      val rsStatus = mock[ResultSet]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getObject(eqTo(3), eqTo(classOf[ResultSet]))).thenReturn(rsStatus)
      when(rsStatus.next()).thenReturn(false)

      val repo = new SdltFormpRepository(db)

      val result = repo
        .sdltSelectGovTalkStatus(SelectGovTalkStatusRequest("STORN00000", "SUB000"))
        .futureValue

      result.userIdentifier mustBe None
      result.formResultId mustBe None
      result.correlationId mustBe None
      result.formLock mustBe None
      result.protocolStatus mustBe None
      result.gatewayUrl mustBe None

      verify(cs).execute()
      verify(rsStatus).close()
    }

    "return an all-None response when the cursor is null" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getObject(eqTo(3), eqTo(classOf[ResultSet]))).thenReturn(null)

      val repo = new SdltFormpRepository(db)

      val result = repo
        .sdltSelectGovTalkStatus(SelectGovTalkStatusRequest("STORN00000", "SUB000"))
        .futureValue

      result.formResultId mustBe None
      result.protocolStatus mustBe None

      verify(cs).execute()
    }
  }

  "sdltSelectGovTalkFormResultId" - {

    "call SUBMISSION_ADMIN.SelectGovTalkFormResultId and return the out-param value" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(eqTo("{ call SUBMISSION_ADMIN.SelectGovTalkFormResultId(?, ?) }"))).thenReturn(cs)
      when(cs.getString(2)).thenReturn("SUB123")

      val repo = new SdltFormpRepository(db)

      val request = SelectGovTalkFormResultIdRequest(userIdentifier = "STORN12345")

      val result = repo.sdltSelectGovTalkFormResultId(request).futureValue

      result.formResultId mustBe Some("SUB123")

      verify(conn).prepareCall("{ call SUBMISSION_ADMIN.SelectGovTalkFormResultId(?, ?) }")
      verify(cs).setString(1, "STORN12345")
      verify(cs).registerOutParameter(2, Types.VARCHAR)
      verify(cs).execute()
      verify(cs).close()
    }

    "map a null out-param to None" in {
      val db   = mock[Database]
      val conn = mock[Connection]
      val cs   = mock[CallableStatement]

      when(db.withConnection(anyArg[Connection => Any])).thenAnswer { inv =>
        val f = inv.getArgument(0, classOf[Connection => Any]); f(conn)
      }

      when(conn.prepareCall(anyArg[String])).thenReturn(cs)
      when(cs.getString(2)).thenReturn(null)

      val repo = new SdltFormpRepository(db)

      val result = repo
        .sdltSelectGovTalkFormResultId(SelectGovTalkFormResultIdRequest("STORN00000"))
        .futureValue

      result.formResultId mustBe None

      verify(cs).setString(1, "STORN00000")
      verify(cs).execute()
    }
  }

}

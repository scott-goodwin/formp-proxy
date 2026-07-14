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
import play.api.Logging
import play.api.db.{Database, NamedDatabase}
import uk.gov.hmrc.formpproxy.sdlt.models.*
import uk.gov.hmrc.formpproxy.sdlt.models.agents.*
import uk.gov.hmrc.formpproxy.sdlt.models.organisation.*
import uk.gov.hmrc.formpproxy.sdlt.models.returns.{ReturnForPurge, ReturnSummary, ReturnsForPurgeResponse, SdltReturnRecordResponse}
import uk.gov.hmrc.formpproxy.sdlt.models.vendor.*
import uk.gov.hmrc.formpproxy.sdlt.models.purchaser.*
import uk.gov.hmrc.formpproxy.sdlt.models.land.*
import uk.gov.hmrc.formpproxy.sdlt.models.residency.*
import uk.gov.hmrc.formpproxy.sdlt.models.transaction.*
import uk.gov.hmrc.formpproxy.shared.utils.CallableStatementUtils.*
import uk.gov.hmrc.formpproxy.sdlt.models.lease.*
import uk.gov.hmrc.formpproxy.sdlt.models.taxCalculation.*
import uk.gov.hmrc.formpproxy.sdlt.models.submission.*

import java.lang.Long
import java.sql.{CallableStatement, Connection, Date, ResultSet, Timestamp, Types}
import java.time.LocalDate
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

trait SdltSource {
  def sdltCreateReturn(request: CreateReturnRequest): Future[String]
  def sdltGetReturn(returnResourceRef: String, storn: String): Future[GetReturnRequest]
  def sdltGetReturns(request: GetReturnRecordsRequest): Future[SdltReturnRecordResponse]
  def sdltGetReturnsForPurge(request: GetReturnsForPurgeRequest): Future[ReturnsForPurgeResponse]
  def sdltDeleteReturn(request: DeleteReturnRequest): Future[DeleteReturnReturn]
  def sdltCreateVendor(request: CreateVendorRequest): Future[CreateVendorReturn]
  def sdltUpdateVendor(request: UpdateVendorRequest): Future[UpdateVendorReturn]
  def sdltDeleteVendor(request: DeleteVendorRequest): Future[DeleteVendorReturn]
  def sdltCreateReturnAgent(request: CreateReturnAgentRequest): Future[CreateReturnAgentReturn]
  def sdltUpdateReturnAgent(request: UpdateReturnAgentRequest): Future[UpdateReturnAgentReturn]
  def sdltDeleteReturnAgent(request: DeleteReturnAgentRequest): Future[DeleteReturnAgentReturn]
  def sdltUpdateReturnVersion(request: ReturnVersionUpdateRequest): Future[ReturnVersionUpdateReturn]
  def sdltGetOrganisation(req: String): Future[GetSdltOrgRequest]
  def sdltUpdatePredefinedAgent(req: UpdatePredefinedAgentRequest): Future[UpdatePredefinedAgentResponse]
  def sdltCreatePredefinedAgent(request: CreatePredefinedAgentRequest): Future[CreatePredefinedAgentResponse]
  def sdltDeletePredefinedAgent(req: DeletePredefinedAgentRequest): Future[DeletePredefinedAgentResponse]
  def sdltCreatePurchaser(request: CreatePurchaserRequest): Future[CreatePurchaserReturn]
  def sdltUpdatePurchaser(request: UpdatePurchaserRequest): Future[UpdatePurchaserReturn]
  def sdltDeletePurchaser(request: DeletePurchaserRequest): Future[DeletePurchaserReturn]
  def sdltCreateCompanyDetails(request: CreateCompanyDetailsRequest): Future[CreateCompanyDetailsReturn]
  def sdltUpdateCompanyDetails(request: UpdateCompanyDetailsRequest): Future[UpdateCompanyDetailsReturn]
  def sdltDeleteCompanyDetails(request: DeleteCompanyDetailsRequest): Future[DeleteCompanyDetailsReturn]
  def sdltCreateLand(request: CreateLandRequest): Future[CreateLandReturn]
  def sdltUpdateLand(request: UpdateLandRequest): Future[UpdateLandReturn]
  def sdltDeleteLand(request: DeleteLandRequest): Future[DeleteLandReturn]
  def sdltUpdateReturn(request: UpdateReturnRequest): Future[UpdateReturnReturn]
  def sdltCreateResidency(request: CreateResidencyRequest): Future[CreateResidencyReturn]
  def sdltUpdateResidency(request: UpdateResidencyRequest): Future[UpdateResidencyReturn]
  def sdltDeleteResidency(request: DeleteResidencyRequest): Future[DeleteResidencyReturn]
  def sdltUpdateTransaction(request: UpdateTransactionRequest): Future[UpdateTransactionReturn]
  def sdltCreateLease(request: CreateLeaseRequest): Future[CreateLeaseReturn]
  def sdltUpdateLease(request: UpdateLeaseRequest): Future[UpdateLeaseReturn]
  def sdltDeleteLease(request: DeleteLeaseRequest): Future[DeleteLeaseReturn]
  def sdltUpdateTaxCalculation(request: UpdateTaxCalculationRequest): Future[UpdateTaxCalculationReturn]
  def sdltLockReturn(request: LockReturnRequest): Future[LockReturnResponse]
  def sdltCreateSubmission(request: CreateSubmissionRequest): Future[CreateSubmissionReturn]
  def sdltUpdateSubmission(request: UpdateSubmissionRequest): Future[UpdateSubmissionReturn]
  def sdltCreateSubmissionErrorDetail(
    request: CreateSubmissionErrorDetailRequest
  ): Future[CreateSubmissionErrorDetailReturn]
  def sdltDeleteSubmissionErrorDetail(
    request: DeleteSubmissionErrorDetailRequest
  ): Future[DeleteSubmissionErrorDetailReturn]
  def sdltDeleteGovTalkStatus(request: DeleteGovTalkStatusRequest): Future[GovTalkStatusReturn]
  def sdltInsertInitialGovTalkStatus(request: InsertInitialGovTalkStatusRequest): Future[GovTalkStatusReturn]
  def sdltResetGovTalkStatus(request: ResetGovTalkStatusRequest): Future[GovTalkStatusReturn]
  def sdltUpdateGovTalkStatus(request: UpdateGovTalkStatusRequest): Future[GovTalkStatusReturn]
  def sdltUpdateGovTalkStatusCorrelationId(
    request: UpdateGovTalkStatusCorrelationIdRequest
  ): Future[GovTalkStatusReturn]
  def sdltUpdateGovTalkStatusLock(request: UpdateGovTalkStatusLockRequest): Future[GovTalkStatusReturn]
  def sdltUpdateGovTalkStatistics(request: UpdateGovTalkStatisticsRequest): Future[GovTalkStatusReturn]
  def sdltSelectGovTalkStatus(request: SelectGovTalkStatusRequest): Future[SelectGovTalkStatusResponse]
  def sdltSelectGovTalkFormResultId(
    request: SelectGovTalkFormResultIdRequest
  ): Future[SelectGovTalkFormResultIdResponse]
}

private final case class SchemeRow(schemeId: Long, version: Option[Int], email: Option[String])

@Singleton
class SdltFormpRepository @Inject() (@NamedDatabase("sdlt") db: Database)(implicit ec: ExecutionContext)
    extends SdltSource
    with Logging {

  override def sdltGetOrganisation(storn: String): Future[GetSdltOrgRequest] = {
    logger.info(s"[SDLT] getSDLTOrganisation(storn=$storn)")
    Future {
      db.withConnection { conn =>
        val cs = conn.prepareCall(
          "{ call SDLT_ORGANISATION_PROCS.Get_SDLT_Organisation(?, ?, ?) }"
        )
        try {
          cs.setString(1, storn)

          cs.registerOutParameter(2, OracleTypes.CURSOR)
          cs.registerOutParameter(3, OracleTypes.CURSOR)

          cs.execute()

          val sdltOrganisation = processResultSet(cs, 2, processSdltOrganisation)
          val agents           = processResultSetSeq(cs, 3, processAgent)

          GetSdltOrgRequest(
            storn = Some(storn),
            version = sdltOrganisation.flatMap(_.version),
            isReturnUser = sdltOrganisation.flatMap(_.isReturnUser),
            doNotDisplayWelcomePage = sdltOrganisation.flatMap(_.doNotDisplayWelcomePage),
            agents = agents
          )
        } finally cs.close()
      }
    }
  }

  override def sdltCreateReturn(request: CreateReturnRequest): Future[String] = Future {

    db.withTransaction { conn =>
      val submissionId = callCreateReturnSubmission(
        conn = conn,
        p_storn = request.stornId,
        p_purchaser_is_company = request.purchaserIsCompany,
        p_surname_comp_name = request.surNameOrCompanyName,
        p_land_house_number = request.houseNumber.map(_.toString),
        p_land_address_1 = request.addressLine1,
        p_land_address_2 = request.addressLine2,
        p_land_address_3 = request.addressLine3,
        p_land_address_4 = request.addressLine4,
        p_land_postcode = request.postcode,
        p_transaction_type = request.transactionType
      )

      submissionId.toString
    }
  }

  private def callCreateReturnSubmission(
    conn: Connection,
    p_storn: String,
    p_purchaser_is_company: String,
    p_surname_comp_name: String,
    p_land_house_number: Option[String] = None,
    p_land_address_1: String,
    p_land_address_2: Option[String] = None,
    p_land_address_3: Option[String] = None,
    p_land_address_4: Option[String] = None,
    p_land_postcode: Option[String] = None,
    p_transaction_type: String
  ): Long = {
    val cs = conn.prepareCall("{ call RETURN_PROCS.Create_Return(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setString(2, p_purchaser_is_company)
      cs.setString(3, p_surname_comp_name)
      cs.setOptionalString(4, p_land_house_number)
      cs.setString(5, p_land_address_1)
      cs.setOptionalString(6, p_land_address_2)
      cs.setOptionalString(7, p_land_address_3)
      cs.setOptionalString(8, p_land_address_4)
      cs.setOptionalString(9, p_land_postcode)
      cs.setString(10, p_transaction_type)
      cs.registerOutParameter(11, Types.NUMERIC)
      cs.execute()
      cs.getLong(11)
    } finally cs.close()
  }

  override def sdltGetReturn(returnResourceRef: String, storn: String): Future[GetReturnRequest] = {
    logger.info(s"[SDLT] sdltGetReturn(returnResourceRef=$returnResourceRef, storn=$storn)")
    Future {
      db.withConnection { conn =>
        val cs = conn.prepareCall(
          "{ call RETURN_PROCS.Get_Return_With_Residency(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
        )
        try {
          cs.setString(1, storn)
          cs.setLong(2, returnResourceRef.toLong)

          cs.registerOutParameter(3, OracleTypes.CURSOR)
          cs.registerOutParameter(4, OracleTypes.CURSOR)
          cs.registerOutParameter(5, OracleTypes.CURSOR)
          cs.registerOutParameter(6, OracleTypes.CURSOR)
          cs.registerOutParameter(7, OracleTypes.CURSOR)
          cs.registerOutParameter(8, OracleTypes.CURSOR)
          cs.registerOutParameter(9, OracleTypes.CURSOR)
          cs.registerOutParameter(10, OracleTypes.CURSOR)
          cs.registerOutParameter(11, OracleTypes.CURSOR)
          cs.registerOutParameter(12, OracleTypes.CURSOR)
          cs.registerOutParameter(13, OracleTypes.CURSOR)
          cs.registerOutParameter(14, OracleTypes.CURSOR)
          cs.registerOutParameter(15, OracleTypes.CURSOR)
          cs.registerOutParameter(16, OracleTypes.CURSOR)

          cs.execute()

          val sdltOrganisation       = processResultSet(cs, 3, processSdltOrganisation)
          val returnInfo             = processResultSet(cs, 4, processReturnInfo)
          val purchasers             = processResultSetSeq(cs, 5, processPurchaser)
          val companyDetails         = processResultSet(cs, 6, processCompanyDetails)
          val vendors                = processResultSetSeq(cs, 7, processVendor)
          val lands                  = processResultSetSeq(cs, 8, processLand)
          val transaction            = processResultSet(cs, 9, processTransaction)
          val returnAgents           = processResultSetSeq(cs, 10, processReturnAgent)
          val agent                  = processResultSetSeq(cs, 11, processAgent)
          val lease                  = processResultSet(cs, 12, processLease)
          val taxCalculation         = processResultSet(cs, 13, processTaxCalculation)
          val submission             = processResultSet(cs, 14, processSubmission)
          val submissionErrorDetails = processResultSet(cs, 15, processSubmissionErrorDetails)
          val residency              = processResultSet(cs, 16, processResidency)

          GetReturnRequest(
            stornId = Some(storn),
            returnResourceRef = Some(returnResourceRef),
            sdltOrganisation = sdltOrganisation,
            returnInfo = returnInfo,
            purchaser = if (purchasers.isEmpty) None else Some(purchasers),
            companyDetails = companyDetails,
            vendor = if (vendors.isEmpty) None else Some(vendors),
            land = if (lands.isEmpty) None else Some(lands),
            transaction = transaction,
            returnAgent = if (returnAgents.isEmpty) None else Some(returnAgents),
            agent = if (agent.isEmpty) None else Some(agent),
            lease = lease,
            taxCalculation = taxCalculation,
            submission = submission,
            submissionErrorDetails = submissionErrorDetails,
            residency = residency
          )
        } finally cs.close()
      }
    }
  }

  override def sdltGetReturns(request: GetReturnRecordsRequest): Future[SdltReturnRecordResponse] = {
    logger.info(s"[SDLT] sdltGetReturns($request)")
    Future {
      db.withConnection { conn =>
        val cs = conn.prepareCall(
          "{ call RETURN_PROCS.query_return(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
        )
        try {
          // Set up SPs IN/OUT params :: DEFAULTS assign as per SPs definition
          cs.setString(1, request.storn)
          cs.setNull(2, Types.VARCHAR) // p_utrn
          cs.setNull(3, Types.VARCHAR) // p_min_letter
          cs.setNull(4, Types.VARCHAR) // p_max_letter
          cs.setOptionalString(5, request.status) // p_status
          cs.setNull(6, Types.VARCHAR)
          if (request.deletionFlag) {
            cs.setString(7, "TRUE")
          } else {
            cs.setString(7, "FALSE")
          }
          val (order, orderBy)                      = request.sortSpec
          cs.setString(8, order) // p_order
          cs.setString(9, orderBy) // p_order_by
          cs.setLong(10, request.pageNumber.map(_.toLong).getOrElse(1L))
          cs.setOptionalString(11, request.pageType)
          // Output
          cs.registerOutParameter(12, OracleTypes.CURSOR)
          cs.registerOutParameter(13, OracleTypes.NUMERIC)
          cs.execute()
          // Fetch output params
          val totalcount: Long                      = cs.getLong(13)
          val returnSummaryList: Seq[ReturnSummary] = processResultSetSeq(cs, 12, processReturnSummary)

          SdltReturnRecordResponse(
            returnSummaryCount = Some(totalcount.toInt), // Inform consumer that count is not returned
            returnSummaryList = returnSummaryList.toList
          )
        } finally cs.close()
      }
    }
  }

  override def sdltGetReturnsForPurge(request: GetReturnsForPurgeRequest): Future[ReturnsForPurgeResponse] = {
    logger.info(s"[SDLT] sdltGetReturnsForPurge($request)")
    Future {
      db.withConnection { conn =>
        val cs = conn.prepareCall(
          "{ call RETURN_PROCS.get_returns_for_purge(?, ?) }"
        )
        try {
          cs.setDate(1, Date.valueOf(request.purgeDate))
          cs.registerOutParameter(2, OracleTypes.CURSOR)
          cs.execute()

          val returnsForPurge: Seq[ReturnForPurge] = processResultSetSeq(cs, 2, processReturnForPurge)

          ReturnsForPurgeResponse(returnsForPurge = returnsForPurge.toList)
        } finally cs.close()
      }
    }
  }

  private def processReturnForPurge(rs: ResultSet): ReturnForPurge =
    ReturnForPurge(
      storn = rs.getString("storn"),
      returnResourceRef = rs.getString("return_resource_ref"),
      status = Option(rs.getString("status")).getOrElse("")
    )

  override def sdltDeleteReturn(request: DeleteReturnRequest): Future[DeleteReturnReturn] = Future {
    db.withTransaction { conn =>
      callDeleteReturn(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong
      )
    }
  }

  private def callDeleteReturn(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long
  ): DeleteReturnReturn = {

    val cs = conn.prepareCall("{ call RETURN_PROCS.delete_return(?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.execute()

      DeleteReturnReturn(deleted = true)
    } finally cs.close()
  }

  private def processReturnSummary(rs: ResultSet): ReturnSummary =
    ReturnSummary(
      returnReference = rs.getString("return_resource_ref"),
      utrn = Option(rs.getString("utrn")),
      status = Option(rs.getString("status")).getOrElse(""),
      dateSubmitted = Try(LocalDate.parse(rs.getString("submitted_date"))).toOption,
      purchaserName = Option(rs.getString("name")).getOrElse(""),
      // purchaserName => causing crash if attempt to treat as a String output???
      address = Option(rs.getString("address")).getOrElse(""),
      agentReference = Option(rs.getString("agent"))
    )

  private def processResultSet[T](cs: CallableStatement, position: Int, processor: ResultSet => T): Option[T] = {
    val rs = cs.getObject(position, classOf[ResultSet])
    try
      if (rs != null && rs.next()) Some(processor(rs)) else None
    finally
      if (rs != null) rs.close()
  }

  private def processResultSetSeq[T](cs: CallableStatement, position: Int, processor: ResultSet => T): Seq[T] = {
    val rs = cs.getObject(position, classOf[ResultSet])
    try
      if (rs != null) {
        val buffer = scala.collection.mutable.ListBuffer[T]()
        while (rs.next())
          buffer += processor(rs)
        buffer.toSeq
      } else Seq.empty
    finally
      if (rs != null) rs.close()
  }

  private def processSdltOrganisation(rs: ResultSet): SdltOrganisation =
    SdltOrganisation(
      isReturnUser = Option(rs.getString("IS_RETURN_USER")),
      doNotDisplayWelcomePage = Option(rs.getString("DO_NOT_DISPLAY_WELCOME_PAGE")),
      storn = Option(rs.getString("STORN")),
      version = Option(rs.getString("VERSION"))
    )

  private def processReturnInfo(rs: ResultSet): ReturnInfo =
    ReturnInfo(
      returnID = Option(rs.getString("RETURN_ID")),
      storn = Option(rs.getString("STORN")),
      purchaserCounter = Option(rs.getString("PURCHASER_COUNTER")),
      vendorCounter = Option(rs.getString("VENDOR_COUNTER")),
      landCounter = Option(rs.getString("LAND_COUNTER")),
      purgeDate = Option(rs.getString("PURGE_DATE")),
      version = Option(rs.getString("VERSION")),
      mainPurchaserID = Option(rs.getString("MAIN_PURCHASER_ID")),
      mainVendorID = Option(rs.getString("MAIN_VENDOR_ID")),
      mainLandID = Option(rs.getString("MAIN_LAND_ID")),
      IRMarkGenerated = Option(rs.getString("IRMARK_GENERATED")),
      landCertForEachProp = Option(rs.getString("LAND_CERT_FOR_EACH_PROP")),
      returnResourceRef = Option(rs.getString("RETURN_RESOURCE_REF")),
      declaration = Option(rs.getString("DECLARATION")),
      status = Option(rs.getString("STATUS"))
    )

  private def processPurchaser(rs: ResultSet): Purchaser =
    Purchaser(
      purchaserID = Option(rs.getString("PURCHASER_ID")),
      returnID = Option(rs.getString("RETURN_ID")),
      isCompany = Option(rs.getString("IS_COMPANY")),
      isTrustee = Option(rs.getString("IS_TRUSTEE")),
      isConnectedToVendor = Option(rs.getString("IS_CONNECTED_TO_VENDOR")),
      isRepresentedByAgent = Option(rs.getString("IS_REPRESENTED_BY_AGENT")),
      title = Option(rs.getString("TITLE")),
      surname = Option(rs.getString("SURNAME")),
      forename1 = Option(rs.getString("FORENAME1")),
      forename2 = Option(rs.getString("FORENAME2")),
      companyName = Option(rs.getString("COMPANY_NAME")),
      houseNumber = Option(rs.getString("HOUSE_NUMBER")),
      address1 = Option(rs.getString("ADDRESS_1")),
      address2 = Option(rs.getString("ADDRESS_2")),
      address3 = Option(rs.getString("ADDRESS_3")),
      address4 = Option(rs.getString("ADDRESS_4")),
      postcode = Option(rs.getString("POSTCODE")),
      phone = Option(rs.getString("PHONE")),
      nino = Option(rs.getString("NINO")),
      purchaserResourceRef = Option(rs.getString("PURCHASER_RESOURCE_REF")),
      nextPurchaserID = Option(rs.getString("NEXT_PURCHASER_ID")),
      lMigrated = Option(rs.getString("L_MIGRATED")),
      createDate = Option(rs.getString("CREATE_DATE")),
      lastUpdateDate = Option(rs.getString("LAST_UPDATE_DATE")),
      isUkCompany = Option(rs.getString("IS_UK_COMPANY")),
      hasNino = Option(rs.getString("HAS_NINO")),
      dateOfBirth = Option(rs.getString("DATE_OF_BIRTH")),
      registrationNumber = Option(rs.getString("REGISTRATION_NUMBER")),
      placeOfRegistration = Option(rs.getString("PLACE_OF_REGISTRATION"))
    )

  private def processCompanyDetails(rs: ResultSet): CompanyDetails =
    CompanyDetails(
      companyDetailsID = Option(rs.getString("COMPANY_DETAILS_ID")),
      returnID = Option(rs.getString("RETURN_ID")),
      purchaserID = Option(rs.getString("PURCHASER_ID")),
      UTR = Option(rs.getString("UTR")),
      VATReference = Option(rs.getString("VAT_REFERENCE")),
      companyTypeBank = Option(rs.getString("COMPANY_TYPE_BANK")),
      companyTypeBuilder = Option(rs.getString("COMPANY_TYPE_BUILDER")),
      companyTypeBuildsoc = Option(rs.getString("COMPANY_TYPE_BUILDSOC")),
      companyTypeCentgov = Option(rs.getString("COMPANY_TYPE_CENTGOV")),
      companyTypeIndividual = Option(rs.getString("COMPANY_TYPE_INDIVIDUAL")),
      companyTypeInsurance = Option(rs.getString("COMPANY_TYPE_INSURANCE")),
      companyTypeLocalauth = Option(rs.getString("COMPANY_TYPE_LOCALAUTH")),
      companyTypeOthercharity = Option(rs.getString("COMPANY_TYPE_OTHERCHARITY")),
      companyTypeOthercompany = Option(rs.getString("COMPANY_TYPE_OTHERCOMPANY")),
      companyTypeOtherfinancial = Option(rs.getString("COMPANY_TYPE_OTHERFINANCIAL")),
      companyTypePartnership = Option(rs.getString("COMPANY_TYPE_PARTNERSHIP")),
      companyTypeProperty = Option(rs.getString("COMPANY_TYPE_PROPERTY")),
      companyTypePubliccorp = Option(rs.getString("COMPANY_TYPE_PUBLICCORP")),
      companyTypeSoletrader = Option(rs.getString("COMPANY_TYPE_SOLETRADER")),
      companyTypePensionfund = Option(rs.getString("COMPANY_TYPE_PENSIONFUND"))
    )

  private def processVendor(rs: ResultSet): Vendor =
    Vendor(
      vendorID = Option(rs.getString("VENDOR_ID")),
      returnID = Option(rs.getString("RETURN_ID")),
      title = Option(rs.getString("TITLE")),
      forename1 = Option(rs.getString("FORENAME1")),
      forename2 = Option(rs.getString("FORENAME2")),
      name = Option(rs.getString("NAME")),
      houseNumber = Option(rs.getString("HOUSE_NUMBER")),
      address1 = Option(rs.getString("ADDRESS_1")),
      address2 = Option(rs.getString("ADDRESS_2")),
      address3 = Option(rs.getString("ADDRESS_3")),
      address4 = Option(rs.getString("ADDRESS_4")),
      postcode = Option(rs.getString("POSTCODE")),
      isRepresentedByAgent = Option(rs.getString("IS_REPRESENTED_BY_AGENT")),
      vendorResourceRef = Option(rs.getString("VENDOR_RESOURCE_REF")),
      nextVendorID = Option(rs.getString("NEXT_VENDOR_ID")),
      lastUpdateDate = Option(rs.getString("LAST_UPDATE_DATE"))
    )

  private def processLand(rs: ResultSet): Land =
    Land(
      landID = Option(rs.getString("LAND_ID")),
      returnID = Option(rs.getString("RETURN_ID")),
      propertyType = Option(rs.getString("PROPERTY_TYPE")),
      interestCreatedTransferred = Option(rs.getString("INTEREST_TRANSFERRED_CREATED")),
      houseNumber = Option(rs.getString("HOUSE_NUMBER")),
      address1 = Option(rs.getString("ADDRESS_1")),
      address2 = Option(rs.getString("ADDRESS_2")),
      address3 = Option(rs.getString("ADDRESS_3")),
      address4 = Option(rs.getString("ADDRESS_4")),
      postcode = Option(rs.getString("POSTCODE")),
      landArea = Option(rs.getString("LAND_AREA")),
      areaUnit = Option(rs.getString("AREA_UNIT")),
      localAuthorityNumber = Option(rs.getString("LOCAL_AUTHORITY_NUMBER")),
      mineralRights = Option(rs.getString("MINERAL_RIGHTS")),
      NLPGUPRN = Option(rs.getString("NLPG_UPRN")),
      willSendPlanByPost = Option(rs.getString("WILL_SEND_PLAN_BY_POST")),
      titleNumber = Option(rs.getString("TITLE_NUMBER")),
      landResourceRef = Option(rs.getString("LAND_RESOURCE_REF")),
      nextLandID = Option(rs.getString("NEXT_LAND_ID")),
      DARPostcode = None,
      lastUpdateDate = Option(rs.getString("LAST_UPDATE_DATE"))
    )

  private def processTransaction(rs: ResultSet): Transaction =
    Transaction(
      transactionID = Option(rs.getString("TRANSACTION_ID")),
      returnID = Option(rs.getString("RETURN_ID")),
      claimingRelief = Option(rs.getString("CLAIMING_RELIEF")),
      reliefAmount = Option(rs.getString("RELIEF_AMOUNT")),
      reliefReason = Option(rs.getString("RELIEF_REASON")),
      reliefSchemeNumber = Option(rs.getString("RELIEF_SCHEME_NUMBER")),
      isLinked = Option(rs.getString("IS_LINKED")),
      totalConsiderationLinked = Option(rs.getString("TOTAL_CONSIDERATION_LINKED")),
      totalConsideration = Option(rs.getString("TOTAL_CONSIDERATION")),
      considerationBuild = Option(rs.getString("CONSIDERATION_BUILD")),
      considerationCash = Option(rs.getString("CONSIDERATION_CASH")),
      considerationContingent = Option(rs.getString("CONSIDERATION_CONTINGENT")),
      considerationDebt = Option(rs.getString("CONSIDERATION_DEBT")),
      considerationEmploy = Option(rs.getString("CONSIDERATION_EMPLOY")),
      considerationOther = Option(rs.getString("CONSIDERATION_OTHER")),
      considerationLand = Option(rs.getString("CONSIDERATION_LAND")),
      considerationServices = Option(rs.getString("CONSIDERATION_SERVICES")),
      considerationSharesQTD = Option(rs.getString("CONSIDERATION_SHARES_QTD")),
      considerationSharesUNQTD = Option(rs.getString("CONSIDERATION_SHARES_UNQTD")),
      considerationVAT = Option(rs.getString("CONSIDERATION_VAT")),
      includesChattel = Option(rs.getString("INCLUDES_CHATTEL")),
      includesGoodwill = Option(rs.getString("INCLUDES_GOODWILL")),
      includesOther = Option(rs.getString("INCLUDES_OTHER")),
      includesStock = Option(rs.getString("INCLUDES_STOCK")),
      usedAsFactory = Option(rs.getString("USED_AS_FACTORY")),
      usedAsHotel = Option(rs.getString("USED_AS_HOTEL")),
      usedAsIndustrial = Option(rs.getString("USED_AS_INDUSTRIAL")),
      usedAsOffice = Option(rs.getString("USED_AS_OFFICE")),
      usedAsOther = Option(rs.getString("USED_AS_OTHER")),
      usedAsShop = Option(rs.getString("USED_AS_SHOP")),
      usedAsWarehouse = Option(rs.getString("USED_AS_WAREHOUSE")),
      contractDate = Option(rs.getString("CONTRACT_DATE")),
      isDependantOnFutureEvent = Option(rs.getString("IS_DEPENDANT_ON_FUTURE_EVENT")),
      transactionDescription = Option(rs.getString("TRANSACTION_DESCRIPTION")),
      newTransactionDescription = Option(rs.getString("NEW_TRANSACTION_DESCRIPTION")),
      effectiveDate = Option(rs.getString("EFFECTIVE_DATE")),
      isLandExchanged = Option(rs.getString("IS_LAND_EXCHANGED")),
      exchangedLandHouseNumber = Option(rs.getString("EXCHANGED_LAND_HOUSE_NUMBER")),
      exchangedLandAddress1 = Option(rs.getString("EXCHANGED_LAND_ADDRESS_1")),
      exchangedLandAddress2 = Option(rs.getString("EXCHANGED_LAND_ADDRESS_2")),
      exchangedLandAddress3 = Option(rs.getString("EXCHANGED_LAND_ADDRESS_3")),
      exchangedLandAddress4 = Option(rs.getString("EXCHANGED_LAND_ADDRESS_4")),
      exchangedLandPostcode = Option(rs.getString("EXCHANGED_LAND_POSTCODE")),
      agreedToDeferPayment = Option(rs.getString("AGREED_TO_DEFER_PAYMENT")),
      postTransRulingApplied = Option(rs.getString("POST_TRANS_RULING_APPLIED")),
      isPursuantToPreviousOption = Option(rs.getString("IS_PURSUANT_TO_PREVIOUS_OPTION")),
      restrictionsAffectInterest = Option(rs.getString("RESTRICTIONS_AFFECT_INTEREST")),
      restrictionDetails = Option(rs.getString("RESTRICTION_DETAILS")),
      postTransRulingFollowed = Option(rs.getString("POST_TRANS_RULING_FOLLOWED")),
      isPartOfSaleOfBusiness = Option(rs.getString("IS_PART_OF_SALE_OF_BUSINESS")),
      totalConsiderationBusiness = Option(rs.getString("TOTAL_CONSIDERATION_BUSINESS"))
    )

  private def processReturnAgent(rs: ResultSet): ReturnAgent =
    ReturnAgent(
      returnAgentID = Option(rs.getString("RETURN_AGENT_ID")),
      returnID = Option(rs.getString("RETURN_ID")),
      agentType = Option(rs.getString("AGENT_TYPE")),
      name = Option(rs.getString("NAME")),
      houseNumber = Option(rs.getString("HOUSE_NUMBER")),
      address1 = Option(rs.getString("ADDRESS_1")),
      address2 = Option(rs.getString("ADDRESS_2")),
      address3 = Option(rs.getString("ADDRESS_3")),
      address4 = Option(rs.getString("ADDRESS_4")),
      postcode = Option(rs.getString("POSTCODE")),
      phone = Option(rs.getString("PHONE")),
      email = Option(rs.getString("EMAIL")),
      DXAddress = Option(rs.getString("DX_ADDRESS")),
      reference = Option(rs.getString("REFERENCE")),
      isAuthorised = Option(rs.getString("IS_AUTHORISED"))
    )

  private def processAgent(rs: ResultSet): Agent =
    Agent(
      agentId = Option(rs.getString("AGENT_ID")),
      storn = Option(rs.getString("STORN")),
      name = Option(rs.getString("NAME")),
      houseNumber = Option(rs.getString("HOUSE_NUMBER")),
      address1 = Option(rs.getString("ADDRESS_1")),
      address2 = Option(rs.getString("ADDRESS_2")),
      address3 = Option(rs.getString("ADDRESS_3")),
      address4 = Option(rs.getString("ADDRESS_4")),
      postcode = Option(rs.getString("POSTCODE")),
      phone = Option(rs.getString("PHONE")),
      email = Option(rs.getString("EMAIL")),
      dxAddress = Option(rs.getString("DX_ADDRESS")),
      agentResourceReference = Option(rs.getString("AGENT_RESOURCE_REF"))
    )

  private def processLease(rs: ResultSet): Lease =
    Lease(
      leaseID = Option(rs.getString("LEASE_ID")),
      returnID = Option(rs.getString("RETURN_ID")),
      isAnnualRentOver1000 = Option(rs.getString("IS_ANNUAL_RENT_OVER_1000")),
      breakClauseType = Option(rs.getString("BREAK_CLAUSE_TYPE")),
      breakClauseDate = Option(rs.getString("BREAK_CLAUSE_DATE")),
      leaseContReservedRent = Option(rs.getString("LEASE_CONT_RESERVED_RENT")),
      contractEndDate = Option(rs.getString("CONTRACT_END_DATE")),
      contractStartDate = Option(rs.getString("CONTRACT_START_DATE")),
      firstReviewDate = Option(rs.getString("FIRST_REVIEW_DATE")),
      leaseType = Option(rs.getString("LEASE_TYPE")),
      marketRent = Option(rs.getString("MARKET_RENT")),
      netPresentValue = Option(rs.getString("NET_PRESENT_VALUE")),
      optionToRenew = Option(rs.getString("OPTION_TO_RENEW")),
      totalPremiumPayable = Option(rs.getString("TOTAL_PREMIUM_PAYABLE")),
      rentChargeDate = Option(rs.getString("RENT_CHARGE_DATE")),
      rentFreePeriod = Option(rs.getString("RENT_FREE_PERIOD")),
      reviewClauseType = Option(rs.getString("REVIEW_CLAUSE_TYPE")),
      rentReviewFrequency = Option(rs.getString("RENT_REVIEW_FREQUENCY")),
      serviceCharge = Option(rs.getString("SERVICE_CHARGE")),
      serviceChargeFrequency = Option(rs.getString("SERVICE_CHARGE_FREQUENCY")),
      startingRent = Option(rs.getString("STARTING_RENT")),
      startingRentEndDate = Option(rs.getString("STARTING_RENT_END_DATE")),
      laterRentKnown = Option(rs.getString("LATER_RENT_KNOWN")),
      termsSurrendered = Option(rs.getString("TERMS_SURRENDERED")),
      considToLndlrdBuild = Option(rs.getString("CONSID_TO_LNDLRD_BUILD")),
      considToLndlrdContin = Option(rs.getString("CONSID_TO_LNDLRD_CONTIN")),
      considToLndlrdDebt = Option(rs.getString("CONSID_TO_LNDLRD_DEBT")),
      considToLndlrdEmploy = Option(rs.getString("CONSID_TO_LNDLRD_EMPLOY")),
      considToLndlrdOther = Option(rs.getString("CONSID_TO_LNDLRD_OTHER")),
      considToLndlrdLand = Option(rs.getString("CONSID_TO_LNDLRD_LAND")),
      considToLndlrdServices = Option(rs.getString("CONSID_TO_LNDLRD_SERVICES")),
      considToLndlrdSharedQTD = Option(rs.getString("CONSID_TO_LNDLRD_SHARED_QTD")),
      considToLndlrdSharedUNQTD = Option(rs.getString("CONSID_TO_LNDLRD_SHARED_UNQTD")),
      considToTenantBuild = Option(rs.getString("CONSID_TO_TENANT_BUILD")),
      considToTenantContin = Option(rs.getString("CONSID_TO_TENANT_CONTIN")),
      considToTenantEmploy = Option(rs.getString("CONSID_TO_TENANT_EMPLOY")),
      considToTenantOther = Option(rs.getString("CONSID_TO_TENANT_OTHER")),
      considToTenantLand = Option(rs.getString("CONSID_TO_TENANT_LAND")),
      considToTenantServices = Option(rs.getString("CONSID_TO_TENANT_SERVICES")),
      considToTenantSharesQTD = Option(rs.getString("CONSID_TO_TENANT_SHARES_QTD")),
      considToTenantSharesUNQTD = Option(rs.getString("CONSID_TO_TENANT_SHARES_UNQTD")),
      turnoverRent = Option(rs.getString("TURNOVER_RENT")),
      unasertainableRent = Option(rs.getString("UNASERTAINABLE_RENT")),
      VATAmount = Option(rs.getString("VAT_AMOUNT"))
    )

  private def processTaxCalculation(rs: ResultSet): TaxCalculation =
    TaxCalculation(
      taxCalculationID = Option(rs.getString("TAX_CALCULATION_ID")),
      returnID = Option(rs.getString("RETURN_ID")),
      amountPaid = Option(rs.getString("AMOUNT_PAID")),
      includesPenalty = Option(rs.getString("INCLUDES_PENALTY")),
      taxDue = Option(rs.getString("TAX_DUE")),
      taxDuePremium = Option(rs.getString("TAX_DUE_PREMIUM")),
      taxDueNPV = Option(rs.getString("TAX_DUE_NPV")),
      calcPenaltyDue = Option(rs.getString("CALC_PENALTY_DUE")),
      calcTaxDue = Option(rs.getString("CALC_TAX_DUE")),
      calcTaxRate1 = Option(rs.getString("CALC_TAX_RATE1")),
      calcTaxRate2 = Option(rs.getString("CALC_TAX_RATE2")),
      calcTotalTaxPenaltyDue = Option(rs.getString("CALC_TOTAL_TAX_PENALTY_DUE")),
      calcTotalNPVTax = Option(rs.getString("CALC_TOTAL_NPV_TAX")),
      calcTotalPremiumTax = Option(rs.getString("CALC_TOTAL_PREMIUM_TAX")),
      honestyDeclaration = None
    )

  private def processSubmission(rs: ResultSet): Submission =
    Submission(
      submissionID = Option(rs.getString("SUBMISSION_ID")),
      returnID = Option(rs.getString("RETURN_ID")),
      storn = Option(rs.getString("STORN")),
      submissionStatus = Option(rs.getString("SUBMISSION_STATUS")),
      govtalkMessageClass = Option(rs.getString("GOVTALK_MESSAGE_CLASS")),
      UTRN = Option(rs.getString("UTRN")),
      irmarkReceived = Option(rs.getString("IRMARK_RECEIVED")),
      submissionReceipt = Option(rs.getString("SUBMISSION_RECEIPT")),
      govtalkErrorCode = Option(rs.getString("GOVTALK_ERROR_CODE")),
      govtalkErrorType = Option(rs.getString("GOVTALK_ERROR_TYPE")),
      govtalkErrorMessage = Option(rs.getString("GOVTALK_ERROR_MESSAGE")),
      numPolls = Option(rs.getString("NUM_POLLS")),
      createDate = Option(rs.getString("CREATE_DATE")),
      lastUpdateDate = Option(rs.getString("LAST_UPDATE_DATE")),
      acceptedDate = Option(rs.getString("ACCEPTED_DATE")),
      submittedDate = Option(rs.getString("SUBMITTED_DATE")),
      email = Option(rs.getString("EMAIL")),
      submissionRequestDate = Option(rs.getString("SUBMISSION_REQUEST_DATE")),
      irmarkSent = Option(rs.getString("IR_MARK_SENT"))
    )

  private def processSubmissionErrorDetails(rs: ResultSet): SubmissionErrorDetails =
    SubmissionErrorDetails(
      errorDetailID = Option(rs.getString("ERROR_DETAIL_ID")),
      returnID = Option(rs.getString("RETURN_ID")),
      position = Option(rs.getString("POSITION")),
      errorMessage = Option(rs.getString("ERROR_MESSAGE")),
      storn = Option(rs.getString("STORN")),
      submissionID = Option(rs.getString("SUBMISSION_ID"))
    )

  private def processResidency(rs: ResultSet): Residency =
    Residency(
      residencyID = Option(rs.getString("RESIDENCY_ID")),
      isNonUkResidents = Option(rs.getString("IS_NON_UK_RESIDENTS")),
      isCloseCompany = Option(rs.getString("IS_CLOSE_COMPANY")),
      isCrownRelief = Option(rs.getString("IS_CROWN_RELIEF"))
    )

  override def sdltCreateVendor(request: CreateVendorRequest): Future[CreateVendorReturn] = Future {
    db.withTransaction { conn =>
      callCreateVendor(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_title = request.title,
        p_forename1 = request.forename1,
        p_forename2 = request.forename2,
        p_name = request.name,
        p_house_number = request.houseNumber,
        p_address_1 = request.addressLine1,
        p_address_2 = request.addressLine2,
        p_address_3 = request.addressLine3,
        p_address_4 = request.addressLine4,
        p_postcode = request.postcode,
        p_is_represented_by_agent = request.isRepresentedByAgent
      )
    }
  }

  private def callCreateVendor(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_title: Option[String],
    p_forename1: Option[String],
    p_forename2: Option[String],
    p_name: String,
    p_house_number: Option[String],
    p_address_1: String,
    p_address_2: Option[String],
    p_address_3: Option[String],
    p_address_4: Option[String],
    p_postcode: Option[String],
    p_is_represented_by_agent: String
  ): CreateVendorReturn = {

    val cs = conn.prepareCall("{ call VENDOR_PROCS.Create_Vendor(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setOptionalString(3, p_title)
      cs.setOptionalString(4, p_forename1)
      cs.setOptionalString(5, p_forename2)
      cs.setString(6, p_name)
      cs.setOptionalString(7, p_house_number)
      cs.setString(8, p_address_1)
      cs.setOptionalString(9, p_address_2)
      cs.setOptionalString(10, p_address_3)
      cs.setOptionalString(11, p_address_4)
      cs.setOptionalString(12, p_postcode)
      cs.setString(13, p_is_represented_by_agent)

      cs.registerOutParameter(14, Types.NUMERIC)
      cs.registerOutParameter(15, Types.NUMERIC)

      cs.execute()

      val vendorResourceRef = cs.getLong(14)
      val vendorId          = cs.getLong(15)

      CreateVendorReturn(
        vendorResourceRef = vendorResourceRef.toString,
        vendorId = vendorId.toString
      )
    } finally cs.close()
  }

  override def sdltUpdateVendor(request: UpdateVendorRequest): Future[UpdateVendorReturn] = Future {
    db.withTransaction { conn =>
      callUpdateVendor(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_title = request.title,
        p_forename1 = request.forename1,
        p_forename2 = request.forename2,
        p_name = request.name,
        p_house_number = request.houseNumber,
        p_address_1 = request.addressLine1,
        p_address_2 = request.addressLine2,
        p_address_3 = request.addressLine3,
        p_address_4 = request.addressLine4,
        p_postcode = request.postcode,
        p_is_represented_by_agent = request.isRepresentedByAgent,
        p_vendor_resource_ref = request.vendorResourceRef.toLong,
        p_next_vendor_id = request.nextVendorId
      )
    }
  }

  private def callUpdateVendor(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_title: Option[String],
    p_forename1: Option[String],
    p_forename2: Option[String],
    p_name: String,
    p_house_number: Option[String],
    p_address_1: String,
    p_address_2: Option[String],
    p_address_3: Option[String],
    p_address_4: Option[String],
    p_postcode: Option[String],
    p_is_represented_by_agent: String,
    p_vendor_resource_ref: Long,
    p_next_vendor_id: Option[String]
  ): UpdateVendorReturn = {

    val cs = conn.prepareCall("{ call VENDOR_PROCS.Update_Vendor(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setOptionalString(3, p_title)
      cs.setOptionalString(4, p_forename1)
      cs.setOptionalString(5, p_forename2)
      cs.setString(6, p_name)
      cs.setOptionalString(7, p_house_number)
      cs.setString(8, p_address_1)
      cs.setOptionalString(9, p_address_2)
      cs.setOptionalString(10, p_address_3)
      cs.setOptionalString(11, p_address_4)
      cs.setOptionalString(12, p_postcode)
      cs.setString(13, p_is_represented_by_agent)
      cs.setLong(14, p_vendor_resource_ref)
      cs.setOptionalString(15, p_next_vendor_id)

      cs.execute()

      UpdateVendorReturn(
        updated = true
      )

    } finally cs.close()
  }

  override def sdltDeleteVendor(request: DeleteVendorRequest): Future[DeleteVendorReturn] = Future {
    db.withTransaction { conn =>
      callDeleteVendor(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_vendor_resource_ref = request.vendorResourceRef.toLong
      )
    }
  }

  private def callDeleteVendor(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_vendor_resource_ref: Long
  ): DeleteVendorReturn = {

    val cs = conn.prepareCall("{ call VENDOR_PROCS.Delete_Vendor(?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setLong(3, p_vendor_resource_ref)

      cs.execute()

      DeleteVendorReturn(
        deleted = true
      )
    } finally cs.close()
  }

  override def sdltCreateReturnAgent(request: CreateReturnAgentRequest): Future[CreateReturnAgentReturn] = Future {
    db.withTransaction { conn =>
      callCreateReturnAgent(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_agent_type = request.agentType,
        p_name = request.name,
        p_house_number = request.houseNumber,
        p_address_1 = request.addressLine1,
        p_address_2 = request.addressLine2,
        p_address_3 = request.addressLine3,
        p_address_4 = request.addressLine4,
        p_postcode = request.postcode,
        p_phone = request.phoneNumber,
        p_email = request.email,
        p_dx_address = None,
        p_reference = request.agentReference,
        p_is_authorised = request.isAuthorised
      )
    }
  }

  private def callCreateReturnAgent(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_agent_type: String,
    p_name: String,
    p_house_number: Option[String],
    p_address_1: String,
    p_address_2: Option[String],
    p_address_3: Option[String],
    p_address_4: Option[String],
    p_postcode: String,
    p_phone: Option[String],
    p_email: Option[String],
    p_dx_address: Option[String],
    p_reference: Option[String],
    p_is_authorised: Option[String]
  ): CreateReturnAgentReturn = {

    val cs = conn.prepareCall(
      "{ call RETURN_AGENT_PROCS.Create_Return_Agent(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setString(3, p_agent_type)
      cs.setString(4, p_name)
      cs.setOptionalString(5, p_house_number)
      cs.setString(6, p_address_1)
      cs.setOptionalString(7, p_address_2)
      cs.setOptionalString(8, p_address_3)
      cs.setOptionalString(9, p_address_4)
      cs.setString(10, p_postcode)
      cs.setOptionalString(11, p_phone)
      cs.setOptionalString(12, p_email)
      cs.setOptionalString(13, p_dx_address)
      cs.setOptionalString(14, p_reference)
      cs.setOptionalString(15, p_is_authorised)

      cs.registerOutParameter(16, Types.NUMERIC)

      cs.execute()

      val returnAgentID = cs.getLong(16)

      CreateReturnAgentReturn(
        returnAgentID = returnAgentID.toString
      )
    } finally cs.close()
  }

  override def sdltUpdateReturnAgent(request: UpdateReturnAgentRequest): Future[UpdateReturnAgentReturn] = Future {
    db.withTransaction { conn =>
      callUpdateReturnAgent(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_agent_type = request.agentType,
        p_name = request.name,
        p_house_number = request.houseNumber,
        p_address_1 = request.addressLine1,
        p_address_2 = request.addressLine2,
        p_address_3 = request.addressLine3,
        p_address_4 = request.addressLine4,
        p_postcode = request.postcode,
        p_phone = request.phoneNumber,
        p_email = request.email,
        p_dx_address = None,
        p_reference = request.agentReference,
        p_is_authorised = request.isAuthorised
      )
    }
  }

  private def callUpdateReturnAgent(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_agent_type: String,
    p_name: String,
    p_house_number: Option[String],
    p_address_1: String,
    p_address_2: Option[String],
    p_address_3: Option[String],
    p_address_4: Option[String],
    p_postcode: String,
    p_phone: Option[String],
    p_email: Option[String],
    p_dx_address: Option[String],
    p_reference: Option[String],
    p_is_authorised: Option[String]
  ): UpdateReturnAgentReturn = {

    val cs = conn.prepareCall(
      "{ call RETURN_AGENT_PROCS.UPDATE_RETURN_AGENT(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setString(3, p_agent_type)
      cs.setString(4, p_name)
      cs.setOptionalString(5, p_house_number)
      cs.setString(6, p_address_1)
      cs.setOptionalString(7, p_address_2)
      cs.setOptionalString(8, p_address_3)
      cs.setOptionalString(9, p_address_4)
      cs.setString(10, p_postcode)
      cs.setOptionalString(11, p_phone)
      cs.setOptionalString(12, p_email)
      cs.setOptionalString(13, p_dx_address)
      cs.setOptionalString(14, p_reference)
      cs.setOptionalString(15, p_is_authorised)
      cs.execute()

      UpdateReturnAgentReturn(
        updated = true
      )
    } finally cs.close()
  }

  override def sdltDeleteReturnAgent(request: DeleteReturnAgentRequest): Future[DeleteReturnAgentReturn] = Future {
    db.withTransaction { conn =>
      callDeleteReturnAgent(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_agent_type = request.agentType
      )
    }
  }

  private def callDeleteReturnAgent(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_agent_type: String
  ): DeleteReturnAgentReturn = {

    val cs = conn.prepareCall("{ call RETURN_AGENT_PROCS.Delete_Return_Agent(?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setString(3, p_agent_type)
      cs.execute()

      DeleteReturnAgentReturn(deleted = true)
    } finally cs.close()
  }

  override def sdltUpdateReturnVersion(request: ReturnVersionUpdateRequest): Future[ReturnVersionUpdateReturn] =
    Future {
      db.withTransaction { conn =>
        callUpdateReturnVersion(
          conn = conn,
          p_storn = request.storn,
          p_return_resource_ref = request.returnResourceRef.toLong,
          p_version = request.currentVersion.toLong
        )
      }
    }

  private def callUpdateReturnVersion(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_version: Long
  ): ReturnVersionUpdateReturn = {

    val cs = conn.prepareCall("{ call RETURN_PROCS.Update_Version_Number(?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setLong(3, p_version)

      cs.registerOutParameter(3, Types.NUMERIC)

      cs.execute()

      val newVersion = cs.getInt(3)

      ReturnVersionUpdateReturn(newVersion = newVersion)
    } finally cs.close()
  }

  override def sdltUpdatePredefinedAgent(request: UpdatePredefinedAgentRequest): Future[UpdatePredefinedAgentResponse] =
    logger.info(s"[SDLT] sdltUpdatePredefinedAgent(request=$request)")
    Future {
      db.withTransaction { conn =>
        callUpdatePredefinedAgent(
          conn = conn,
          p_storn = request.storn,
          p_agent_resource_ref = request.agentResourceReference.toLong,
          p_name = request.agentName,
          p_house_number = request.houseNumber,
          p_address_1 = request.addressLine1,
          p_address_2 = request.addressLine2,
          p_address_3 = request.addressLine3,
          p_address_4 = request.addressLine4,
          p_postcode = request.postcode,
          p_phone = request.phone,
          p_email = request.email,
          p_dx_address = request.dxAddress
        )
      }
    }

  private def callUpdatePredefinedAgent(
    conn: Connection,
    p_storn: String,
    p_agent_resource_ref: Long,
    p_name: String,
    p_house_number: Option[String],
    p_address_1: Option[String],
    p_address_2: Option[String],
    p_address_3: Option[String],
    p_address_4: Option[String],
    p_postcode: Option[String],
    p_phone: Option[String],
    p_email: Option[String],
    p_dx_address: Option[String]
  ): UpdatePredefinedAgentResponse = {

    val cs = conn.prepareCall("{ call AGENT_PROCS.Update_Agent(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ? ) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_agent_resource_ref)
      cs.setString(3, p_name)
      cs.setOptionalString(4, p_house_number)
      cs.setOptionalString(5, p_address_1)
      cs.setOptionalString(6, p_address_2)
      cs.setOptionalString(7, p_address_3)
      cs.setOptionalString(8, p_address_4)
      cs.setOptionalString(9, p_postcode)
      cs.setOptionalString(10, p_phone)
      cs.setOptionalString(11, p_email)
      cs.setOptionalString(12, p_dx_address)
      cs.execute()
      UpdatePredefinedAgentResponse(updated = true)
    } finally cs.close()
  }

  override def sdltDeletePredefinedAgent(request: DeletePredefinedAgentRequest): Future[DeletePredefinedAgentResponse] =
    Future {
      db.withTransaction { conn =>
        callDeleteAgent(
          conn = conn,
          p_storn = request.storn,
          p_agent_resource_ref = request.agentReferenceNumber.toLong
        )
      }
    }

  private def callDeleteAgent(
    conn: Connection,
    p_storn: String,
    p_agent_resource_ref: Long
  ): DeletePredefinedAgentResponse = {

    val cs = conn.prepareCall("{ call AGENT_PROCS.Delete_Agent(?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_agent_resource_ref)
      cs.execute()

      DeletePredefinedAgentResponse(deleted = true)
    } finally cs.close()
  }

  override def sdltCreatePredefinedAgent(request: CreatePredefinedAgentRequest): Future[CreatePredefinedAgentResponse] =
    logger.info(s"[SDLT] sdltCreatePredefinedAgent(request=$request)")
    Future {
      db.withTransaction { conn =>
        callCreatePredefinedAgent(
          conn = conn,
          p_storn = request.storn,
          p_name = request.agentName,
          p_house_number = request.houseNumber,
          p_address_1 = request.addressLine1,
          p_address_2 = request.addressLine2,
          p_address_3 = request.addressLine3,
          p_address_4 = request.addressLine4,
          p_postcode = request.postcode,
          p_phone = request.phone,
          p_email = request.email,
          p_dx_address = request.dxAddress
        )

      }
    }

  private def callCreatePredefinedAgent(
    conn: Connection,
    p_storn: String,
    p_name: String,
    p_house_number: Option[String],
    p_address_1: Option[String],
    p_address_2: Option[String],
    p_address_3: Option[String],
    p_address_4: Option[String],
    p_postcode: Option[String],
    p_phone: Option[String],
    p_email: Option[String],
    p_dx_address: Option[String]
  ): CreatePredefinedAgentResponse = {

    val cs = conn.prepareCall(
      "{ call AGENT_PROCS.create_agent(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setString(2, p_name)
      cs.setOptionalString(3, p_house_number)
      cs.setOptionalString(4, p_address_1)
      cs.setOptionalString(5, p_address_2)
      cs.setOptionalString(6, p_address_3)
      cs.setOptionalString(7, p_address_4)
      cs.setOptionalString(8, p_postcode)
      cs.setOptionalString(9, p_phone)
      cs.setOptionalString(10, p_email)
      cs.setOptionalString(11, p_dx_address)

      cs.registerOutParameter(12, Types.NUMERIC)
      cs.registerOutParameter(13, Types.NUMERIC)
      cs.execute()

      val tempAgentId          = cs.getLong(12)
      val tempAgentResourceRef = cs.getLong(13)

      CreatePredefinedAgentResponse(
        agentResourceRef = Some(tempAgentResourceRef.toString),
        agentId = Some(tempAgentId.toString)
      )
    } finally cs.close()
  }

  override def sdltCreatePurchaser(request: CreatePurchaserRequest): Future[CreatePurchaserReturn] = Future {
    db.withTransaction { conn =>
      callCreatePurchaser(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_is_company = request.isCompany,
        p_is_trustee = request.isTrustee,
        p_is_connected_to_vendor = request.isConnectedToVendor,
        p_is_represented_by_agent = request.isRepresentedByAgent,
        p_title = request.title,
        p_surname = request.surname,
        p_forename1 = request.forename1,
        p_forename2 = request.forename2,
        p_company_name = request.companyName,
        p_house_number = request.houseNumber,
        p_address_1 = request.address1,
        p_address_2 = request.address2,
        p_address_3 = request.address3,
        p_address_4 = request.address4,
        p_postcode = request.postcode,
        p_phone = request.phone,
        p_nino = request.nino,
        p_has_nino = request.hasNino,
        p_date_of_birth = request.dateOfBirth,
        p_is_uk_company = request.isUkCompany,
        p_registration_number = request.registrationNumber,
        p_place_of_registration = request.placeOfRegistration
      )
    }
  }

  private def callCreatePurchaser(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_is_company: Option[String],
    p_is_trustee: Option[String],
    p_is_connected_to_vendor: Option[String],
    p_is_represented_by_agent: Option[String],
    p_title: Option[String],
    p_surname: Option[String],
    p_forename1: Option[String],
    p_forename2: Option[String],
    p_company_name: Option[String],
    p_house_number: Option[String],
    p_address_1: Option[String],
    p_address_2: Option[String],
    p_address_3: Option[String],
    p_address_4: Option[String],
    p_postcode: Option[String],
    p_phone: Option[String],
    p_nino: Option[String],
    p_has_nino: Option[String],
    p_date_of_birth: Option[String],
    p_is_uk_company: Option[String],
    p_registration_number: Option[String],
    p_place_of_registration: Option[String]
  ): CreatePurchaserReturn = {

    val cs = conn.prepareCall(
      "{ call PURCHASER_PROCS.Create_Purchaser(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setOptionalString(3, p_is_company)
      cs.setOptionalString(4, p_is_trustee)
      cs.setOptionalString(5, p_is_connected_to_vendor)
      cs.setOptionalString(6, p_is_represented_by_agent)
      cs.setOptionalString(7, p_title)
      cs.setOptionalString(8, p_surname)
      cs.setOptionalString(9, p_forename1)
      cs.setOptionalString(10, p_forename2)
      cs.setOptionalString(11, p_company_name)
      cs.setOptionalString(12, p_house_number)
      cs.setOptionalString(13, p_address_1)
      cs.setOptionalString(14, p_address_2)
      cs.setOptionalString(15, p_address_3)
      cs.setOptionalString(16, p_address_4)
      cs.setOptionalString(17, p_postcode)
      cs.setOptionalString(18, p_phone)
      cs.setOptionalString(19, p_nino)
      cs.setOptionalString(20, p_has_nino)
      cs.setOptionalString(21, p_date_of_birth)
      cs.setOptionalString(22, p_is_uk_company)
      cs.setOptionalString(23, p_registration_number)
      cs.setOptionalString(24, p_place_of_registration)

      cs.registerOutParameter(25, Types.NUMERIC)
      cs.registerOutParameter(26, Types.NUMERIC)

      cs.execute()

      val purchaserResourceRef = cs.getLong(25)
      val purchaserId          = cs.getLong(26)

      CreatePurchaserReturn(
        purchaserResourceRef = purchaserResourceRef.toString,
        purchaserId = purchaserId.toString
      )
    } finally cs.close()
  }

  override def sdltUpdatePurchaser(request: UpdatePurchaserRequest): Future[UpdatePurchaserReturn] = Future {
    db.withTransaction { conn =>
      callUpdatePurchaser(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_purchaser_resource_ref = request.purchaserResourceRef.toLong,
        p_is_company = request.isCompany,
        p_is_trustee = request.isTrustee,
        p_is_connected_to_vendor = request.isConnectedToVendor,
        p_is_represented_by_agent = request.isRepresentedByAgent,
        p_title = request.title,
        p_surname = request.surname,
        p_forename1 = request.forename1,
        p_forename2 = request.forename2,
        p_company_name = request.companyName,
        p_house_number = request.houseNumber,
        p_address_1 = request.address1,
        p_address_2 = request.address2,
        p_address_3 = request.address3,
        p_address_4 = request.address4,
        p_postcode = request.postcode,
        p_phone = request.phone,
        p_nino = request.nino,
        p_next_purchaser_id = request.nextPurchaserId,
        p_has_nino = request.hasNino,
        p_date_of_birth = request.dateOfBirth,
        p_is_uk_company = request.isUkCompany,
        p_registration_number = request.registrationNumber,
        p_place_of_registration = request.placeOfRegistration
      )
    }
  }

  private def callUpdatePurchaser(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_purchaser_resource_ref: Long,
    p_is_company: Option[String],
    p_is_trustee: Option[String],
    p_is_connected_to_vendor: Option[String],
    p_is_represented_by_agent: Option[String],
    p_title: Option[String],
    p_surname: Option[String],
    p_forename1: Option[String],
    p_forename2: Option[String],
    p_company_name: Option[String],
    p_house_number: Option[String],
    p_address_1: Option[String],
    p_address_2: Option[String],
    p_address_3: Option[String],
    p_address_4: Option[String],
    p_postcode: Option[String],
    p_phone: Option[String],
    p_nino: Option[String],
    p_next_purchaser_id: Option[String],
    p_has_nino: Option[String],
    p_date_of_birth: Option[String],
    p_is_uk_company: Option[String],
    p_registration_number: Option[String],
    p_place_of_registration: Option[String]
  ): UpdatePurchaserReturn = {

    val cs = conn.prepareCall(
      "{ call PURCHASER_PROCS.Update_Purchaser(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setLong(3, p_purchaser_resource_ref)
      cs.setOptionalString(4, p_is_company)
      cs.setOptionalString(5, p_is_trustee)
      cs.setOptionalString(6, p_is_connected_to_vendor)
      cs.setOptionalString(7, p_is_represented_by_agent)
      cs.setOptionalString(8, p_title)
      cs.setOptionalString(9, p_surname)
      cs.setOptionalString(10, p_forename1)
      cs.setOptionalString(11, p_forename2)
      cs.setOptionalString(12, p_company_name)
      cs.setOptionalString(13, p_house_number)
      cs.setOptionalString(14, p_address_1)
      cs.setOptionalString(15, p_address_2)
      cs.setOptionalString(16, p_address_3)
      cs.setOptionalString(17, p_address_4)
      cs.setOptionalString(18, p_postcode)
      cs.setOptionalString(19, p_phone)
      cs.setOptionalString(20, p_nino)
      cs.setOptionalString(21, p_next_purchaser_id)
      cs.setOptionalString(22, p_has_nino)
      cs.setOptionalString(23, p_date_of_birth)
      cs.setOptionalString(24, p_is_uk_company)
      cs.setOptionalString(25, p_registration_number)
      cs.setOptionalString(26, p_place_of_registration)

      cs.execute()

      UpdatePurchaserReturn(
        updated = true
      )

    } finally cs.close()
  }

  override def sdltDeletePurchaser(request: DeletePurchaserRequest): Future[DeletePurchaserReturn] = Future {
    db.withTransaction { conn =>
      callDeletePurchaser(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_purchaser_resource_ref = request.purchaserResourceRef.toLong
      )
    }
  }

  private def callDeletePurchaser(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_purchaser_resource_ref: Long
  ): DeletePurchaserReturn = {

    val cs = conn.prepareCall("{ call PURCHASER_PROCS.Delete_Purchaser(?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setLong(3, p_purchaser_resource_ref)

      cs.execute()

      DeletePurchaserReturn(
        deleted = true
      )
    } finally cs.close()
  }

  override def sdltCreateCompanyDetails(request: CreateCompanyDetailsRequest): Future[CreateCompanyDetailsReturn] =
    Future {
      db.withTransaction { conn =>
        callCreateCompanyDetails(
          conn = conn,
          p_storn = request.stornId,
          p_return_resource_ref = request.returnResourceRef.toLong,
          p_purchaser_resource_ref = request.purchaserResourceRef.toLong,
          p_utr = request.utr,
          p_vat_reference = request.vatReference,
          p_comp_type_bank = request.compTypeBank,
          p_comp_type_builder = request.compTypeBuilder,
          p_comp_type_buildsoc = request.compTypeBuildsoc,
          p_comp_type_centgov = request.compTypeCentgov,
          p_comp_type_individual = request.compTypeIndividual,
          p_comp_type_insurance = request.compTypeInsurance,
          p_comp_type_localauth = request.compTypeLocalauth,
          p_comp_type_ocharity = request.compTypeOcharity,
          p_comp_type_ocompany = request.compTypeOcompany,
          p_comp_type_ofinancial = request.compTypeOfinancial,
          p_comp_type_partship = request.compTypePartship,
          p_comp_type_property = request.compTypeProperty,
          p_comp_type_publiccorp = request.compTypePubliccorp,
          p_comp_type_soletrader = request.compTypeSoletrader,
          p_comp_type_penfund = request.compTypePenfund
        )
      }
    }

  private def callCreateCompanyDetails(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_purchaser_resource_ref: Long,
    p_utr: Option[String],
    p_vat_reference: Option[String],
    p_comp_type_bank: Option[String],
    p_comp_type_builder: Option[String],
    p_comp_type_buildsoc: Option[String],
    p_comp_type_centgov: Option[String],
    p_comp_type_individual: Option[String],
    p_comp_type_insurance: Option[String],
    p_comp_type_localauth: Option[String],
    p_comp_type_ocharity: Option[String],
    p_comp_type_ocompany: Option[String],
    p_comp_type_ofinancial: Option[String],
    p_comp_type_partship: Option[String],
    p_comp_type_property: Option[String],
    p_comp_type_publiccorp: Option[String],
    p_comp_type_soletrader: Option[String],
    p_comp_type_penfund: Option[String]
  ): CreateCompanyDetailsReturn = {

    val cs = conn.prepareCall(
      "{ call PURCHASER_PROCS.Create_Company_Details(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setLong(3, p_purchaser_resource_ref)
      cs.setOptionalString(4, p_utr)
      cs.setOptionalString(5, p_vat_reference)
      cs.setOptionalString(6, p_comp_type_bank)
      cs.setOptionalString(7, p_comp_type_builder)
      cs.setOptionalString(8, p_comp_type_buildsoc)
      cs.setOptionalString(9, p_comp_type_centgov)
      cs.setOptionalString(10, p_comp_type_individual)
      cs.setOptionalString(11, p_comp_type_insurance)
      cs.setOptionalString(12, p_comp_type_localauth)
      cs.setOptionalString(13, p_comp_type_ocharity)
      cs.setOptionalString(14, p_comp_type_ocompany)
      cs.setOptionalString(15, p_comp_type_ofinancial)
      cs.setOptionalString(16, p_comp_type_partship)
      cs.setOptionalString(17, p_comp_type_property)
      cs.setOptionalString(18, p_comp_type_publiccorp)
      cs.setOptionalString(19, p_comp_type_soletrader)
      cs.setOptionalString(20, p_comp_type_penfund)

      cs.registerOutParameter(21, Types.NUMERIC)

      cs.execute()

      val companyDetailsId = cs.getLong(21)

      CreateCompanyDetailsReturn(
        companyDetailsId = companyDetailsId.toString
      )
    } finally cs.close()
  }

  override def sdltUpdateCompanyDetails(request: UpdateCompanyDetailsRequest): Future[UpdateCompanyDetailsReturn] =
    Future {
      db.withTransaction { conn =>
        callUpdateCompanyDetails(
          conn = conn,
          p_storn = request.stornId,
          p_return_resource_ref = request.returnResourceRef.toLong,
          p_purchaser_resource_ref = request.purchaserResourceRef.toLong,
          p_utr = request.utr,
          p_vat_reference = request.vatReference,
          p_comp_type_bank = request.compTypeBank,
          p_comp_type_builder = request.compTypeBuilder,
          p_comp_type_buildsoc = request.compTypeBuildsoc,
          p_comp_type_centgov = request.compTypeCentgov,
          p_comp_type_individual = request.compTypeIndividual,
          p_comp_type_insurance = request.compTypeInsurance,
          p_comp_type_localauth = request.compTypeLocalauth,
          p_comp_type_ocharity = request.compTypeOcharity,
          p_comp_type_ocompany = request.compTypeOcompany,
          p_comp_type_ofinancial = request.compTypeOfinancial,
          p_comp_type_partship = request.compTypePartship,
          p_comp_type_property = request.compTypeProperty,
          p_comp_type_publiccorp = request.compTypePubliccorp,
          p_comp_type_soletrader = request.compTypeSoletrader,
          p_comp_type_penfund = request.compTypePenfund
        )
      }
    }

  private def callUpdateCompanyDetails(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_purchaser_resource_ref: Long,
    p_utr: Option[String],
    p_vat_reference: Option[String],
    p_comp_type_bank: Option[String],
    p_comp_type_builder: Option[String],
    p_comp_type_buildsoc: Option[String],
    p_comp_type_centgov: Option[String],
    p_comp_type_individual: Option[String],
    p_comp_type_insurance: Option[String],
    p_comp_type_localauth: Option[String],
    p_comp_type_ocharity: Option[String],
    p_comp_type_ocompany: Option[String],
    p_comp_type_ofinancial: Option[String],
    p_comp_type_partship: Option[String],
    p_comp_type_property: Option[String],
    p_comp_type_publiccorp: Option[String],
    p_comp_type_soletrader: Option[String],
    p_comp_type_penfund: Option[String]
  ): UpdateCompanyDetailsReturn = {

    val cs = conn.prepareCall(
      "{ call PURCHASER_PROCS.Update_Company_Details(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setLong(3, p_purchaser_resource_ref)
      cs.setOptionalString(4, p_utr)
      cs.setOptionalString(5, p_vat_reference)
      cs.setOptionalString(6, p_comp_type_bank)
      cs.setOptionalString(7, p_comp_type_builder)
      cs.setOptionalString(8, p_comp_type_buildsoc)
      cs.setOptionalString(9, p_comp_type_centgov)
      cs.setOptionalString(10, p_comp_type_individual)
      cs.setOptionalString(11, p_comp_type_insurance)
      cs.setOptionalString(12, p_comp_type_localauth)
      cs.setOptionalString(13, p_comp_type_ocharity)
      cs.setOptionalString(14, p_comp_type_ocompany)
      cs.setOptionalString(15, p_comp_type_ofinancial)
      cs.setOptionalString(16, p_comp_type_partship)
      cs.setOptionalString(17, p_comp_type_property)
      cs.setOptionalString(18, p_comp_type_publiccorp)
      cs.setOptionalString(19, p_comp_type_soletrader)
      cs.setOptionalString(20, p_comp_type_penfund)

      cs.execute()

      UpdateCompanyDetailsReturn(
        updated = true
      )

    } finally cs.close()
  }

  override def sdltDeleteCompanyDetails(request: DeleteCompanyDetailsRequest): Future[DeleteCompanyDetailsReturn] =
    Future {
      db.withTransaction { conn =>
        callDeleteCompanyDetails(
          conn = conn,
          p_storn = request.storn,
          p_return_resource_ref = request.returnResourceRef.toLong
        )
      }
    }

  private def callDeleteCompanyDetails(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long
  ): DeleteCompanyDetailsReturn = {

    val cs = conn.prepareCall("{ call PURCHASER_PROCS.Delete_Company_Details(?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)

      cs.execute()

      DeleteCompanyDetailsReturn(
        deleted = true
      )
    } finally cs.close()
  }

  override def sdltCreateLand(request: CreateLandRequest): Future[CreateLandReturn] = Future {
    db.withTransaction { conn =>
      callCreateLand(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_property_type = request.propertyType,
        p_interest_transferred_created = request.interestTransferredCreated,
        p_house_number = request.houseNumber,
        p_address_1 = request.addressLine1,
        p_address_2 = request.addressLine2,
        p_address_3 = request.addressLine3,
        p_address_4 = request.addressLine4,
        p_postcode = request.postcode,
        p_land_area = request.landArea,
        p_area_unit = request.areaUnit,
        p_local_authority_number = request.localAuthorityNumber,
        p_mineral_rights = request.mineralRights,
        p_nlpg_uprn = request.nlpgUprn,
        p_will_send_plans_by_post = request.willSendPlansByPost,
        p_title_number = request.titleNumber
      )
    }
  }

  private def callCreateLand(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_property_type: String,
    p_interest_transferred_created: String,
    p_house_number: Option[String],
    p_address_1: String,
    p_address_2: Option[String],
    p_address_3: Option[String],
    p_address_4: Option[String],
    p_postcode: Option[String],
    p_land_area: Option[String],
    p_area_unit: Option[String],
    p_local_authority_number: Option[String],
    p_mineral_rights: Option[String],
    p_nlpg_uprn: Option[String],
    p_will_send_plans_by_post: Option[String],
    p_title_number: Option[String]
  ): CreateLandReturn = {

    val cs = conn.prepareCall(
      "{ call LAND_PROCS.Create_Land(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setString(3, p_property_type)
      cs.setString(4, p_interest_transferred_created)
      cs.setOptionalString(5, p_house_number)
      cs.setString(6, p_address_1)
      cs.setOptionalString(7, p_address_2)
      cs.setOptionalString(8, p_address_3)
      cs.setOptionalString(9, p_address_4)
      cs.setOptionalString(10, p_postcode)
      cs.setOptionalString(11, p_land_area)
      cs.setOptionalString(12, p_area_unit)
      cs.setOptionalString(13, p_local_authority_number)
      cs.setOptionalString(14, p_mineral_rights)
      cs.setOptionalString(15, p_nlpg_uprn)
      cs.setOptionalString(16, p_will_send_plans_by_post)
      cs.setOptionalString(17, p_title_number)

      cs.registerOutParameter(18, Types.NUMERIC)
      cs.registerOutParameter(19, Types.NUMERIC)

      cs.execute()

      val landId          = cs.getLong(18)
      val landResourceRef = cs.getLong(19)

      CreateLandReturn(
        landResourceRef = landResourceRef.toString,
        landId = landId.toString
      )
    } finally cs.close()
  }

  override def sdltUpdateLand(request: UpdateLandRequest): Future[UpdateLandReturn] = Future {
    db.withTransaction { conn =>
      callUpdateLand(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_property_type = request.propertyType,
        p_interest_transferred_created = request.interestTransferredCreated,
        p_house_number = request.houseNumber,
        p_address_1 = request.addressLine1,
        p_address_2 = request.addressLine2,
        p_address_3 = request.addressLine3,
        p_address_4 = request.addressLine4,
        p_postcode = request.postcode,
        p_land_area = request.landArea,
        p_area_unit = request.areaUnit,
        p_local_authority_number = request.localAuthorityNumber,
        p_mineral_rights = request.mineralRights,
        p_nlpg_uprn = request.nlpgUprn,
        p_will_send_plans_by_post = request.willSendPlansByPost,
        p_title_number = request.titleNumber,
        p_land_resource_ref = request.landResourceRef.toLong,
        p_next_land_id = request.nextLandId
      )
    }
  }

  private def callUpdateLand(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_property_type: String,
    p_interest_transferred_created: String,
    p_house_number: Option[String],
    p_address_1: String,
    p_address_2: Option[String],
    p_address_3: Option[String],
    p_address_4: Option[String],
    p_postcode: Option[String],
    p_land_area: Option[String],
    p_area_unit: Option[String],
    p_local_authority_number: Option[String],
    p_mineral_rights: Option[String],
    p_nlpg_uprn: Option[String],
    p_will_send_plans_by_post: Option[String],
    p_title_number: Option[String],
    p_land_resource_ref: Long,
    p_next_land_id: Option[String]
  ): UpdateLandReturn = {

    val cs = conn.prepareCall(
      "{ call LAND_PROCS.Update_Land(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setString(3, p_property_type)
      cs.setString(4, p_interest_transferred_created)
      cs.setOptionalString(5, p_house_number)
      cs.setString(6, p_address_1)
      cs.setOptionalString(7, p_address_2)
      cs.setOptionalString(8, p_address_3)
      cs.setOptionalString(9, p_address_4)
      cs.setOptionalString(10, p_postcode)
      cs.setOptionalString(11, p_land_area)
      cs.setOptionalString(12, p_area_unit)
      cs.setOptionalString(13, p_local_authority_number)
      cs.setOptionalString(14, p_mineral_rights)
      cs.setOptionalString(15, p_nlpg_uprn)
      cs.setOptionalString(16, p_will_send_plans_by_post)
      cs.setOptionalString(17, p_title_number)
      cs.setLong(18, p_land_resource_ref)
      cs.setOptionalString(19, p_next_land_id)

      cs.execute()

      UpdateLandReturn(
        updated = true
      )

    } finally cs.close()
  }

  override def sdltDeleteLand(request: DeleteLandRequest): Future[DeleteLandReturn] = Future {
    db.withTransaction { conn =>
      callDeleteLand(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_land_resource_ref = request.landResourceRef.toLong
      )
    }
  }

  private def callDeleteLand(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_land_resource_ref: Long
  ): DeleteLandReturn = {

    val cs = conn.prepareCall("{ call LAND_PROCS.Delete_Land(?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setLong(3, p_land_resource_ref)

      cs.execute()

      DeleteLandReturn(
        deleted = true
      )
    } finally cs.close()
  }

  override def sdltUpdateReturn(request: UpdateReturnRequest): Future[UpdateReturnReturn] = Future {
    db.withTransaction { conn =>
      callUpdateReturn(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_main_purchaser_id = request.mainPurchaserID,
        p_main_vendor_id = request.mainVendorID,
        p_main_land_id = request.mainLandID,
        p_irmark_generated = request.IRMarkGenerated,
        p_land_cert_for_each_prop = request.landCertForEachProp,
        p_declaration = request.declaration
      )
    }
  }

  private def callUpdateReturn(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_main_purchaser_id: Option[String] = None,
    p_main_vendor_id: Option[String] = None,
    p_main_land_id: Option[String] = None,
    p_irmark_generated: Option[String] = None,
    p_land_cert_for_each_prop: Option[String] = None,
    p_declaration: Option[String] = None
  ): UpdateReturnReturn = {

    val cs = conn.prepareCall("{ call RETURN_PROCS.Update_Return(?, ?, ?, ?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setOptionalString(3, p_main_purchaser_id)
      cs.setOptionalString(4, p_main_vendor_id)
      cs.setOptionalString(5, p_main_land_id)
      cs.setOptionalString(6, p_irmark_generated)
      cs.setOptionalString(7, p_land_cert_for_each_prop)
      cs.setOptionalString(8, p_declaration)

      cs.execute()

      UpdateReturnReturn(
        updated = true
      )

    } finally cs.close()
  }

  override def sdltCreateResidency(request: CreateResidencyRequest): Future[CreateResidencyReturn] = Future {
    db.withTransaction { conn =>
      callCreateResidency(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_is_non_uk_residents = request.residency.isNonUkResidents,
        p_is_close_company = request.residency.isCompany,
        p_is_crown_relief = request.residency.isCrownRelief
      )
    }
  }

  private def callCreateResidency(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_is_non_uk_residents: String,
    p_is_close_company: String,
    p_is_crown_relief: String
  ): CreateResidencyReturn = {

    val cs = conn.prepareCall("{ call RESIDENCY_PROCS.CREATE_RESIDENCY(?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setString(3, p_is_non_uk_residents)
      cs.setString(4, p_is_close_company)
      cs.setString(5, p_is_crown_relief)

      cs.execute()

      CreateResidencyReturn(created = true)
    } finally cs.close()
  }

  override def sdltUpdateResidency(request: UpdateResidencyRequest): Future[UpdateResidencyReturn] = Future {
    db.withTransaction { conn =>
      callUpdateResidency(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_is_non_uk_residents = request.residency.isNonUkResidents,
        p_is_close_company = request.residency.isCompany,
        p_is_crown_relief = request.residency.isCrownRelief
      )
    }
  }

  private def callUpdateResidency(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_is_non_uk_residents: String,
    p_is_close_company: String,
    p_is_crown_relief: String
  ): UpdateResidencyReturn = {

    val cs = conn.prepareCall("{ call RESIDENCY_PROCS.UPDATE_RESIDENCY(?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setString(3, p_is_non_uk_residents)
      cs.setString(4, p_is_close_company)
      cs.setString(5, p_is_crown_relief)

      cs.execute()

      UpdateResidencyReturn(updated = true)
    } finally cs.close()
  }

  override def sdltDeleteResidency(request: DeleteResidencyRequest): Future[DeleteResidencyReturn] = Future {
    db.withTransaction { conn =>
      callDeleteResidency(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong
      )
    }
  }

  private def callDeleteResidency(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long
  ): DeleteResidencyReturn = {

    val cs = conn.prepareCall("{ call RESIDENCY_PROCS.DELETE_RESIDENCY(?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)

      cs.execute()

      DeleteResidencyReturn(deleted = true)
    } finally cs.close()
  }

  override def sdltUpdateTransaction(request: UpdateTransactionRequest): Future[UpdateTransactionReturn] = Future {
    db.withTransaction { conn =>
      callUpdateTransaction(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_claiming_relief = request.transaction.claimingRelief,
        p_relief_amount = request.transaction.reliefAmount,
        p_relief_reason = request.transaction.reliefReason,
        p_relief_scheme_number = request.transaction.reliefSchemeNumber,
        p_is_linked = request.transaction.isLinked,
        p_total_consider_linked = request.transaction.totalConsiderLinked,
        p_total_consider = request.transaction.totalConsider,
        p_consider_build = request.transaction.considerBuild,
        p_consider_cash = request.transaction.considerCash,
        p_consider_contingent = request.transaction.considerContingent,
        p_consider_debt = request.transaction.considerDebt,
        p_consider_employ = request.transaction.considerEmploy,
        p_consider_other = request.transaction.considerOther,
        p_consider_land = request.transaction.considerLand,
        p_consider_services = request.transaction.considerServices,
        p_consider_shares_qtd = request.transaction.considerSharesQtd,
        p_consider_shares_unqtd = request.transaction.considerSharesUnqtd,
        p_consider_vat = request.transaction.considerVat,
        p_includes_chattel = request.transaction.includesChattel,
        p_includes_goodwill = request.transaction.includesGoodwill,
        p_includes_other = request.transaction.includesOther,
        p_includes_stock = request.transaction.includesStock,
        p_used_as_factory = request.transaction.usedAsFactory,
        p_used_as_hotel = request.transaction.usedAsHotel,
        p_used_as_industrial = request.transaction.usedAsIndustrial,
        p_used_as_office = request.transaction.usedAsOffice,
        p_used_as_other = request.transaction.usedAsOther,
        p_used_as_shop = request.transaction.usedAsShop,
        p_used_as_warehouse = request.transaction.usedAsWarehouse,
        p_contract_date = request.transaction.contractDate,
        p_is_depend_future_event = request.transaction.isDependOnFutureEvent,
        p_transaction_des = request.transaction.transactionDescription,
        p_new_transaction_des = request.transaction.newTransactionDescription,
        p_effective_date = request.transaction.effectiveDate,
        p_is_land_exchanged = request.transaction.isLandExchanged,
        p_ex_land_house_number = request.transaction.exLandHouseNumber,
        p_ex_land_address_1 = request.transaction.exLandAddress1,
        p_ex_land_address_2 = request.transaction.exLandAddress2,
        p_ex_land_address_3 = request.transaction.exLandAddress3,
        p_ex_land_address_4 = request.transaction.exLandAddress4,
        p_ex_land_postcode = request.transaction.exLandPostcode,
        p_agreed_defer_pay = request.transaction.agreedDeferPay,
        p_post_tr_rul_applied = request.transaction.postTransactionRulingApplied,
        p_is_pur_to_pre_opt = request.transaction.isPursuantToPreviousOption,
        p_rest_affect_int = request.transaction.restAffectInt,
        p_rest_details = request.transaction.restDetails,
        p_post_tr_rul_foll = request.transaction.postTransactionRulingFollowed,
        p_is_part_sale_of_busi = request.transaction.isPartOfSaleOfBusiness,
        p_total_consider_busi = request.transaction.totalConsiderationOfBusiness
      )
    }
  }

  private def callUpdateTransaction(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_claiming_relief: Option[String],
    p_relief_amount: Option[String],
    p_relief_reason: Option[String],
    p_relief_scheme_number: Option[String],
    p_is_linked: Option[String],
    p_total_consider_linked: Option[String],
    p_total_consider: Option[String],
    p_consider_build: Option[String],
    p_consider_cash: Option[String],
    p_consider_contingent: Option[String],
    p_consider_debt: Option[String],
    p_consider_employ: Option[String],
    p_consider_other: Option[String],
    p_consider_land: Option[String],
    p_consider_services: Option[String],
    p_consider_shares_qtd: Option[String],
    p_consider_shares_unqtd: Option[String],
    p_consider_vat: Option[String],
    p_includes_chattel: Option[String],
    p_includes_goodwill: Option[String],
    p_includes_other: Option[String],
    p_includes_stock: Option[String],
    p_used_as_factory: Option[String],
    p_used_as_hotel: Option[String],
    p_used_as_industrial: Option[String],
    p_used_as_office: Option[String],
    p_used_as_other: Option[String],
    p_used_as_shop: Option[String],
    p_used_as_warehouse: Option[String],
    p_contract_date: Option[String],
    p_is_depend_future_event: Option[String],
    p_transaction_des: Option[String],
    p_new_transaction_des: Option[String],
    p_effective_date: Option[String],
    p_is_land_exchanged: Option[String],
    p_ex_land_house_number: Option[String],
    p_ex_land_address_1: Option[String],
    p_ex_land_address_2: Option[String],
    p_ex_land_address_3: Option[String],
    p_ex_land_address_4: Option[String],
    p_ex_land_postcode: Option[String],
    p_agreed_defer_pay: Option[String],
    p_post_tr_rul_applied: Option[String],
    p_is_pur_to_pre_opt: Option[String],
    p_rest_affect_int: Option[String],
    p_rest_details: Option[String],
    p_post_tr_rul_foll: Option[String],
    p_is_part_sale_of_busi: Option[String],
    p_total_consider_busi: Option[String]
  ): UpdateTransactionReturn = {

    val cs = conn.prepareCall(
      "{ call TRANSACTION_PROCS.UPDATE_TRANSACTION(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setOptionalString(3, p_claiming_relief)
      cs.setOptionalString(4, p_relief_amount)
      cs.setOptionalString(5, p_relief_reason)
      cs.setOptionalString(6, p_relief_scheme_number)
      cs.setOptionalString(7, p_is_linked)
      cs.setOptionalString(8, p_total_consider_linked)
      cs.setOptionalString(9, p_total_consider)
      cs.setOptionalString(10, p_consider_build)
      cs.setOptionalString(11, p_consider_cash)
      cs.setOptionalString(12, p_consider_contingent)
      cs.setOptionalString(13, p_consider_debt)
      cs.setOptionalString(14, p_consider_employ)
      cs.setOptionalString(15, p_consider_other)
      cs.setOptionalString(16, p_consider_land)
      cs.setOptionalString(17, p_consider_services)
      cs.setOptionalString(18, p_consider_shares_qtd)
      cs.setOptionalString(19, p_consider_shares_unqtd)
      cs.setOptionalString(20, p_consider_vat)
      cs.setOptionalString(21, p_includes_chattel)
      cs.setOptionalString(22, p_includes_goodwill)
      cs.setOptionalString(23, p_includes_other)
      cs.setOptionalString(24, p_includes_stock)
      cs.setOptionalString(25, p_used_as_factory)
      cs.setOptionalString(26, p_used_as_hotel)
      cs.setOptionalString(27, p_used_as_industrial)
      cs.setOptionalString(28, p_used_as_office)
      cs.setOptionalString(29, p_used_as_other)
      cs.setOptionalString(30, p_used_as_shop)
      cs.setOptionalString(31, p_used_as_warehouse)
      cs.setOptionalString(32, p_contract_date)
      cs.setOptionalString(33, p_is_depend_future_event)
      cs.setOptionalString(34, p_transaction_des)
      cs.setOptionalString(35, p_new_transaction_des)
      cs.setOptionalString(36, p_effective_date)
      cs.setOptionalString(37, p_is_land_exchanged)
      cs.setOptionalString(38, p_ex_land_house_number)
      cs.setOptionalString(39, p_ex_land_address_1)
      cs.setOptionalString(40, p_ex_land_address_2)
      cs.setOptionalString(41, p_ex_land_address_3)
      cs.setOptionalString(42, p_ex_land_address_4)
      cs.setOptionalString(43, p_ex_land_postcode)
      cs.setOptionalString(44, p_agreed_defer_pay)
      cs.setOptionalString(45, p_post_tr_rul_applied)
      cs.setOptionalString(46, p_is_pur_to_pre_opt)
      cs.setOptionalString(47, p_rest_affect_int)
      cs.setOptionalString(48, p_rest_details)
      cs.setOptionalString(49, p_post_tr_rul_foll)
      cs.setOptionalString(50, p_is_part_sale_of_busi)
      cs.setOptionalString(51, p_total_consider_busi)

      cs.execute()

      UpdateTransactionReturn(updated = true)
    } finally cs.close()
  }

  override def sdltCreateLease(request: CreateLeaseRequest): Future[CreateLeaseReturn] = Future {
    db.withTransaction { conn =>
      callCreateLease(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_is_annual_rent_over_1000 = request.lease.isAnnualRentOver1000,
        p_contract_end_date = request.lease.contractEndDate,
        p_contract_start_date = request.lease.contractStartDate,
        p_lease_type = request.lease.leaseType,
        p_net_present_value = request.lease.netPresentValue,
        p_total_premium_payable = request.lease.totalPremiumPayable,
        p_rent_free_period = request.lease.rentFreePeriod,
        p_starting_rent = request.lease.startingRent,
        p_starting_rent_end_date = request.lease.startingRentEndDate,
        p_later_rent_known = request.lease.laterRentKnown,
        p_vat_amount = request.lease.vatAmount
      )
    }
  }

  private def callCreateLease(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_is_annual_rent_over_1000: Option[String],
    p_contract_end_date: Option[String],
    p_contract_start_date: Option[String],
    p_lease_type: Option[String],
    p_net_present_value: Option[String],
    p_total_premium_payable: Option[String],
    p_rent_free_period: Option[String],
    p_starting_rent: Option[String],
    p_starting_rent_end_date: Option[String],
    p_later_rent_known: Option[String],
    p_vat_amount: Option[String]
  ): CreateLeaseReturn = {

    val cs = conn.prepareCall(
      "{ call LEASE_PROCS.CREATE_LEASE(" +
        "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
        "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
        "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
        "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
        "?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setOptionalString(3, p_is_annual_rent_over_1000)
      cs.setNull(4, Types.VARCHAR)
      cs.setNull(5, Types.VARCHAR)
      cs.setNull(6, Types.VARCHAR)
      cs.setOptionalString(7, p_contract_end_date)
      cs.setOptionalString(8, p_contract_start_date)
      cs.setNull(9, Types.VARCHAR)
      cs.setOptionalString(10, p_lease_type)
      cs.setNull(11, Types.VARCHAR)
      cs.setOptionalString(12, p_net_present_value)
      cs.setNull(13, Types.VARCHAR)
      cs.setOptionalString(14, p_total_premium_payable)
      cs.setNull(15, Types.VARCHAR)
      cs.setOptionalString(16, p_rent_free_period)
      cs.setNull(17, Types.VARCHAR)
      cs.setNull(18, Types.VARCHAR)
      cs.setNull(19, Types.VARCHAR)
      cs.setNull(20, Types.VARCHAR)
      cs.setOptionalString(21, p_starting_rent)
      cs.setOptionalString(22, p_starting_rent_end_date)
      cs.setOptionalString(23, p_later_rent_known)
      cs.setNull(24, Types.VARCHAR)
      cs.setNull(25, Types.VARCHAR)
      cs.setNull(26, Types.VARCHAR)
      cs.setNull(27, Types.VARCHAR)
      cs.setNull(28, Types.VARCHAR)
      cs.setNull(29, Types.VARCHAR)
      cs.setNull(30, Types.VARCHAR)
      cs.setNull(31, Types.VARCHAR)
      cs.setNull(32, Types.VARCHAR)
      cs.setNull(33, Types.VARCHAR)
      cs.setNull(34, Types.VARCHAR)
      cs.setNull(35, Types.VARCHAR)
      cs.setNull(36, Types.VARCHAR)
      cs.setNull(37, Types.VARCHAR)
      cs.setNull(38, Types.VARCHAR)
      cs.setNull(39, Types.VARCHAR)
      cs.setNull(40, Types.VARCHAR)
      cs.setNull(41, Types.VARCHAR)
      cs.setNull(42, Types.VARCHAR)
      cs.setNull(43, Types.VARCHAR)
      cs.setOptionalString(44, p_vat_amount)
      cs.registerOutParameter(45, Types.NUMERIC)

      cs.execute()

      CreateLeaseReturn(created = true)
    } finally cs.close()
  }

  override def sdltUpdateLease(request: UpdateLeaseRequest): Future[UpdateLeaseReturn] = Future {
    db.withTransaction { conn =>
      callUpdateLease(
        conn = conn,
        p_storn = request.stornId,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_is_annual_rent_over_1000 = request.lease.isAnnualRentOver1000,
        p_contract_end_date = request.lease.contractEndDate,
        p_contract_start_date = request.lease.contractStartDate,
        p_lease_type = request.lease.leaseType,
        p_net_present_value = request.lease.netPresentValue,
        p_total_premium_payable = request.lease.totalPremiumPayable,
        p_rent_free_period = request.lease.rentFreePeriod,
        p_starting_rent = request.lease.startingRent,
        p_starting_rent_end_date = request.lease.startingRentEndDate,
        p_later_rent_known = request.lease.laterRentKnown,
        p_vat_amount = request.lease.vatAmount
      )
    }
  }

  private def callUpdateLease(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_is_annual_rent_over_1000: Option[String],
    p_contract_end_date: Option[String],
    p_contract_start_date: Option[String],
    p_lease_type: Option[String],
    p_net_present_value: Option[String],
    p_total_premium_payable: Option[String],
    p_rent_free_period: Option[String],
    p_starting_rent: Option[String],
    p_starting_rent_end_date: Option[String],
    p_later_rent_known: Option[String],
    p_vat_amount: Option[String]
  ): UpdateLeaseReturn = {

    val cs = conn.prepareCall(
      "{ call LEASE_PROCS.UPDATE_LEASE(" +
        "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
        "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
        "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
        "?, ?, ?, ?, ?, ?, ?, ?, ?, ?, " +
        "?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setOptionalString(3, p_is_annual_rent_over_1000)
      cs.setNull(4, Types.VARCHAR)
      cs.setNull(5, Types.VARCHAR)
      cs.setNull(6, Types.VARCHAR)
      cs.setOptionalString(7, p_contract_end_date)
      cs.setOptionalString(8, p_contract_start_date)
      cs.setNull(9, Types.VARCHAR)
      cs.setOptionalString(10, p_lease_type)
      cs.setNull(11, Types.VARCHAR)
      cs.setOptionalString(12, p_net_present_value)
      cs.setNull(13, Types.VARCHAR)
      cs.setOptionalString(14, p_total_premium_payable)
      cs.setNull(15, Types.VARCHAR)
      cs.setOptionalString(16, p_rent_free_period)
      cs.setNull(17, Types.VARCHAR)
      cs.setNull(18, Types.VARCHAR)
      cs.setNull(19, Types.VARCHAR)
      cs.setNull(20, Types.VARCHAR)
      cs.setOptionalString(21, p_starting_rent)
      cs.setOptionalString(22, p_starting_rent_end_date)
      cs.setOptionalString(23, p_later_rent_known)
      cs.setNull(24, Types.VARCHAR)
      cs.setNull(25, Types.VARCHAR)
      cs.setNull(26, Types.VARCHAR)
      cs.setNull(27, Types.VARCHAR)
      cs.setNull(28, Types.VARCHAR)
      cs.setNull(29, Types.VARCHAR)
      cs.setNull(30, Types.VARCHAR)
      cs.setNull(31, Types.VARCHAR)
      cs.setNull(32, Types.VARCHAR)
      cs.setNull(33, Types.VARCHAR)
      cs.setNull(34, Types.VARCHAR)
      cs.setNull(35, Types.VARCHAR)
      cs.setNull(36, Types.VARCHAR)
      cs.setNull(37, Types.VARCHAR)
      cs.setNull(38, Types.VARCHAR)
      cs.setNull(39, Types.VARCHAR)
      cs.setNull(40, Types.VARCHAR)
      cs.setNull(41, Types.VARCHAR)
      cs.setNull(42, Types.VARCHAR)
      cs.setNull(43, Types.VARCHAR)
      cs.setOptionalString(44, p_vat_amount)

      cs.execute()

      UpdateLeaseReturn(updated = true)
    } finally cs.close()
  }

  override def sdltDeleteLease(request: DeleteLeaseRequest): Future[DeleteLeaseReturn] = Future {
    db.withTransaction { conn =>
      callDeleteLease(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong
      )
    }
  }

  private def callDeleteLease(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long
  ): DeleteLeaseReturn = {

    val cs = conn.prepareCall("{ call LEASE_PROCS.DELETE_LEASE(?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)

      cs.execute()

      DeleteLeaseReturn(deleted = true)
    } finally cs.close()
  }

  override def sdltUpdateTaxCalculation(request: UpdateTaxCalculationRequest): Future[UpdateTaxCalculationReturn] =
    Future {
      db.withTransaction { conn =>
        callUpdateTaxCalculation(
          conn = conn,
          p_storn = request.stornId,
          p_return_resource_ref = request.returnResourceRef.toLong,
          p_amount_paid = request.amountPaid,
          p_includes_penalty = request.includesPenalty,
          p_tax_due = request.taxDue,
          p_calc_penalty_due = request.calcPenaltyDue,
          p_calc_tax_due = request.calcTaxDue,
          p_calc_tax_rate1 = request.calcTaxRate1,
          p_calc_tax_rate2 = request.calcTaxRate2,
          p_calc_total_tax_penalty_due = request.calcTotalTaxPenaltyDue,
          p_calc_total_npv_tax = request.calcTotalNpvTax,
          p_calc_total_premium_tax = request.calcTotalPremiumTax,
          p_tax_due_premium = request.taxDuePremium,
          p_tax_due_npv = request.taxDueNpv,
          p_honesty_declaration = request.honestyDeclaration
        )
      }
    }

  private def callUpdateTaxCalculation(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_amount_paid: Option[String],
    p_includes_penalty: Option[String],
    p_tax_due: Option[String],
    p_calc_penalty_due: Option[String],
    p_calc_tax_due: Option[String],
    p_calc_tax_rate1: Option[String],
    p_calc_tax_rate2: Option[String],
    p_calc_total_tax_penalty_due: Option[String],
    p_calc_total_npv_tax: Option[String],
    p_calc_total_premium_tax: Option[String],
    p_tax_due_premium: Option[String],
    p_tax_due_npv: Option[String],
    p_honesty_declaration: Option[String]
  ): UpdateTaxCalculationReturn = {

    val cs = conn.prepareCall(
      "{ call TAX_CALCULATION_PROC.Update_Tax(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setOptionalString(3, p_amount_paid)
      cs.setOptionalString(4, p_includes_penalty)
      cs.setOptionalString(5, p_tax_due)
      cs.setOptionalString(6, p_calc_penalty_due)
      cs.setOptionalString(7, p_calc_tax_due)
      cs.setOptionalString(8, p_calc_tax_rate1)
      cs.setOptionalString(9, p_calc_tax_rate2)
      cs.setOptionalString(10, p_calc_total_tax_penalty_due)
      cs.setOptionalString(11, p_calc_total_npv_tax)
      cs.setOptionalString(12, p_calc_total_premium_tax)
      cs.setOptionalString(13, p_tax_due_premium)
      cs.setOptionalString(14, p_tax_due_npv)
      cs.setOptionalString(15, p_honesty_declaration)

      cs.execute()

      UpdateTaxCalculationReturn(updated = true)
    } finally cs.close()
  }

  override def sdltLockReturn(request: LockReturnRequest): Future[LockReturnResponse] = Future {
    db.withTransaction { conn =>
      callLockReturn(
        conn = conn,
        p_storn = request.storn,
        p_return = request.returnResourceRef.toLong,
        p_version = request.version.toLong
      )
    }
  }

  private def callLockReturn(
    conn: Connection,
    p_storn: String,
    p_return: Long,
    p_version: Long
  ): LockReturnResponse = {

    val cs = conn.prepareCall("{ call return_procs.lock_return(?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return)
      cs.setLong(3, p_version)
      cs.registerOutParameter(3, Types.NUMERIC)

      cs.execute()

      LockReturnResponse(success = true)
    } finally cs.close()
  }

  override def sdltCreateSubmission(request: CreateSubmissionRequest): Future[CreateSubmissionReturn] = Future {
    db.withTransaction { conn =>
      callCreateSubmission(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_email = request.email
      )
    }
  }

  private def callCreateSubmission(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_email: Option[String]
  ): CreateSubmissionReturn = {

    val cs = conn.prepareCall("{ call SUBMISSION_PROCS.CREATE_SUBMISSION(?, ?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setOptionalString(3, p_email)

      cs.registerOutParameter(4, Types.NUMERIC)

      cs.execute()

      val submissionId = cs.getLong(4)

      CreateSubmissionReturn(success = true)
    } finally cs.close()
  }

  override def sdltUpdateSubmission(request: UpdateSubmissionRequest): Future[UpdateSubmissionReturn] = Future {
    db.withTransaction { conn =>
      callUpdateSubmission(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_irmark_received = request.submission.IRMarkRecieved,
        p_utrn = request.submission.utrn,
        p_email = request.submission.email,
        p_submission_request_date = request.submission.submissionRequestDate,
        p_accepted_date = request.submission.acceptedDate,
        p_submittable_status = request.submission.submittableStatus,
        p_govtalk_error_code = request.submission.govTalkErrorCode,
        p_govtalk_error_type = request.submission.govTalkErrorType,
        p_govtalk_error_message = request.submission.govTalkErrorMessage,
        p_irmark_sent = request.submission.IRMarkSent
      )
    }
  }

  private def setOptionalDate(cs: CallableStatement, index: Int, value: Option[String]): Unit =
    value.filter(_.nonEmpty) match {
      case Some(v) => cs.setDate(index, Date.valueOf(v))
      case None    => cs.setNull(index, Types.DATE)
    }

  private def callUpdateSubmission(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_irmark_received: Option[String],
    p_utrn: Option[String],
    p_email: Option[String],
    p_submission_request_date: Option[String],
    p_accepted_date: Option[String],
    p_submittable_status: Option[String],
    p_govtalk_error_code: Option[String],
    p_govtalk_error_type: Option[String],
    p_govtalk_error_message: Option[String],
    p_irmark_sent: Option[String]
  ): UpdateSubmissionReturn = {

    val cs = conn.prepareCall(
      "{ call SUBMISSION_PROCS.UPDATE_SUBMISSION(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }"
    )
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setNull(3, Types.VARCHAR)
      cs.setOptionalString(4, p_irmark_received)
      cs.setOptionalString(5, p_utrn)
      cs.setOptionalString(6, p_email)
      setOptionalDate(cs, 7, p_submission_request_date)
      setOptionalDate(cs, 8, p_accepted_date)
      cs.setOptionalString(9, p_submittable_status)
      cs.setOptionalString(10, p_govtalk_error_code)
      cs.setOptionalString(11, p_govtalk_error_type)
      cs.setOptionalString(12, p_govtalk_error_message)
      cs.setOptionalString(13, p_irmark_sent)

      cs.execute()

      UpdateSubmissionReturn(success = true)
    } finally cs.close()
  }

  override def sdltCreateSubmissionErrorDetail(
    request: CreateSubmissionErrorDetailRequest
  ): Future[CreateSubmissionErrorDetailReturn] = Future {
    db.withTransaction { conn =>
      callCreateSubmissionErrorDetail(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong,
        p_position = request.submissionErrorDetails.position.toLong,
        p_error_message = request.submissionErrorDetails.errorMessage
      )
    }
  }

  private def callCreateSubmissionErrorDetail(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long,
    p_position: Long,
    p_error_message: String
  ): CreateSubmissionErrorDetailReturn = {

    val cs = conn.prepareCall("{ call SUBMISSION_PROCS.CREATE_SUBMISSION_ERROR_DETAIL(?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)
      cs.setLong(3, p_position)
      cs.setString(4, p_error_message)

      cs.registerOutParameter(5, Types.NUMERIC)

      cs.execute()

      val errorDetailId = cs.getLong(5)
      CreateSubmissionErrorDetailReturn(success = true)
    } finally cs.close()
  }

  override def sdltDeleteSubmissionErrorDetail(
    request: DeleteSubmissionErrorDetailRequest
  ): Future[DeleteSubmissionErrorDetailReturn] = Future {
    db.withTransaction { conn =>
      callDeleteSubmissionErrorDetail(
        conn = conn,
        p_storn = request.storn,
        p_return_resource_ref = request.returnResourceRef.toLong
      )
    }
  }

  private def callDeleteSubmissionErrorDetail(
    conn: Connection,
    p_storn: String,
    p_return_resource_ref: Long
  ): DeleteSubmissionErrorDetailReturn = {

    val cs = conn.prepareCall("{ call SUBMISSION_PROCS.DELETE_SUBMISSION_ERROR_DETAIL(?, ?) }")
    try {
      cs.setString(1, p_storn)
      cs.setLong(2, p_return_resource_ref)

      cs.execute()

      DeleteSubmissionErrorDetailReturn(success = true)
    } finally cs.close()
  }

  private def setRequiredTimestamp(cs: CallableStatement, index: Int, value: String): Unit =
    cs.setTimestamp(index, Timestamp.valueOf(value))

  private def setOptionalTimestamp(cs: CallableStatement, index: Int, value: Option[String]): Unit =
    value.filter(_.nonEmpty) match {
      case Some(v) => cs.setTimestamp(index, Timestamp.valueOf(v))
      case None    => cs.setNull(index, Types.TIMESTAMP)
    }

  override def sdltDeleteGovTalkStatus(request: DeleteGovTalkStatusRequest): Future[GovTalkStatusReturn] = Future {
    db.withTransaction { conn =>
      callDeleteGovTalkStatus(conn = conn, p_result_id = request.resultId)
    }
  }

  private def callDeleteGovTalkStatus(conn: Connection, p_result_id: String): GovTalkStatusReturn = {
    val cs = conn.prepareCall("{ call SUBMISSION_PROCS.DELETE_GOVTALK_STATUS(?) }")
    try {
      cs.setString(1, p_result_id)
      cs.execute()
      GovTalkStatusReturn(success = true)
    } finally cs.close()
  }

  override def sdltInsertInitialGovTalkStatus(request: InsertInitialGovTalkStatusRequest): Future[GovTalkStatusReturn] =
    Future {
      db.withTransaction { conn =>
        callInsertInitialGovTalkStatus(
          conn = conn,
          p_user_identifier = request.userIdentifier,
          p_formResultId = request.formResultId,
          p_correlation_id = request.correlationId,
          p_form_lock = request.govTalkStatus.formLock,
          p_create_timestamp = request.govTalkStatus.createTimestamp,
          p_endstate_timestamp = request.govTalkStatus.endStateTimestamp,
          p_last_mesg_timestamp = request.govTalkStatus.lastMessageTimestamp,
          p_num_polls = request.govTalkStatus.numberOfPolls.toLong,
          p_poll_interval = request.govTalkStatus.pollInterval.toLong,
          p_protocol_status = request.govTalkStatus.protocolStatus,
          p_gatewayurl = request.govTalkStatus.gatewayUrl
        )
      }
    }

  private def callInsertInitialGovTalkStatus(
    conn: Connection,
    p_user_identifier: String,
    p_formResultId: String,
    p_correlation_id: String,
    p_form_lock: String,
    p_create_timestamp: String,
    p_endstate_timestamp: Option[String],
    p_last_mesg_timestamp: String,
    p_num_polls: Long,
    p_poll_interval: Long,
    p_protocol_status: String,
    p_gatewayurl: String
  ): GovTalkStatusReturn = {
    val cs = conn.prepareCall("{ call SUBMISSION_ADMIN.InsertInitialGovTalkStatus(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_user_identifier)
      cs.setString(2, p_formResultId)
      cs.setString(3, p_correlation_id)
      cs.setString(4, p_form_lock)
      setRequiredTimestamp(cs, 5, p_create_timestamp)
      setOptionalTimestamp(cs, 6, p_endstate_timestamp)
      setRequiredTimestamp(cs, 7, p_last_mesg_timestamp)
      cs.setLong(8, p_num_polls)
      cs.setLong(9, p_poll_interval)
      cs.setString(10, p_protocol_status)
      cs.setString(11, p_gatewayurl)
      cs.execute()
      GovTalkStatusReturn(success = true)
    } finally cs.close()
  }

  override def sdltResetGovTalkStatus(request: ResetGovTalkStatusRequest): Future[GovTalkStatusReturn] = Future {
    db.withTransaction { conn =>
      callResetGovTalkStatus(
        conn = conn,
        p_user_identifier = request.userIdentifier,
        p_formResultId = request.formResultId,
        p_correlation_id = request.correlationId,
        p_form_lock = request.govTalkStatus.formLock,
        p_create_timestamp = request.govTalkStatus.createTimestamp,
        p_endstate_timestamp = request.govTalkStatus.endStateTimestamp,
        p_last_mesg_timestamp = request.govTalkStatus.lastMessageTimestamp,
        p_num_polls = request.govTalkStatus.numberOfPolls.toLong,
        p_poll_interval = request.govTalkStatus.pollInterval.toLong,
        p_protocol_status_old = request.govTalkStatus.protocolStatusOld,
        p_protocol_status_new = request.govTalkStatus.protocolStatusNew,
        p_gatewayurl = request.govTalkStatus.gatewayUrl
      )
    }
  }

  private def callResetGovTalkStatus(
    conn: Connection,
    p_user_identifier: String,
    p_formResultId: String,
    p_correlation_id: String,
    p_form_lock: String,
    p_create_timestamp: String,
    p_endstate_timestamp: Option[String],
    p_last_mesg_timestamp: String,
    p_num_polls: Long,
    p_poll_interval: Long,
    p_protocol_status_old: String,
    p_protocol_status_new: String,
    p_gatewayurl: String
  ): GovTalkStatusReturn = {
    val cs = conn.prepareCall("{ call SUBMISSION_ADMIN.ResetGovTalkStatusRecord(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_user_identifier)
      cs.setString(2, p_formResultId)
      cs.setString(3, p_correlation_id)
      cs.setString(4, p_form_lock)
      setRequiredTimestamp(cs, 5, p_create_timestamp)
      setOptionalTimestamp(cs, 6, p_endstate_timestamp)
      setRequiredTimestamp(cs, 7, p_last_mesg_timestamp)
      cs.setLong(8, p_num_polls)
      cs.setLong(9, p_poll_interval)
      cs.setString(10, p_protocol_status_old)
      cs.setString(11, p_protocol_status_new)
      cs.setString(12, p_gatewayurl)
      cs.execute()
      GovTalkStatusReturn(success = true)
    } finally cs.close()
  }

  override def sdltUpdateGovTalkStatus(request: UpdateGovTalkStatusRequest): Future[GovTalkStatusReturn] = Future {
    db.withTransaction { conn =>
      callUpdateGovTalkStatus(
        conn = conn,
        p_user_identifier = request.userIdentifier,
        p_formResultId = request.formResultId,
        p_endstate_timestamp = request.endStateTimestamp,
        p_protocol_status = request.protocolStatus
      )
    }
  }

  private def callUpdateGovTalkStatus(
    conn: Connection,
    p_user_identifier: String,
    p_formResultId: String,
    p_endstate_timestamp: String,
    p_protocol_status: String
  ): GovTalkStatusReturn = {
    val cs = conn.prepareCall("{ call SUBMISSION_ADMIN.UpdateGovTalkStatus(?, ?, ?, ?) }")
    try {
      cs.setString(1, p_user_identifier)
      cs.setString(2, p_formResultId)
      setRequiredTimestamp(cs, 3, p_endstate_timestamp)
      cs.setString(4, p_protocol_status)
      cs.execute()
      GovTalkStatusReturn(success = true)
    } finally cs.close()
  }

  override def sdltUpdateGovTalkStatusLock(request: UpdateGovTalkStatusLockRequest): Future[GovTalkStatusReturn] =
    Future {
      db.withTransaction { conn =>
        callUpdateGovTalkStatusLock(
          conn = conn,
          p_user_identifier = request.userIdentifier,
          p_formResultId = request.formResultId,
          p_form_lock_old = request.govTalkStatus.formLockOld,
          p_poll_interval = request.govTalkStatus.pollInterval.toLong,
          p_gatewayurl = request.govTalkStatus.gatewayUrl,
          p_form_lock_new = request.govTalkStatus.formLockNew
        )
      }
    }

  private def callUpdateGovTalkStatusLock(
    conn: Connection,
    p_user_identifier: String,
    p_formResultId: String,
    p_form_lock_old: String,
    p_poll_interval: Long,
    p_gatewayurl: String,
    p_form_lock_new: String
  ): GovTalkStatusReturn = {
    val cs = conn.prepareCall("{ call SUBMISSION_ADMIN.UpdateGovTalkStatusLock(?, ?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_user_identifier)
      cs.setString(2, p_formResultId)
      cs.setString(3, p_form_lock_old)
      cs.setLong(4, p_poll_interval)
      cs.setString(5, p_gatewayurl)
      cs.setString(6, p_form_lock_new)
      cs.execute()
      GovTalkStatusReturn(success = true)
    } finally cs.close()
  }

  override def sdltUpdateGovTalkStatistics(request: UpdateGovTalkStatisticsRequest): Future[GovTalkStatusReturn] =
    Future {
      db.withTransaction { conn =>
        callUpdateGovTalkStatistics(
          conn = conn,
          p_user_identifier = request.userIdentifier,
          p_formResultId = request.formResultId,
          p_last_mesg_timestamp = request.govTalkStatus.lastMessageTimestamp,
          p_num_polls = request.govTalkStatus.numberOfPolls.toLong,
          p_poll_interval = request.govTalkStatus.pollInterval.toLong,
          p_gatewayurl = request.govTalkStatus.gatewayUrl
        )
      }
    }

  override def sdltUpdateGovTalkStatusCorrelationId(
    request: UpdateGovTalkStatusCorrelationIdRequest
  ): Future[GovTalkStatusReturn] = Future {
    db.withTransaction { conn =>
      callUpdateGovTalkStatusCorrelationId(
        conn = conn,
        p_user_identifier = request.userIdentifier,
        p_formResultId = request.formResultId,
        p_correlation_id = request.correlationId,
        p_endstate_timestamp = request.endStateTimestamp,
        p_protocol_status = request.protocolStatus
      )
    }
  }

  private def callUpdateGovTalkStatusCorrelationId(
    conn: Connection,
    p_user_identifier: String,
    p_formResultId: String,
    p_correlation_id: String,
    p_endstate_timestamp: String,
    p_protocol_status: String
  ): GovTalkStatusReturn = {
    val cs = conn.prepareCall("{ call SUBMISSION_ADMIN.UpdateGovTalkStatusCorr(?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_user_identifier)
      cs.setString(2, p_formResultId)
      cs.setString(3, p_correlation_id)
      setRequiredTimestamp(cs, 4, p_endstate_timestamp)
      cs.setString(5, p_protocol_status)
      cs.execute()
      GovTalkStatusReturn(success = true)
    } finally cs.close()
  }

  private def callUpdateGovTalkStatistics(
    conn: Connection,
    p_user_identifier: String,
    p_formResultId: String,
    p_last_mesg_timestamp: String,
    p_num_polls: Long,
    p_poll_interval: Long,
    p_gatewayurl: String
  ): GovTalkStatusReturn = {
    val cs = conn.prepareCall("{ call SUBMISSION_ADMIN.UpdateGovTalkStatistics(?, ?, ?, ?, ?, ?) }")
    try {
      cs.setString(1, p_user_identifier)
      cs.setString(2, p_formResultId)
      setRequiredTimestamp(cs, 3, p_last_mesg_timestamp)
      cs.setLong(4, p_num_polls)
      cs.setLong(5, p_poll_interval)
      cs.setString(6, p_gatewayurl)
      cs.execute()
      GovTalkStatusReturn(success = true)
    } finally cs.close()
  }

  override def sdltSelectGovTalkStatus(request: SelectGovTalkStatusRequest): Future[SelectGovTalkStatusResponse] = {
    logger.info(
      s"[SDLT] sdltSelectGovTalkStatus(userIdentifier=${request.userIdentifier}, formResultId=${request.formResultId})"
    )
    Future {
      db.withConnection { conn =>
        val cs = conn.prepareCall("{ call SUBMISSION_ADMIN.SelectGovTalkStatus(?, ?, ?) }")
        try {
          cs.setString(1, request.userIdentifier)
          cs.setString(2, request.formResultId)
          cs.registerOutParameter(3, OracleTypes.CURSOR)
          cs.execute()

          processResultSet(cs, 3, processGovTalkStatus)
            .getOrElse(
              SelectGovTalkStatusResponse(None, None, None, None, None, None, None, None, None, None, None)
            )
        } finally cs.close()
      }
    }
  }

  private def processGovTalkStatus(rs: ResultSet): SelectGovTalkStatusResponse =
    SelectGovTalkStatusResponse(
      userIdentifier = Option(rs.getString("USER_IDENTIFIER")),
      formResultId = Option(rs.getString("FORM_RESULT_ID")),
      correlationId = Option(rs.getString("CORRELATION_ID")),
      formLock = Option(rs.getString("FORM_LOCK")),
      createTimestamp = Option(rs.getString("CREATE_TIMESTAMP")),
      endStateTimestamp = Option(rs.getString("ENDSTATE_TIMESTAMP")),
      lastMessageTimestamp = Option(rs.getString("LAST_MESG_TIMESTAMP")),
      numberOfPolls = Option(rs.getString("NUM_POLLS")),
      pollInterval = Option(rs.getString("POLL_INTERVAL")),
      protocolStatus = Option(rs.getString("PROTOCOL_STATUS")),
      gatewayUrl = Option(rs.getString("GATEWAYURL"))
    )

  override def sdltSelectGovTalkFormResultId(
    request: SelectGovTalkFormResultIdRequest
  ): Future[SelectGovTalkFormResultIdResponse] = {
    logger.info(s"[SDLT] sdltSelectGovTalkFormResultId(userIdentifier=${request.userIdentifier})")
    Future {
      db.withConnection { conn =>
        val cs = conn.prepareCall("{ call SUBMISSION_ADMIN.SelectGovTalkFormResultId(?, ?) }")
        try {
          cs.setString(1, request.userIdentifier)
          cs.registerOutParameter(2, Types.VARCHAR)
          cs.execute()
          SelectGovTalkFormResultIdResponse(formResultId = Option(cs.getString(2)))
        } finally cs.close()
      }
    }
  }

}

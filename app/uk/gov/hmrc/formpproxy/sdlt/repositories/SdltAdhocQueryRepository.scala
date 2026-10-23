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

package uk.gov.hmrc.formpproxy.sdlt.repositories

import play.api.db.{Database, NamedDatabase}
import play.api.libs.json.{JsNull, JsObject, JsString, JsValue}

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Using

@Singleton
class SdltAdhocQueryRepository @Inject() (@NamedDatabase("sdltReadOnly") db: Database)(implicit ec: ExecutionContext) {

  def runQuery(rawSql: String): Future[Seq[JsObject]] = Future {
    val sql = rawSql.trim.stripSuffix(";")

    db.withConnection { conn =>
      Using.resource(conn.prepareStatement(sql)) { ps =>
        Using.resource(ps.executeQuery()) { rs =>
          val meta    = rs.getMetaData
          val columns = (1 to meta.getColumnCount).map(meta.getColumnLabel)
          val rows    = scala.collection.mutable.ListBuffer[JsObject]()

          while (rs.next())
            rows += JsObject(columns.zipWithIndex.map { case (name, i) =>
              val value: JsValue = Option(rs.getString(i + 1)).map(JsString(_)).getOrElse(JsNull)
              name -> value
            })

          rows.toSeq
        }
      }
    }
  }
}

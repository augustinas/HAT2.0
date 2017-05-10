/*
 * Copyright (C) 2017 HAT Data Exchange Ltd
 * SPDX-License-Identifier: AGPL-3.0
 *
 * This file is part of the Hub of All Things project (HAT).
 *
 * HAT is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, version 3 of
 * the License.
 *
 * HAT is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General
 * Public License along with this program. If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * Written by Andrius Aucinas <andrius.aucinas@hatdex.org>
 * 4 / 2017
 */

package org.hatdex.hat.api.service.monitoring

import java.util.UUID

import akka.stream.Materializer
import org.hatdex.hat.api.models.EndpointData
import org.hatdex.hat.authentication.models.HatUser
import org.hatdex.hat.dal.ModelTranslation
import org.hatdex.hat.resourceManagement.FakeHatConfiguration
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.specification.Scope
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.libs.json.{ JsValue, Json }
import play.api.test.PlaySpecification
import play.api.{ Application, Logger }

class JsonStatsServiceSpec(implicit ee: ExecutionEnv) extends PlaySpecification with Mockito with JsonStatsServiceContext {

  val logger = Logger(this.getClass)

  sequential

  "The `countJsonPaths` method" should {
    "Correctly count numbers of values for simple objects" in {

      val result = JsonStatsService.countJsonPaths(simpleJson)
      result("field") must equalTo(1)
      result("date") must equalTo(1)
      result("date_iso") must equalTo(1)
      result("anotherField") must equalTo(1)
      result("object.objectField") must equalTo(1)
      result("object.objectFieldArray[]") must equalTo(3)
      result("object.objectFieldObjectArray[].subObjectName") must equalTo(2)
      result("object.objectFieldObjectArray[].subObjectName2") must equalTo(2)
    }
  }

  "The `countEndpointData` method" should {
    "Correctly count numbers of values for simple endpoint data objects" in {

      val counts = JsonStatsService.countEndpointData(EndpointData("test", None, simpleJson, None))
      val result = counts("test")
      result("field") must equalTo(1)
      result("date") must equalTo(1)
      result("date_iso") must equalTo(1)
      result("anotherField") must equalTo(1)
      result("object.objectField") must equalTo(1)
      result("object.objectFieldArray[]") must equalTo(3)
      result("object.objectFieldObjectArray[].subObjectName") must equalTo(2)
      result("object.objectFieldObjectArray[].subObjectName2") must equalTo(2)
    }

    "Correctly count numbers of values for linked endpoint data objects" in {

      val counts = JsonStatsService.countEndpointData(
        EndpointData("test", None, simpleJson, Some(Seq(EndpointData("test", None, simpleJson, None)))))
      val result = counts("test")
      result("field") must equalTo(2)
      result("date") must equalTo(2)
      result("date_iso") must equalTo(2)
      result("anotherField") must equalTo(2)
      result("object.objectField") must equalTo(2)
      result("object.objectFieldArray[]") must equalTo(6)
      result("object.objectFieldObjectArray[].subObjectName") must equalTo(4)
      result("object.objectFieldObjectArray[].subObjectName2") must equalTo(4)
    }
  }

  "The `endpointDataCounts` method" should {
    "correctly combine numbers of values from subsequent EndpointData records" in {

      val counts = JsonStatsService.endpointDataCounts(
        Seq(
          EndpointData("test", None, simpleJson, Some(Seq(EndpointData("test", None, simpleJson, None)))),
          EndpointData("test", None, simpleJson, None)),
        ModelTranslation.fromInternalModel(owner), "testEntry")

      counts.headOption must beSome
      counts.head.logEntry must equalTo("testEntry")
      val result = counts.head.counts

      result("field") must equalTo(3)
      result("date") must equalTo(3)
      result("date_iso") must equalTo(3)
      result("anotherField") must equalTo(3)
      result("object.objectField") must equalTo(3)
      result("object.objectFieldArray[]") must equalTo(9)
      result("object.objectFieldObjectArray[].subObjectName") must equalTo(6)
      result("object.objectFieldObjectArray[].subObjectName2") must equalTo(6)
    }
  }

}

trait JsonStatsServiceContext extends Scope {
  // Setup default users for testing
  val owner = HatUser(UUID.randomUUID(), "hatuser", Some("pa55w0rd"), "hatuser", "owner", enabled = true)

  lazy val application: Application = new GuiceApplicationBuilder()
    .configure(FakeHatConfiguration.config)
    .build()

  implicit lazy val materializer: Materializer = application.materializer

  val simpleJson: JsValue = Json.parse(
    """
      | {
      |   "field": "value",
      |   "date": 1492699047,
      |   "date_iso": "2017-04-20T14:37:27+00:00",
      |   "anotherField": "anotherFieldValue",
      |   "object": {
      |     "objectField": "objectFieldValue",
      |     "objectFieldArray": ["objectFieldArray1", "objectFieldArray2", "objectFieldArray3"],
      |     "objectFieldObjectArray": [
      |       {"subObjectName": "subObject1", "subObjectName2": "subObject1-2"},
      |       {"subObjectName": "subObject2", "subObjectName2": "subObject2-2"}
      |     ]
      |   }
      | }
    """.stripMargin)
}
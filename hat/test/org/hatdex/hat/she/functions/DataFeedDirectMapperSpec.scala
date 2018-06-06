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
 * 11 / 2017
 */

package org.hatdex.hat.she.functions

import org.hatdex.hat.api.models.EndpointData
import org.hatdex.hat.api.models.FilterOperator.Between
import org.hatdex.hat.api.service.richData.RichDataService
import org.hatdex.hat.she.models.Request
import org.hatdex.hat.she.service._
import org.joda.time.{ DateTime, DateTimeUtils }
import org.specs2.concurrent.ExecutionEnv
import org.specs2.mock.Mockito
import org.specs2.specification.BeforeAfterAll
import play.api.Logger
import play.api.test.PlaySpecification

import scala.concurrent.Await
import scala.concurrent.duration._

class DataFeedDirectMapperSpec(implicit ee: ExecutionEnv) extends DataFeedDirectMapper with PlaySpecification with Mockito with DataFeedDirectMapperContext with BeforeAfterAll {
  val logger = Logger(this.getClass)

  def beforeAll: Unit = {
    DateTimeUtils.setCurrentMillisFixed(1514764800000L)
    Await.result(databaseReady, 60.seconds)
  }

  def afterAll: Unit = {
    DateTimeUtils.setCurrentMillisSystem()
  }

  override def before(): Unit = {
    import org.hatdex.hat.dal.Tables._
    import org.hatdex.libs.dal.HATPostgresProfile.api._

    val endpointRecrodsQuery = DataJson.filter(d => d.source.like("test%") ||
      d.source.like("rumpel%") ||
      d.source.like("twitter%") ||
      d.source.like("facebook%") ||
      d.source.like("fitbit%") ||
      d.source.like("calendar%")).map(_.recordId)

    val action = DBIO.seq(
      DataDebitBundle.filter(_.bundleId.like("test%")).delete,
      DataDebitContract.filter(_.dataDebitKey.like("test%")).delete,
      DataCombinators.filter(_.combinatorId.like("test%")).delete,
      DataBundles.filter(_.bundleId.like("test%")).delete,
      DataJsonGroupRecords.filter(_.recordId in endpointRecrodsQuery).delete,
      DataJsonGroups.filterNot(g => g.groupId in DataJsonGroupRecords.map(_.groupId)).delete,
      DataJson.filter(r => r.recordId in endpointRecrodsQuery).delete)

    Await.result(hatDatabase.run(action), 60.seconds)
  }

  // TODO: updated tweet with retweet structure
  "The `mapTweet` method" should {
    "translate twitter retweets" in {
      val mapper = new TwitterFeedMapper()
      val transformed = mapper.mapDataRecord(exampleTweetRetweet.recordId.get, exampleTweetRetweet.data).get
      transformed.source must be equalTo "twitter"
      transformed.types must contain("post")
      transformed.title.get.text must contain("You retweeted")
      transformed.content.get.text.get must contain("RT @jupenur: Oh shit Adobe https://t.co/7rDL3LWVVz")

      transformed.location.get.geo.get.longitude must be equalTo -75.14310264
      transformed.location.get.address.get.country.get must be equalTo "United States"
      transformed.location.get.address.get.city.get must be equalTo "Washington"
    }

    // TODO: update tweet with reply structure
    "translate twitter replies" in {
      val mapper = new TwitterFeedMapper()
      val transformed = mapper.mapDataRecord(exampleTweetMentions.recordId.get, exampleTweetMentions.data).get
      transformed.source must be equalTo "twitter"
      transformed.title.get.text must contain("You replied to @drgeep")
    }

    "translate minimal tweet structure correctly" in {
      val mapper = new TwitterFeedMapper()
      val transformed = mapper.mapDataRecord(exampleTweetMinimalFields.recordId.get, exampleTweetMinimalFields.data).get
      transformed.source must be equalTo "twitter"
      transformed.content.get.text.get must contain("Tweet from Portugal.")
    }
  }

  "The `InstagramMediaMapper` class" should {
    "translate single image posts" in {
      val mapper = new InstagramMediaMapper()
      val transformed = mapper.mapDataRecord(exampleInstagramImage.recordId.get, exampleInstagramImage.data).get
      transformed.source must be equalTo "instagram"
      transformed.title.get.text must contain("You posted")
      transformed.title.get.action.get must be equalTo "image"
      transformed.content.get.text.get must contain("Saturday breakfast magic")
      transformed.content.get.media.get.length must be equalTo 1
      transformed.content.get.media.get.head.url.get must be startingWith "https://scontent.cdninstagram.com/vp"
    }

    "translate multiple image carousel posts" in {
      val mapper = new InstagramMediaMapper()
      val transformed = mapper.mapDataRecord(exampleMultipleInstagramImages.recordId.get, exampleMultipleInstagramImages.data).get
      transformed.source must be equalTo "instagram"
      transformed.title.get.text must contain("You posted")
      transformed.title.get.action.get must be equalTo "carousel"
      transformed.content.get.text.get must contain("The beauty of Richmond park...")
      transformed.content.get.media.get.length must be equalTo 3
      transformed.content.get.media.get.head.url.get must be startingWith "https://scontent.cdninstagram.com/vp"
    }

    "create data queries using correct unix timestamp format" in {
      val mapper = new InstagramMediaMapper()
      val fromDate = new DateTime("2018-05-01T09:00:00Z")
      val untilDate = fromDate.plusDays(1)

      val propertyQuery = mapper.dataQueries(Some(fromDate), Some(untilDate))
      propertyQuery.head.orderBy.get must be equalTo "created_time"
      propertyQuery.head.endpoints.head.endpoint must be equalTo "instagram/feed"
      propertyQuery.head.endpoints.head.filters.get.head.operator.asInstanceOf[Between].lower.as[String] must be equalTo "1525165200"
      propertyQuery.head.endpoints.head.filters.get.head.operator.asInstanceOf[Between].upper.as[String] must be equalTo "1525251600"
    }
  }

  "The `mapFacebookPost` method" should {
    "translate facebook photo posts" in {
      val mapper = new FacebookFeedMapper()
      val transformed = mapper.mapDataRecord(exampleFacebookPhotoPost.recordId.get, exampleFacebookPhotoPost.data).get

      transformed.source must be equalTo "facebook"
      transformed.title.get.text must be equalTo "You posted a photo"
      transformed.content.get.media.get.head.url.get must be startingWith "https://scontent.xx.fbcdn.net"
    }

    "translate facebook replies" in {
      val mapper = new FacebookFeedMapper()
      val transformed = mapper.mapDataRecord(exampleFacebookPost.recordId.get, exampleFacebookPost.data).get
      transformed.source must be equalTo "facebook"
      transformed.title.get.text must be equalTo "You posted"
      transformed.content.get.text.get must be startingWith "jetlag wouldn't be so bad if not for  Aileen signing (whistling?) out the window overnight..."
    }

    "translate facebook stories" in {
      val mapper = new FacebookFeedMapper()
      val transformed = mapper.mapDataRecord(facebookStory.recordId.get, facebookStory.data).get
      transformed.source must be equalTo "facebook"
      transformed.title.get.text must be equalTo "You shared a story"
      transformed.content.get.text.get must be startingWith "Guilty. Though works for startups too."
      transformed.content.get.text.get must contain("http://phdcomics.com/comics.php?f=1969")
    }
  }

  "The `mapFacebookEvent` method" should {
    "translate facebook events with location" in {
      val mapper = new FacebookEventMapper()
      val transformed = mapper.mapDataRecord(facebookEvent.recordId.get, facebookEvent.data).get

      transformed.source must be equalTo "facebook"
      transformed.types must contain("event")
      transformed.title.get.text must be equalTo "You are attending an event"
      transformed.content.get.text.get must contain("We're going somewhere new")
      transformed.location.get.address.get.city.get must be equalTo "Singapore"
      transformed.location.get.address.get.name.get must be equalTo "Carlton Hotel Singapore"
    }

    "translate facebook events without location" in {
      val mapper = new FacebookEventMapper()
      val transformed = mapper.mapDataRecord(facebookEvenNoLocation.recordId.get, facebookEvenNoLocation.data).get

      transformed.source must be equalTo "facebook"
      transformed.types must contain("event")
      transformed.title.get.text must be equalTo "You are attending an event"
      transformed.content.get.text.get must contain("privacy, security, access rights, regulation")
      transformed.location must beNone
    }

    "translate facebook events with incomplete location" in {
      val mapper = new FacebookEventMapper()
      val transformed = mapper.mapDataRecord(facebookEvenPartialLocation.recordId.get, facebookEvenPartialLocation.data).get

      transformed.source must be equalTo "facebook"
      transformed.types must contain("event")
      transformed.title.get.text must be equalTo "You are attending an event"
      transformed.content.get.text.get must contain("privacy, security, access rights, regulation")
      transformed.location must beNone
    }
  }

  "The `mapFitbitWeight` method" should {
    "translate fitbit weight" in {
      val mapper = new FitbitWeightMapper()
      val transformed = mapper.mapDataRecord(fitbitWeightMeasurement.recordId.get, fitbitWeightMeasurement.data).get

      transformed.source must be equalTo "fitbit"
      transformed.types must contain("fitness", "weight")
      transformed.title.get.text must be equalTo "You added a new weight measurement"
      transformed.content.get.text.get must contain("94.8")
      transformed.content.get.text.get must contain("25.46")
      transformed.content.get.text.get must contain("21.5")
    }
  }

  "The `mapFitbitSleep` method" should {
    "translate fitbit sleep" in {
      val mapper = new FitbitSleepMapper()
      val transformed = mapper.mapDataRecord(fitbitSleepMeasurement.recordId.get, fitbitSleepMeasurement.data).get

      transformed.source must be equalTo "fitbit"
      transformed.types must contain("fitness", "sleep")
      transformed.title.get.text must contain("You woke up")
      transformed.content.get.text.get must contain("You spent 8 hours and 4 minutes in bed.")
      transformed.content.get.text.get must contain("You slept for 7 hours and 20 minutes ")
      transformed.content.get.text.get must contain("and were awake for 44 minutes")
    }
  }

  "The `mapFitbitActivity` method" should {
    "translate fitbit activity" in {
      val mapper = new FitbitActivityMapper()
      val transformed = mapper.mapDataRecord(fitbitActivity.recordId.get, fitbitActivity.data).get

      transformed.source must be equalTo "fitbit"
      transformed.types must contain("fitness")
      transformed.title.get.text must contain("You logged Fitbit activity")
      transformed.content.get.text.get must contain("Activity: Walk")
      transformed.content.get.text.get must contain("Duration: 17 minutes")
      transformed.content.get.text.get must contain("Average heart rate: 94")
      transformed.content.get.text.get must contain("Calories burned: 126")
    }
  }

  "The `mapFitbitDaySummarySteps` method" should {
    "not generate a feed item for days with 0 steps recorded" in {
      val mapper = new FitbitActivityDaySummaryMapper()
      val transformed = mapper.mapDataRecord(fitbitDayEmptySummary.recordId.get, fitbitDayEmptySummary.data)
      transformed must beAFailedTry
    }

    "translate fitbit day summary to steps" in {
      val mapper = new FitbitActivityDaySummaryMapper()
      val transformed = mapper.mapDataRecord(fitbitDaySummary.recordId.get, fitbitDaySummary.data).get
      transformed.source must be equalTo "fitbit"
      transformed.types must contain("fitness")
      transformed.title.get.text must contain("You walked 12135 steps")
      transformed.content must beNone
    }
  }

  "The `mapGoogleCalendarEvent` method" should {
    "translate google calendar event with timezone information" in {
      val mapper = new GoogleCalendarMapper()
      val transformed = mapper.mapDataRecord(googleCalendarEvent.recordId.get, googleCalendarEvent.data).get
      transformed.source must be equalTo "google"
      transformed.types must contain("event")
      transformed.title.get.text must contain("MadHATTERs Tea Party: The Boston Party")
      transformed.title.get.subtitle.get must contain("12 December 18:30 - 22:30 EST")
      transformed.content.get.text.get must contain("personal data, user accounts, security and value")
    }

    "translate google calendar full-day event" in {
      val mapper = new GoogleCalendarMapper()
      val transformed = mapper.mapDataRecord(googleCalendarFullDayEvent.recordId.get, googleCalendarFullDayEvent.data).get
      transformed.source must be equalTo "google"
      transformed.types must contain("event")
      transformed.content.get.text must beNone
    }
  }

  sequential

  "The `execute` method" should {
    "run mappings for all events" in {
      val request = Request(Map[String, Seq[EndpointData]](
        "twitter" -> Seq(exampleTweetRetweet, exampleTweetMentions),
        "facebook/feed" -> Seq(exampleFacebookPhotoPost, exampleFacebookPost, facebookStory),
        "facebook/events" -> Seq(facebookEvent, facebookEvenNoLocation, facebookEvenPartialLocation),
        "fitbit/sleep" -> Seq(fitbitSleepMeasurement),
        "fitbit/weight" -> Seq(fitbitWeightMeasurement),
        "fitbit/activity" -> Seq(fitbitActivity),
        "fitbit/activity/day/summary" -> Seq(fitbitDaySummary),
        "calendar" -> Seq(googleCalendarEvent, googleCalendarFullDayEvent)), linkRecords = true)

      val response = execute(configuration, request)

      response map { responseRecords =>
        responseRecords.length must be equalTo 14
      } await (3, 10.seconds)
    }

    "skip records where endpoint is not recognised" in {
      val request = Request(Map[String, Seq[EndpointData]](
        "twitter" -> Seq(exampleTweetRetweet, exampleFacebookPost)), linkRecords = true)

      val response = execute(configuration, request)

      response map { responseRecords =>
        responseRecords.length must be equalTo 1
      } await (3, 10.seconds)
    }

    "skip records where data doesn't match the expected structure" in {
      val request = Request(Map[String, Seq[EndpointData]](
        "twitter" -> Seq(exampleTweetRetweet, exampleTweetRetweet.copy(data = exampleFacebookPost.data))), linkRecords = true)

      val response = execute(configuration, request)

      response map { responseRecords =>
        responseRecords.length must be equalTo 1
      } await (3, 10.seconds)
    }
  }

  "The bundle specification" should {
    "Include all matching data" in {
      await(databaseReady)(10.seconds)

      val records = Seq(exampleTweetRetweet, exampleTweetMentions, exampleFacebookPhotoPost, exampleFacebookPost,
        facebookStory, facebookEvent, facebookEvenNoLocation, facebookEvenPartialLocation, fitbitSleepMeasurement,
        fitbitWeightMeasurement, fitbitActivity, fitbitDaySummary, googleCalendarEvent, googleCalendarFullDayEvent)

      val service = application.injector.instanceOf[RichDataService]
      val res = for {
        _ <- service.saveData(owner.userId, records)
        data <- service.bundleData(configuration.dataBundle)
      } yield {
        data.values.toSeq.flatten.length must be equalTo 14
        data("twitter").length must be equalTo 2
        data("facebook/feed").length must be equalTo 3
        data("facebook/events").length must be equalTo 3
        data("calendar").length must be equalTo 2
        data("fitbit/sleep").length must be equalTo 1
        data("fitbit/weight").length must be equalTo 1
        data("fitbit/activity").length must be equalTo 1
        data("fitbit/activity/day/summary").length must be equalTo 1
      }

      res.await(1, 30.seconds)

    }

    "Correctly filter data by date" in {
      await(databaseReady)(10.seconds)
      val from: DateTime = DateTime.parse("2017-09-22T19:24:47+0000")
      val to: DateTime = DateTime.parse("2017-11-05T12:34:55+0000")
      val bundle = registeredFunction.bundleFilterByDate(Some(from), Some(to))

      val records = Seq(exampleTweetRetweet, exampleTweetMentions, exampleFacebookPhotoPost, exampleFacebookPost,
        facebookStory, facebookEvent, facebookEvenNoLocation, facebookEvenPartialLocation, fitbitSleepMeasurement,
        fitbitWeightMeasurement, fitbitActivity, fitbitDaySummary, googleCalendarEvent, googleCalendarFullDayEvent)

      val service = application.injector.instanceOf[RichDataService]
      val res = for {
        _ <- service.saveData(owner.userId, records)
        data <- service.bundleData(bundle)
      } yield {
        data("twitter").length must be equalTo 1
        data("facebook/feed").length must be equalTo 1
        data("facebook/events").length must be equalTo 0
        data("fitbit/sleep").length must be equalTo 0
        data("fitbit/weight").length must be equalTo 1
        data("fitbit/activity").length must be equalTo 0
        data("fitbit/activity/day/summary").length must be equalTo 1
        data("calendar").length must be equalTo 1
      }

      res.await(1, 30.seconds)
    }
  }
}


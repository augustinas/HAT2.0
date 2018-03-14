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
 * 3 / 2018
 */

package org.hatdex.hat.she.service

import java.util.UUID

import akka.NotUsed
import akka.stream.scaladsl.Source
import org.hatdex.hat.api.models.applications._
import org.hatdex.hat.api.models.{ EndpointQuery, EndpointQueryFilter, FilterOperator, PropertyQuery }
import org.hatdex.hat.api.service.richData.RichDataService
import org.hatdex.hat.resourceManagement.HatServer
import org.hatdex.hat.utils.SourceMergeSorter
import org.joda.time.{ DateTime, DateTimeZone }
import org.joda.time.format.ISODateTimeFormat
import play.api.Logger
import play.api.libs.json._

import scala.concurrent.{ ExecutionContext, Future }
import scala.util.{ Failure, Success, Try }

trait DataEndpointMapper extends JodaWrites with JodaReads {
  def feed(fromDate: Option[DateTime], untilDate: Option[DateTime])(implicit ec: ExecutionContext, hatServer: HatServer): Source[DataFeedItem, NotUsed]
}

class GoogleCalendarMapper(richDataService: RichDataService) extends DataEndpointMapper {

  def feed(fromDate: Option[DateTime], untilDate: Option[DateTime])(implicit ec: ExecutionContext, hatServer: HatServer): Source[DataFeedItem, NotUsed] = {
    val fmt = ISODateTimeFormat.dateTime()
    val dateTimeFilter = if (fromDate.isDefined) {
      Some(FilterOperator.Between(Json.toJson(fromDate.map(_.toString(fmt))), Json.toJson(untilDate.map(_.toString(fmt)))))
    }
    else {
      None
    }

    val eventDateTimePropertyQuery = PropertyQuery(
      List(
        EndpointQuery("calendar/google/events", None, Some(Seq(
          dateTimeFilter.map(f => EndpointQueryFilter("start.dateTime", None, f))).flatten), None)),
      Some("start.dateTime"), Some("descending"), None)

    val dateFilter = if (fromDate.isDefined) {
      Some(FilterOperator.Between(Json.toJson(fromDate.map(_.toString("yyyy-MM-dd"))), Json.toJson(untilDate.map(_.toString("yyyy-MM-dd")))))
    }
    else {
      None
    }

    val eventDatePropertyQuery = PropertyQuery(
      List(
        EndpointQuery("calendar/google/events", None, Some(Seq(
          dateFilter.map(f => EndpointQueryFilter("start.date", None, f))).flatten), None)),
      Some("start.date"), Some("descending"), None)

    new SourceMergeSorter().mergeWithSorter(Seq(
      dataFeed(eventDateTimePropertyQuery),
      dataFeed(eventDatePropertyQuery)))

  }

  protected def dataFeed(propertyQuery: PropertyQuery)(implicit hatServer: HatServer, ec: ExecutionContext): Source[DataFeedItem, NotUsed] = {
    val eventualFeed: Future[Seq[DataFeedItem]] = richDataService.propertyData(propertyQuery.endpoints, propertyQuery.orderBy,
      orderingDescending = propertyQuery.ordering.contains("descending"), skip = 0, limit = None, createdAfter = None)(hatServer.db)
      .map { events ⇒
        if (events.nonEmpty) {
          (events.head :: events.sliding(2).collect { case Seq(a, b) if a.data \ "id" != b.data \ "id" => b }.toList)
            .map(item ⇒ mapGoogleCalendarEvent(item.recordId.get, item.data))
            .collect { case Success(x) => x }
        }
        else {
          Seq()
        }
      }
    Source.fromFuture(eventualFeed).mapConcat(f ⇒ f.toList)
  }

  protected implicit def dataFeedItemOrdering: Ordering[DataFeedItem] = Ordering.fromLessThan(_.date isAfter _.date)

  protected def mapGoogleCalendarEvent(recordId: UUID, content: JsValue): Try[DataFeedItem] = {
    for {
      startDate <- Try((content \ "start" \ "dateTime").asOpt[DateTime]
        .getOrElse((content \ "start" \ "date").as[DateTime])
        .withZone((content \ "start" \ "timeZone").asOpt[String].flatMap(z => Try(DateTimeZone.forID(z)).toOption).getOrElse(DateTimeZone.getDefault)))
      endDate <- Try((content \ "end" \ "dateTime").asOpt[DateTime]
        .getOrElse((content \ "end" \ "date").as[DateTime])
        .withZone((content \ "end" \ "timeZone").asOpt[String].flatMap(z => Try(DateTimeZone.forID(z)).toOption).getOrElse(DateTimeZone.getDefault)))
      timeIntervalString <- Try(eventTimeIntervalString(startDate, Some(endDate)))
      itemContent <- Try(DataFeedItemContent(
        Some(s"${timeIntervalString._1} ${timeIntervalString._2.getOrElse("")}"), None))
      location <- Try(DataFeedItemLocation(
        geo = None,
        address = (content \ "location").asOpt[String].map(l ⇒ LocationAddress(None, None, Some(l), None, None)), // TODO integrate with geocoding API for full location information?
        tags = None))
    } yield {
      val title = DataFeedItemTitle(s"${(content \ "summary").as[String]}", Some("event"))
      val loc = Some(location).filter(l => l.address.isDefined || l.geo.isDefined || l.tags.isDefined)
      DataFeedItem("google", startDate, Seq("event"),
        Some(title), Some(itemContent), loc)
    }
  }

  private def eventTimeIntervalString(start: DateTime, end: Option[DateTime]): (String, Option[String]) = {
    val startString = if (start.getMillisOfDay == 0) {
      s"${start.toString("dd MMMM")}"
    }
    else {
      s"${start.withZone(start.getZone).toString("dd MMMM HH:mm")}"
    }

    val endString = end.map {
      case t if t.isAfter(start.withTimeAtStartOfDay().plusDays(1)) =>
        if (t.hourOfDay().get() == 0) {
          s"- ${t.minusMinutes(1).toString("dd MMMM")}"
        }
        else {
          s"- ${t.withZone(start.getZone).toString("dd MMMM HH:mm z")}"
        }
      case t if t.hourOfDay().get() == 0 => ""
      case t                             => s"- ${t.withZone(start.getZone).toString("HH:mm z")}"
    }

    (startString, endString)
  }
}

class FitbitWeightMapper(richDataService: RichDataService) extends DataEndpointMapper {

  def feed(fromDate: Option[DateTime], untilDate: Option[DateTime])(implicit ec: ExecutionContext, hatServer: HatServer): Source[DataFeedItem, NotUsed] = {
    val dateFilter = if (fromDate.isDefined) {
      Some(FilterOperator.Between(Json.toJson(fromDate.map(_.toString("yyyy-MM-dd"))), Json.toJson(untilDate.map(_.toString("yyyy-MM-dd")))))
    }
    else {
      None
    }

    val propertyQuery = PropertyQuery(
      List(
        EndpointQuery("fitbit/weight", None, dateFilter.map(f => Seq(EndpointQueryFilter("date", None, f))), None)),
      Some("date"), Some("descending"), None)

    val feed: Future[Seq[DataFeedItem]] = richDataService.propertyData(propertyQuery.endpoints, propertyQuery.orderBy,
      orderingDescending = propertyQuery.ordering.contains("descending"), skip = 0, limit = None, createdAfter = None)(hatServer.db)
      .map {
        _.map(item ⇒ mapFitbitWeight(item.recordId.get, item.data))
          .collect { case Success(x) => x }
      }

    Source.fromFuture(feed)
      .mapConcat(f ⇒ f.toList)
  }

  protected def mapFitbitWeight(recordId: UUID, content: JsValue): Try[DataFeedItem] = {
    val title = DataFeedItemTitle("You added a new weight measurement", Some("weight"))

    val itemContent = DataFeedItemContent(
      Some(Seq(
        (content \ "weight").asOpt[Double].map(w => s"- Weight: $w"),
        (content \ "fat").asOpt[Double].map(w => s"- Body Fat: $w"),
        (content \ "bmi").asOpt[Double].map(w => s"- BMI: $w")).flatten.mkString("\n")),
      None)

    for {
      date <- Try(JsString(s"${(content \ "date").as[String]}T${(content \ "time").as[String]}").as[DateTime])
    } yield DataFeedItem("fitbit", date, Seq("fitness", "weight"), Some(title), Some(itemContent), None)
  }
}

// calendar events
// facebook events

class FitbitActivityMapper(richDataService: RichDataService) extends DataEndpointMapper {
  def feed(fromDate: Option[DateTime], untilDate: Option[DateTime])(implicit ec: ExecutionContext, hatServer: HatServer): Source[DataFeedItem, NotUsed] = {
    val fmt = ISODateTimeFormat.dateTime()
    val dateTimeFilter = if (fromDate.isDefined) {
      Some(FilterOperator.Between(Json.toJson(fromDate.map(_.toString(fmt))), Json.toJson(untilDate.map(_.toString(fmt)))))
    }
    else {
      None
    }

    val propertyQuery = PropertyQuery(
      List(
        EndpointQuery("fitbit/activity", None, dateTimeFilter.map(f => Seq(EndpointQueryFilter("originalStartTime", None, f))), None)),
      Some("originalStartTime"), Some("descending"), None)

    val feed: Future[Seq[DataFeedItem]] = richDataService.propertyData(propertyQuery.endpoints, propertyQuery.orderBy,
      orderingDescending = propertyQuery.ordering.contains("descending"), skip = 0, limit = None, createdAfter = None)(hatServer.db)
      .map {
        _.map(item ⇒ mapFitbitActivity(item.recordId.get, item.data))
          .collect { case Success(x) => x }
      }

    Source.fromFuture(feed)
      .mapConcat(f ⇒ f.toList)
  }

  protected def mapFitbitActivity(recordId: UUID, content: JsValue): Try[DataFeedItem] = {
    for {
      date <- Try((content \ "originalStartTime").as[DateTime])
    } yield {
      val title = DataFeedItemTitle("You logged Fitbit activity", Some("fitness"))

      val message = Seq(
        (content \ "activityName").asOpt[String].map(c => s"- Activity: $c"),
        (content \ "duration").asOpt[Long].map(c => s"- Duration: ${c / 1000 / 60} minutes"),
        (content \ "averageHeartRate").asOpt[Long].map(c => s"- Average heart rate: $c"),
        (content \ "calories").asOpt[Long].map(c => s"- Calories burned: $c")).flatten.mkString("\n")

      DataFeedItem(
        "fitbit", date, Seq("fitness", "activity"),
        Some(title), Some(DataFeedItemContent(Some(message), None)), None)
    }
  }
}

class FitbitActivityDaySummaryMapper(richDataService: RichDataService) extends DataEndpointMapper {
  def feed(fromDate: Option[DateTime], untilDate: Option[DateTime])(implicit ec: ExecutionContext, hatServer: HatServer): Source[DataFeedItem, NotUsed] = {
    val dateFilter = if (fromDate.isDefined) {
      Some(FilterOperator.Between(Json.toJson(fromDate.map(_.toString("yyyy-MM-dd"))), Json.toJson(untilDate.map(_.toString("yyyy-MM-dd")))))
    }
    else {
      None
    }

    val propertyQuery = PropertyQuery(
      List(
        EndpointQuery("fitbit/activity/day/summary", None, dateFilter.map(f => Seq(EndpointQueryFilter("dateCreated", None, f))), None)),
      Some("dateCreated"), Some("descending"), None)

    val feed: Future[Seq[DataFeedItem]] = richDataService.propertyData(propertyQuery.endpoints, propertyQuery.orderBy,
      orderingDescending = propertyQuery.ordering.contains("descending"), skip = 0, limit = None, createdAfter = None)(hatServer.db)
      .map {
        _.map(item ⇒ mapFitbitDaySummarySteps(item.recordId.get, item.data))
          .collect { case Success(x) => x }
      }

    Source.fromFuture(feed)
      .mapConcat(f ⇒ f.toList)
  }

  protected def mapFitbitDaySummarySteps(recordId: UUID, content: JsValue): Try[DataFeedItem] = {
    val fitbitSummary = for {
      count <- Try((content \ "steps").as[Int]) if count > 0
      date <- Try((content \ "dateCreated").as[DateTime])
    } yield {
      val adjustedDate = if (date.getSecondOfDay == 0) {
        date.secondOfDay().withMaximumValue()
      }
      else {
        date
      }
      val title = DataFeedItemTitle(s"You walked $count steps", Some("fitness"))
      DataFeedItem("fitbit", adjustedDate, Seq("fitness", "activity"),
        Some(title), None, None)
    }

    fitbitSummary recoverWith {
      case _: NoSuchElementException => Failure(new RuntimeException("Fitbit empty day summary"))
    }
  }
}

class FitbitSleepMapper(richDataService: RichDataService) extends DataEndpointMapper {
  protected val logger: Logger = Logger(this.getClass)

  def feed(fromDate: Option[DateTime], untilDate: Option[DateTime])(implicit ec: ExecutionContext, hatServer: HatServer): Source[DataFeedItem, NotUsed] = {
    val fmt = ISODateTimeFormat.dateTime()
    val dateTimeFilter = if (fromDate.isDefined) {
      Some(FilterOperator.Between(Json.toJson(fromDate.map(_.toString(fmt))), Json.toJson(untilDate.map(_.toString(fmt)))))
    }
    else {
      None
    }

    val propertyQuery = PropertyQuery(
      List(
        EndpointQuery("fitbit/sleep", None, dateTimeFilter.map(f => Seq(EndpointQueryFilter("endTime", None, f))), None)),
      Some("endTime"), Some("descending"), None)

    val feed: Future[Seq[DataFeedItem]] = richDataService.propertyData(propertyQuery.endpoints, propertyQuery.orderBy,
      orderingDescending = propertyQuery.ordering.contains("descending"), skip = 0, limit = None, createdAfter = None)(hatServer.db)
      .map {
        _.map(item ⇒ mapFitbitSleep(item.recordId.get, item.data))
          .collect { case Success(x) => x }
      }

    Source.fromFuture(feed)
      .mapConcat(f ⇒ f.toList)
  }

  def fitbitDateCorrector(date: String): String = {
    val zonedDatePattern = """.*(z|Z|\+\d{2})(:?\d{2})?$""".r // does the string end with ISO8601 timezone indicator
    if (zonedDatePattern.findFirstIn(date).isDefined) {
      date
    }
    else {
      date + "+0000"
    }
  }

  override implicit val DefaultJodaDateTimeReads: Reads[DateTime] = jodaDateReads("", fitbitDateCorrector)

  protected def mapFitbitSleep(recordId: UUID, content: JsValue): Try[DataFeedItem] = {
    val title = DataFeedItemTitle("You woke up!", Some("sleep"))

    val timeInBed = (content \ "timeInBed").asOpt[Int]
      .map(t => s"You spent ${t / 60} hours and ${t % 60} minutes in bed.")
    val minutesAsleep = (content \ "minutesAsleep").asOpt[Int]
      .map(asleep => s"You slept for ${asleep / 60} hours and ${asleep % 60} minutes" +
        (content \ "minutesAwake").asOpt[Int].map(t => s" and were awake for $t minutes").getOrElse("") +
        ".")
    val efficiency = (content \ "efficiency").asOpt[Int]
      .map(e => s"Your sleep efficiency score tonight was $e.")

    val itemContent = DataFeedItemContent(
      Some(Seq(timeInBed, minutesAsleep, efficiency).flatten.mkString(" ")),
      None)

    for {
      date <- Try((content \ "endTime").as[DateTime])
    } yield {
      DataFeedItem("fitbit", date, Seq("fitness", "sleep"), Some(title), Some(itemContent), None)
    }
  }

}

class TwitterFeedMapper(richDataService: RichDataService) extends DataEndpointMapper {
  def feed(fromDate: Option[DateTime], untilDate: Option[DateTime])(implicit ec: ExecutionContext, hatServer: HatServer): Source[DataFeedItem, NotUsed] = {
    val fmt = ISODateTimeFormat.dateTime()
    val dateTimeFilter = if (fromDate.isDefined) {
      Some(FilterOperator.Between(Json.toJson(fromDate.map(_.toString(fmt))), Json.toJson(untilDate.map(_.toString(fmt)))))
    }
    else {
      None
    }

    val propertyQuery = PropertyQuery(
      List(
        EndpointQuery("twitter/tweets", None, dateTimeFilter.map(f => Seq(EndpointQueryFilter("lastUpdated", None, f))), None)),
      Some("lastUpdated"), Some("descending"), None)

    val feed: Future[Seq[DataFeedItem]] = richDataService.propertyData(propertyQuery.endpoints, propertyQuery.orderBy,
      orderingDescending = propertyQuery.ordering.contains("descending"), skip = 0, limit = None, createdAfter = None)(hatServer.db)
      .map {
        _.map(item ⇒ mapTweet(item.recordId.get, item.data))
          .collect { case Success(x) => x }
      }

    Source.fromFuture(feed)
      .mapConcat(f ⇒ f.toList)
  }

  protected def mapTweet(recordId: UUID, content: JsValue): Try[DataFeedItem] = {
    for {
      title <- Try(if ((content \ "retweeted").as[Boolean]) {
        DataFeedItemTitle("You retweeted", Some("repeat"))
      }
      else if ((content \ "in_reply_to_user_id").isDefined && !((content \ "in_reply_to_user_id").get == JsNull)) {
        DataFeedItemTitle(s"You replied to @${(content \ "in_reply_to_screen_name").as[String]}", None)
      }
      else {
        DataFeedItemTitle("You tweeted", None)
      })
      itemContent <- Try(DataFeedItemContent((content \ "text").asOpt[String], None))
      date <- Try((content \ "lastUpdated").as[DateTime])
    } yield {
      val location = Try(DataFeedItemLocation(
        geo = (content \ "coordinates").asOpt[JsObject]
          .map(coordinates =>
            LocationGeo(
              (coordinates \ "coordinates" \ 0).as[Double],
              (coordinates \ "coordinates" \ 1).as[Double])),
        address = (content \ "place").asOpt[JsObject]
          .map(address =>
            LocationAddress(
              (address \ "country").asOpt[String],
              (address \ "name").asOpt[String],
              None, None, None)),
        tags = None))
        .toOption
        .filter(l => l.address.isDefined || l.geo.isDefined || l.tags.isDefined)

      DataFeedItem("twitter", date, Seq("post"), Some(title), Some(itemContent), location)
    }
  }
}

class FacebookFeedMapper(richDataService: RichDataService) extends DataEndpointMapper {
  def feed(fromDate: Option[DateTime], untilDate: Option[DateTime])(implicit ec: ExecutionContext, hatServer: HatServer): Source[DataFeedItem, NotUsed] = {
    val fmt = ISODateTimeFormat.dateTime()
    val dateTimeFilter = if (fromDate.isDefined) {
      Some(FilterOperator.Between(Json.toJson(fromDate.map(_.toString(fmt))), Json.toJson(untilDate.map(_.toString(fmt)))))
    }
    else {
      None
    }

    val propertyQuery = PropertyQuery(
      List(
        EndpointQuery("facebook/feed", None, dateTimeFilter.map(f => Seq(EndpointQueryFilter("created_time", None, f))), None)),
      Some("created_time"), Some("descending"), None)

    val feed: Future[Seq[DataFeedItem]] = richDataService.propertyData(propertyQuery.endpoints, propertyQuery.orderBy,
      orderingDescending = propertyQuery.ordering.contains("descending"), skip = 0, limit = None, createdAfter = None)(hatServer.db)
      .map {
        _.map(item ⇒ mapFacebookPost(item.recordId.get, item.data))
          .collect { case Success(x) => x }
      }

    Source.fromFuture(feed)
      .mapConcat(f ⇒ f.toList)
  }

  protected def mapFacebookPost(recordId: UUID, content: JsValue): Try[DataFeedItem] = {
    for {
      title <- Try(if ((content \ "type").as[String] == "photo") {
        DataFeedItemTitle("You posted a photo", Some("photo"))
      }
      else if ((content \ "type").as[String] == "link") {
        DataFeedItemTitle("You shared a story", None)
      }
      else {
        DataFeedItemTitle("You posted", None)
      })
      itemContent <- Try(DataFeedItemContent(
        Some(
          s"""${(content \ "message").as[String]}
             |
             |${(content \ "link").asOpt[String].getOrElse("")}""".stripMargin),
        (content \ "picture").asOpt[String].map(url => List(DataFeedItemMedia(Some(url))))))
      date <- Try((content \ "created_time").as[DateTime])
      tags <- Try(Seq("post", (content \ "type").as[String]))
    } yield DataFeedItem("facebook", date, tags, Some(title), Some(itemContent), None)
  }
}

class NotablesFeedMapper(richDataService: RichDataService) extends DataEndpointMapper {
  def feed(fromDate: Option[DateTime], untilDate: Option[DateTime])(implicit ec: ExecutionContext, hatServer: HatServer): Source[DataFeedItem, NotUsed] = {
    val fmt = ISODateTimeFormat.dateTime()
    val dateTimeFilter = if (fromDate.isDefined) {
      Some(FilterOperator.Between(Json.toJson(fromDate.map(_.toString(fmt))), Json.toJson(untilDate.map(_.toString(fmt)))))
    }
    else {
      None
    }

    val propertyQuery = PropertyQuery(
      List(EndpointQuery("rumpel/notablesv1", None,
        dateTimeFilter.map(f => Seq(EndpointQueryFilter("created_time", None, f))), None)), Some("created_time"), Some("descending"), None)

    val feed: Future[Seq[DataFeedItem]] = richDataService.propertyData(
      propertyQuery.endpoints,
      propertyQuery.orderBy,
      orderingDescending = propertyQuery.ordering.contains("descending"),
      skip = 0, limit = None, createdAfter = None)(hatServer.db)
      .map { endpointData =>
        endpointData.map(item => mapRumpelNotable(item.recordId.get, item.data))
          .collect { case Success(x) => x }
      }

    Source.fromFuture(feed)
      .mapConcat(f => f.toList)
  }

  protected def mapRumpelNotable(recordId: UUID, content: JsValue): Try[DataFeedItem] = {
    for {
      title <- Try(if ((content \ "currently_shared").as[Boolean]) {
        DataFeedItemTitle("You posted", Some("public"))
      }
      else {
        DataFeedItemTitle("You posted", Some("private"))
      })
      itemContent <- Try(if ((content \ "photov1").isDefined && (content \ "photov1" \ "link").as[String].nonEmpty) {
        DataFeedItemContent(Some((content \ "message").as[String]), Some(Seq(DataFeedItemMedia(Some((content \ "photov1" \ "link").as[String])))))
      }
      else {
        DataFeedItemContent(Some((content \ "message").as[String]), None)
      })
      location <- Try(if ((content \ "locationv1").isDefined) {
        Some(DataFeedItemLocation(Some(LocationGeo(
          (content \ "locationv1" \ "longitude").as[Double],
          (content \ "locationv1" \ "latitude").as[Double])), None, None))
      }
      else {
        None
      })
      date <- Try((content \ "created_time").as[DateTime])
      tags <- Try((content \ "shared_on").as[Seq[String]])
    } yield DataFeedItem("notables", date, tags, Some(title), Some(itemContent), None)
  }
}

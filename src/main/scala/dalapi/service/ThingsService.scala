package dalapi.service

import dal.SlickPostgresDriver.simple._
import dal.Tables._
import dalapi.models._
import org.joda.time.LocalDateTime
import spray.http.MediaTypes._
import spray.http.StatusCodes._
import spray.httpx.SprayJsonSupport._
import spray.routing._

import scala.util.{Failure, Success, Try}


// this trait defines our service behavior independently from the service actor
trait ThingsService extends EntityServiceApi {
  val entityKind = "thing"

  val routes = {
    pathPrefix(entityKind) {
      create ~
        linkThingToPerson ~
        linkThingToThing ~
        linkToPropertyStatic ~
        linkToPropertyDynamic ~
        addType
    }
  }

  import JsonProtocol._

  def createEntity = entity(as[ApiThing]) { thing =>
    db.withSession { implicit session =>
      val thingsthingRow = new ThingsThingRow(0, LocalDateTime.now(), LocalDateTime.now(), thing.name)
      val result = Try((ThingsThing returning ThingsThing) += thingsthingRow)

      complete {
        result match {
          case Success(createdThing) =>
            ApiThing.fromDbModel(createdThing)
          case Failure(e) =>
            (BadRequest, e.getMessage)
        }
      }
    }
  }

  def linkThingToPerson = path(IntNumber / "person" / IntNumber) { (thingId: Int, personId: Int) =>
    post {
      entity(as[ApiRelationship]) { relationship =>
        db.withSession { implicit session =>
          val recordId = createRelationshipRecord(s"thing/$thingId/person/$personId:${relationship.relationshipType}")

          val crossref = new ThingsThingpersoncrossrefRow(0, LocalDateTime.now(), LocalDateTime.now(),
            personId, thingId, relationship.relationshipType, true, recordId)
          val result = Try((ThingsThingpersoncrossref returning ThingsThingpersoncrossref.map(_.id)) += crossref)

          // Return the created crossref
          complete {
            result match {
              case Success(crossrefId) =>
                (Created, ApiGenericId(crossrefId))
              case Failure(e) =>
                (BadRequest, e.getMessage)
            }
          }

        }
      }
    }
  }

  /*
   * Link two things together, e.g. as one thing part of another thing with a parentChild relationship type
   */
  def linkThingToThing = path(IntNumber / "thing" / IntNumber) { (thingId: Int, thing2Id: Int) =>
    post {
      entity(as[ApiRelationship]) { relationship =>
        db.withSession { implicit session =>
          val recordId = createRelationshipRecord(s"thing/$thingId/thing/$thing2Id:${relationship.relationshipType}")

          val crossref = new ThingsThingtothingcrossrefRow(0, LocalDateTime.now(), LocalDateTime.now(),
            thingId, thing2Id, relationship.relationshipType, true, recordId)
          val result = Try((ThingsThingtothingcrossref returning ThingsThingtothingcrossref.map(_.id)) += crossref)

          // Return the created crossref
          complete {
            result match {
              case Success(crossrefId) =>
                (Created, ApiGenericId(crossrefId))
              case Failure(e) =>
                (BadRequest, e.getMessage)
            }
          }

        }
      }
    }
  }

  /*
   * Link thing to a property statically (tying it in with a specific record ID)
   */
  protected def createPropertyLinkDynamic(entityId: Int, propertyId: Int,
                                                   fieldId: Int, relationshipType: String, propertyRecordId: Int)
                                                  (implicit session: Session) : Try[Int] = {
    val crossref = new ThingsSystempropertydynamiccrossrefRow(
      0, LocalDateTime.now(), LocalDateTime.now(),
      entityId, propertyId,
      fieldId, relationshipType,
      true, propertyRecordId)

    Try((ThingsSystempropertydynamiccrossref returning ThingsSystempropertydynamiccrossref.map(_.id)) += crossref)
  }

  /*
   * Link thing to a property dynamically
   */
  protected def createPropertyLinkStatic(entityId: Int, propertyId: Int,
                                                  recordId: Int, fieldId: Int, relationshipType: String, propertyRecordId: Int)
                                                 (implicit session: Session) : Try[Int] = {
    val crossref = new ThingsSystempropertystaticcrossrefRow(
      0, LocalDateTime.now(), LocalDateTime.now(),
      entityId, propertyId,
      recordId, fieldId, relationshipType,
      true, propertyRecordId)

    Try((ThingsSystempropertystaticcrossref returning ThingsSystempropertystaticcrossref.map(_.id)) += crossref)
  }

  /*
   * Tag thing with a type
   */
  protected def addEntityType(entityId: Int, typeId: Int, relationship: ApiRelationship)
                             (implicit session: Session) : Try[Int] = {
    val entityType = new ThingsSystemtypecrossrefRow(0, LocalDateTime.now(), LocalDateTime.now(),
      entityId, typeId, relationship.relationshipType, true)
    Try((ThingsSystemtypecrossref returning ThingsSystemtypecrossref.map(_.id)) += entityType)
  }

  def getLocations(thingId: Int)
                  (implicit session: Session): Seq[ApiLocationRelationship] = {

    Seq();
  }

  def getOrganisations(thingID: Int)
                      (implicit session: Session): Seq[ApiOrganisationRelationship] = {
    Seq();
  }

  def getPeople(thingID: Int)
               (implicit session: Session): Seq[ApiPersonRelationship] = {
    val links = ThingsThingpersoncrossref.filter(_.thingId === thingID).run

    links flatMap { link: ThingsThingpersoncrossrefRow =>
      val apiPerson = getPerson(link.personId)
      apiPerson.map { person =>
        new ApiPersonRelationship(link.relationshipType, person)
      }
    }
  }

  def getEvents(eventID: Int)
               (implicit session: Session): Seq[ApiEventRelationship] = {
    Seq();
  }

  def getThings(thingID: Int)
               (implicit session: Session): Seq[ApiThingRelationship] = {
    val thingLinks = ThingsThingtothingcrossref.filter(_.thingOneId === thingID).run
    var thingIds = thingLinks.map(_.thingTwoId)

    thingLinks flatMap { link: ThingsThingtothingcrossrefRow =>
      val apiThing = getThing(link.thingTwoId)
      apiThing.map { thing =>
        new ApiThingRelationship(link.relationshipType, thing)
      }
    }
  }

  protected def getPropertiesStatic(thingId: Int)
                                   (implicit session: Session): Seq[ApiPropertyRelationshipStatic] = {

    val crossrefQuery = ThingsSystempropertystaticcrossref.filter(_.thingId === thingId)

    val dataQuery = for {
      crossref <- crossrefQuery
      property <- crossref.systemPropertyFk
      propertyType <- property.systemTypeFk
      propertyUom <- property.systemUnitofmeasurementFk
      field <- crossref.dataFieldFk
      record <- crossref.dataRecordFk
    } yield (crossref, property, propertyType, propertyUom, field, record)

    val data = dataQuery.run

    data.map {
      case (crossref: ThingsSystempropertystaticcrossrefRow, property: SystemPropertyRow, propertyType: SystemTypeRow,
      propertyUom: SystemUnitofmeasurementRow, field: DataFieldRow, record: DataRecordRow) =>
        ApiPropertyRelationshipStatic.fromDbModel(crossref, property, propertyType, propertyUom, field, record)
    }
  }

  protected def getPropertiesDynamic(thingId: Int)
                                    (implicit session: Session): Seq[ApiPropertyRelationshipDynamic] = {

    val crossrefQuery = ThingsSystempropertydynamiccrossref.filter(_.thingId === thingId)

    val dataQuery = for {
      crossref <- crossrefQuery
      property <- crossref.systemPropertyFk
      propertyType <- property.systemTypeFk
      propertyUom <- property.systemUnitofmeasurementFk
      field <- crossref.dataFieldFk
    } yield (crossref, property, propertyType, propertyUom, field)

    val data = dataQuery.run

    data.map {
      case (crossref: ThingsSystempropertydynamiccrossrefRow, property: SystemPropertyRow, propertyType: SystemTypeRow,
      propertyUom: SystemUnitofmeasurementRow, field: DataFieldRow) =>
        ApiPropertyRelationshipDynamic.fromDbModel(crossref, property, propertyType, propertyUom, field)
    }
  }

}

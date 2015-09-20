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

trait EntityServiceApi extends HttpService with EntityService with HatApiService {

  import JsonProtocol._

  def create = path("") {
    post {
      createEntity
    }
  }


  def getPropertiesStaticApi = path(IntNumber / "property" / "static") {
    (entityId: Int) =>
      get {
        db.withSession { implicit session: Session =>
          complete {
            getPropertiesStatic(entityId)
          }
        }
      }
  }

  def getPropertiesDynamicApi = path(IntNumber / "property" / "dynamic") {
    (entityId: Int) =>
      get {
        db.withSession { implicit session: Session =>
          complete {
            getPropertiesDynamic(entityId)
          }
        }
      }
  }

  /*
   * Tag event with a type
   */
  def addType = path(IntNumber / "type" / IntNumber) { (entityId: Int, typeId: Int) =>
    post {
      entity(as[ApiRelationship]) { relationship =>
        db.withSession { implicit session =>
          val result = addEntityType(entityId, typeId, relationship)

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
   * Link event to a property statically (tying it in with a specific record ID)
   */
  def linkToPropertyStatic = path(IntNumber / "property" / "static" / IntNumber) { (entityId: Int, propertyId: Int) =>
    post {
      entity(as[ApiPropertyRelationshipStatic]) { relationship =>
        val result: Try[Int] = (relationship.field.id, relationship.record.id) match {
          case (Some(fieldId), Some(recordId)) =>
            val propertyRecordId = createPropertyRecord(
              s"$entityKind/$entityId/property/static/$propertyId:${relationship.relationshipType}($fieldId,$recordId,${relationship.relationshipType}")

            db.withSession { implicit session =>
              createPropertyLinkStatic(entityId, propertyId, recordId, fieldId, relationship.relationshipType, propertyRecordId)
            }
          case (None, _) =>
            Failure(new IllegalArgumentException("Property relationship must have an existing Data Field with ID"))
          case (_, None) =>
            Failure(new IllegalArgumentException("Static Property relationship must have an existing Data Record with ID"))
        }

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

  /*
   * Link event to a property dynamically
   */
  def linkToPropertyDynamic = path(IntNumber / "property" / "dynamic" / IntNumber) { (entityId: Int, propertyId: Int) =>
    post {
      entity(as[ApiPropertyRelationshipDynamic]) { relationship =>
        val result: Try[Int] = relationship.field.id match {
          case Some(fieldId) =>
            val propertyRecordId = createPropertyRecord(
              s"""$entityKind/$entityId/property/dynamic/$propertyId:${relationship.relationshipType}
                  |($fieldId,${relationship.relationshipType})""".stripMargin)

            db.withSession { implicit session =>
              createPropertyLinkDynamic(entityId, propertyId, fieldId, relationship.relationshipType, propertyRecordId)
            }
          case None =>
            Failure(new IllegalArgumentException("Property relationship must have an existing Data Field with ID"))
        }

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
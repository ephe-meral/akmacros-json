/** Evgeny Chukreev, 2012. http://akshaal.info */

package info.akshaal.test

import info.akshaal.json.play._
import play.api.libs.json._
import org.specs2._

class JsonSpec extends Specification with matcher.ScalaCheckMatchers {
    def is =
        "(De)Serialization to/from Json should work" ^
            "in simple cases" ! simpleExample ^
            "in simple cases with implicit reads/writes" ! simpleImplExample ^
            "in more complicated cases" ! serExample ^
            "in more complicated cases with implicit reads/writes" ! serImplExample ^
            "by matching extra fields on abstract type" ! extraFieldExample ^
            "with annotated fields" ! annotatedExample

    // Domain objects
    case class Simple(str: String, num: Int = 0)

    sealed trait Message
    case class QuitMessage(msg: String) extends Message
    case class Heartbeat(id: Int) extends Message

    case class Messages(userId: Int, messages: List[Message] = List.empty)
    case class Event[T](kind: String, payloads: T)

    def simpleExample = {
        implicit val simpleReads = factory[Simple]('fromJson).toReads
        implicit val simpleWrites = allFields[Simple]('jsonate).toWrites

        val obj = new Simple("123", 5)
        (Json.fromJson[Simple](Json.toJson(obj)) must_== obj) and
            (Json.fromJson[Simple](JsObject(List("str" -> JsString("x")))) must_== Simple("x"))
    }

    def simpleImplExample = {
        implicit val simpleJsFactory = factory[Simple]('fromJson)
        implicit val simpleJsFields = allFields[Simple]('jsonate)

        val obj = new Simple("123", 5)
        (Json.fromJson[Simple](Json.toJson(obj)) must_== obj) and
            (Json.fromJson[Simple](JsObject(List("str" -> JsString("x")))) must_== Simple("x"))
    }

    def serExample = {
        // How to write json objects
        implicit lazy val messageWrites: Writes[Message] = matchingWrites[Message] {
            case m: QuitMessage => quitMessageWrites.writes(m)
            case m: Heartbeat   => heartbeatWrites.writes(m)
        }

        implicit lazy val simpleWrites: Writes[Simple] = allFields[Simple]('jsonate).toWrites
        implicit lazy val quitMessageWrites: Writes[QuitMessage] = allFields[QuitMessage]('jsonate).toWrites
        implicit lazy val heartbeatWrites: Writes[Heartbeat] = allFields[Heartbeat]('jsonate).toWrites
        implicit lazy val messagesWrites: Writes[Messages] = allFields[Messages]('jsonate).toWrites
        implicit def eventWrites[T: Writes] = allFields[Event[T]]('jsonate).toWrites

        // How to read json objects
        implicit lazy val simpleReads: Reads[Simple] = factory[Simple]('fromJson).toReads
        implicit lazy val quitMessageReads: Reads[QuitMessage] = factory[QuitMessage]('fromJson).toReads
        implicit lazy val heartbeatReads: Reads[Heartbeat] = factory[Heartbeat]('fromJson).toReads
        implicit lazy val messagesReads: Reads[Messages] = factory[Messages]('fromJson).toReads
        implicit def eventReads[T: Reads] = factory[Event[T]]('fromJson).toReads

        implicit lazy val messageReads: Reads[Message] = matchingReads[Message] {
            case js: JsObject if js.value contains "msg" => quitMessageReads.reads(js)
            case js: JsObject if js.value contains "id"  => heartbeatReads.reads(js)
        }

        val event =
            Event(kind = "t1",
                payloads = Messages(userId = 4, messages = List(Heartbeat(5), QuitMessage("bye!"))))

        val json = """{"kind":"t1","payloads":{"userId":4,"messages":[{"id":5},{"msg":"bye!"}]}}"""

        (Json.toJson(event).toString.trim aka "toJson" must_== json.trim) and
            (Json.fromJson[Event[Messages]](Json.toJson(event)) aka "fromToJson" must_== event)
    }

    def serImplExample = {
        // How to write json objects
        implicit def messageWrites = matchingWrites[Message] {
            case m: QuitMessage => quitMessageJsFields.toWrites.writes(m)
            case m: Heartbeat   => heartbeatJsFields.toWrites.writes(m)
        }

        implicit def simpleJsFields = allFields[Simple]('jsonate)
        implicit def quitMessageJsFields = allFields[QuitMessage]('jsonate)
        implicit def heartbeatJsFields = allFields[Heartbeat]('jsonate)
        implicit def messagesJsFields = allFields[Messages]('jsonate)
        implicit def eventJsFields[T: Writes] = allFields[Event[T]]('jsonate)

        // How to read json objects
        implicit def simpleJsFactory = factory[Simple]('fromJson)
        implicit def quitMessageJsFactory = factory[QuitMessage]('fromJson)
        implicit def heartbeatJsFactory = factory[Heartbeat]('fromJson)
        implicit def messagesJsFactory = factory[Messages]('fromJson)
        implicit def eventJsFactory[T: Reads] = factory[Event[T]]('fromJson)

        implicit def messageReads: Reads[Message] =
            predicatedReads[Message](
                jsHas('msg) -> quitMessageJsFactory,
                jsHas('id) -> heartbeatJsFactory
            )

        val event =
            Event(kind = "t1",
                payloads = Messages(userId = 4, messages = List(Heartbeat(5), QuitMessage("bye!"))))

        val json = """{"kind":"t1","payloads":{"userId":4,"messages":[{"id":5},{"msg":"bye!"}]}}"""

        (Json.toJson(event).toString.trim aka "toJson" must_== json.trim) and
            (Json.fromJson[Event[Messages]](Json.toJson(event)) aka "fromToJson" must_== event)
    }

    def extraFieldExample = {
        // How to write json objects
        implicit def messageWrites = matchingWrites[Message] {
            case m: QuitMessage => quitMessageJsFields.extra('type -> 'quit).toWrites.writes(m)
            case m: Heartbeat   => heartbeatJsFields.extra('type -> 'heart).toWrites.writes(m)
        }

        implicit def quitMessageJsFields = allFields[QuitMessage]('jsonate)
        implicit def heartbeatJsFields = allFields[Heartbeat]('jsonate)

        val messages = List(Heartbeat(1), QuitMessage("Hello"), Heartbeat(99))
        val messagesJs = Json.toJson(messages)
        val messagesJsStr = """ [{"type":"heart","id":1},{"type":"quit","msg":"Hello"},{"type":"heart","id":99}] """

        // How to read json objects
        implicit def quitMessageJsFactory = factory[QuitMessage]('fromJson)
        implicit def heartbeatJsFactory = factory[Heartbeat]('fromJson)

        implicit def messageReads: Reads[Message] =
            predicatedReads[Message](
                jsHas('type -> 'quit)  -> quitMessageJsFactory,
                jsHas('type -> 'heart) -> heartbeatJsFactory
            )

        val messages2 = Json.fromJson[List[Message]](messagesJs)

        // Test
        (messagesJs must_== Json.parse(messagesJsStr)) and
            (messages2 must_== messages)
    }

    def annotatedExample = {
        @annotation.meta.getter
        class Ok extends annotation.StaticAnnotation

        case class User(@Ok login: String,
                        @Ok fullName: String,
                        @Ok messages: Int = 0,
                        passwordHash: Option[String] = None)

        implicit val userJsFields = annotatedFields[User, Ok]('jsonate)

        val user = User(login = "akshaal",
                        fullName = "Evgeny Chukreev",
                        messages = 10,
                        passwordHash = Some("4d18758602c08243d7c08f8c9e4463b0"))

        val userJs = Json.toJson(user)

        userJs must_== Json.parse("""{"login":"akshaal","fullName":"Evgeny Chukreev","messages":10}""")
    }
}

//  LocalWords:  ScalaCheck fromToJson HB

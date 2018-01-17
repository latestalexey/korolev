package korolev.internal

import java.util.concurrent.atomic.AtomicInteger

import korolev.{Async, Router}
import korolev.Async._
import korolev.Router.Path
import levsha.Id
import levsha.impl.DiffRenderContext.ChangesPerformer
import slogging.LazyLogging

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.{Failure, Success}

/**
  * Typed interface to client side
  */
final class ClientSideApi[F[+ _]: Async](connection: Connection[F])
  extends ChangesPerformer with LazyLogging {

  import ClientSideApi._

  private val lastDescriptor = new AtomicInteger(0)
  private val async = Async[F]
  private val promises = TrieMap.empty[String, Promise[F, String]]
  private val domChangesBuffer = mutable.ArrayBuffer.empty[Any]

  private var onHistory: HistoryCallback = _
  private var onFormDataProgress: FormDataProgressCallback = _
  private var onEvent: EventCallback = _

  private def isProperty(name: String) =
    name.charAt(0) == '^'

  private def isStyle(name: String) =
    name.charAt(0) == '*'

  private def escapeName(name: String, truncate: Boolean) =
    if (truncate) name.substring(1)
    else name

  /**
    * @param onEvent            (renderNum, target, eventType) => Unit
    * @param onFormDataProgress (descriptor, loaded, total)
    */
  def setHandlers(onHistory: HistoryCallback,
                  onEvent: EventCallback,
                  onFormDataProgress: FormDataProgressCallback): Unit = {
    this.onHistory = onHistory
    this.onEvent = onEvent
    this.onFormDataProgress = onFormDataProgress
  }

  def listenEvent(name: String, preventDefault: Boolean): Unit =
    connection.send(Procedure.ListenEvent.code, name, preventDefault)

  def uploadForm(id: Id, descriptor: String): Unit =
    connection.send(Procedure.UploadForm.code, id.mkString, descriptor)

  def focus(id: Id): Unit =
    connection.send(Procedure.Focus.code, id.mkString)

  def extractProperty(id: Id, name: String): F[String] = {
    val descriptor = lastDescriptor.getAndIncrement().toString
    val promise = async.promise[String]
    promises.put(descriptor, promise)
    connection.send(Procedure.ExtractProperty.code, descriptor, id.mkString, name)
    promise.future
  }

  def setProperty(id: Id, name: Symbol, value: Any): Unit = {
    // TODO setProperty should be dedicated
    connection.send(Procedure.ModifyDom.code, ModifyDomProcedure.SetAttr.code, id.mkString, 0, name.name, value, true)
  }

  def changePageUrl(path: Path): Unit =
    connection.send(Procedure.ChangePageUrl.code, path.toString)

  def setRenderNum(i: Int): Unit =
    connection.send(Procedure.SetRenderNum.code, i)

  def cleanRoot(): Unit =
    connection.send(Procedure.CleanRoot.code)

  def reloadCss(): Unit =
    connection.send(Procedure.ReloadCss.code)

  def startDomChanges(): Unit = {
    domChangesBuffer.append(Procedure.ModifyDom.code)
  }

  def flushDomChanges(): Unit = {
    connection.send(domChangesBuffer: _*)
    domChangesBuffer.clear()
  }

  def remove(id: Id): Unit =
    domChangesBuffer.append(ModifyDomProcedure.Remove.code, id.parent.get.mkString, id.mkString)

  def createText(id: Id, text: String): Unit =
    domChangesBuffer.append(ModifyDomProcedure.CreateText.code, id.parent.get.mkString, id.mkString, text)

  def create(id: Id, xmlNs: String, tag: String): Unit = {
    val parent = id.parent.fold("0")(_.mkString)
    val pXmlns =
      if (xmlNs eq levsha.XmlNs.html.uri) 0
      else xmlNs
    domChangesBuffer.append(ModifyDomProcedure.Create.code, parent, id.mkString, pXmlns, tag)
  }

  def setAttr(id: Id, xmlNs: String, name: String, value: String): Unit = {
    if (isStyle(name)) {
      val n = escapeName(name, truncate = true)
      domChangesBuffer.append(ModifyDomProcedure.SetStyle.code, id.mkString, n, value)
    } else {
      val p = isProperty(name)
      val n = escapeName(name, p)
      val pXmlns =
        if (xmlNs eq levsha.XmlNs.html.uri) 0
        else xmlNs
      domChangesBuffer.append(ModifyDomProcedure.SetAttr.code, id.mkString, pXmlns, n, value, p)
    }
  }

  def removeAttr(id: Id, xmlNs: String, name: String): Unit = {
    if (isStyle(name)) {
      val n = escapeName(name, truncate = true)
      domChangesBuffer.append(ModifyDomProcedure.RemoveStyle.code, id.mkString, n)
    } else {
      val p = isProperty(name)
      val n = escapeName(name, p)
      val pXmlns =
        if (xmlNs eq levsha.XmlNs.html.uri) 0
        else xmlNs
      domChangesBuffer.append(ModifyDomProcedure.RemoveAttr.code, id.mkString, pXmlns, n, p)
    }
  }

  private def onReceive(): Unit = connection.received.run {
    case Success(json) =>
      val Array(callbackType, dirtyArgs) = json
        .substring(1, json.length - 1) // remove brackets
        .split(",", 2) // split to tokens
      val args = dirtyArgs
        .substring(1, dirtyArgs.length - 1) // remove ""
      callbackType.toInt match {
        case CallbackType.DomEvent.code =>
          val Array(renderNum, target, tpe) = args.split(':')
          onEvent(renderNum.toInt, Id(target), tpe)
        case CallbackType.FormDataProgress.code =>
          val Array(descriptor, loaded, total) = args.split(':')
          onFormDataProgress(descriptor, loaded.toInt, total.toInt)
        case CallbackType.ExtractPropertyResponse.code =>
          val Array(descriptor, propertyType, value) = args.split(":", 3)
          val result = propertyType.toInt match {
            case PropertyType.Error.code => Failure(ClientSideException(value))
            case _ => Success(value)
          }
          promises
            .remove(descriptor)
            .foreach(_.complete(result))
        case CallbackType.History.code =>
          onHistory(Router.Path.fromString(args))
      }
      onReceive()
    case Failure(e) =>
      logger.error("Unable to receive message from client", e)
  }

  onReceive()
}

object ClientSideApi {

  type HistoryCallback = Path => Unit
  type EventCallback = (Int, Id, String) => Unit
  type FormDataProgressCallback = (String, Int, Int) => Unit

  sealed abstract class Procedure(final val code: Int)

  object Procedure {
    case object SetRenderNum extends Procedure(0) // (n)
    case object CleanRoot extends Procedure(1) // ()
    case object ListenEvent extends Procedure(2) // (type, preventDefault)
    case object ExtractProperty extends Procedure(3) // (id, propertyName, descriptor)
    case object ModifyDom extends Procedure(4) // (commands)
    case object Focus extends Procedure(5) // (id) {
    case object ChangePageUrl extends Procedure(6) // (path)
    case object UploadForm extends Procedure(7) // (id, descriptor)
    case object ReloadCss extends Procedure(8) // ()

    val All = Set(
      SetRenderNum, CleanRoot, ListenEvent, ExtractProperty,
      ModifyDom, Focus, ChangePageUrl, UploadForm, ReloadCss
    )

    def apply(n: Int): Option[Procedure] =
      All.find(_.code == n)
  }

  sealed abstract class ModifyDomProcedure(final val code: Int)

  object ModifyDomProcedure {
    case object Create extends ModifyDomProcedure(0) // (id, childId, xmlNs, tag)
    case object CreateText extends ModifyDomProcedure(1) // (id, childId, text)
    case object Remove extends ModifyDomProcedure(2) // (id, childId)
    case object SetAttr extends ModifyDomProcedure(3) // (id, xmlNs, name, value, isProperty)
    case object RemoveAttr extends ModifyDomProcedure(4) // (id, xmlNs, name, isProperty)
    case object SetStyle extends ModifyDomProcedure(5) // (id, name, value)
    case object RemoveStyle extends ModifyDomProcedure(6) // (id, name)
  }

  sealed abstract class PropertyType(final val code: Int)
  
  object PropertyType {
    case object String extends PropertyType(0)
    case object Number extends PropertyType(1)
    case object Boolean extends PropertyType(2)
    case object Object extends PropertyType(3)
    case object Error extends PropertyType(4)
  }

  sealed abstract class CallbackType(final val code: Int)

  object CallbackType {
    case object DomEvent extends CallbackType(0) // `$renderNum:$elementId:$eventType`
    case object FormDataProgress extends CallbackType(1) // `$descriptor:$loaded:$total`
    case object ExtractPropertyResponse extends CallbackType(2) // `$descriptor:$value`
    case object History extends CallbackType(3) // URL

    final val All = Set(DomEvent, FormDataProgress, ExtractPropertyResponse, History)

    def apply(n: Int): Option[CallbackType] =
      All.find(_.code == n)
  }

  case class ClientSideException(message: String) extends Exception(message)
}
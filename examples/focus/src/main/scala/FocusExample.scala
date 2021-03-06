import korolev._
import korolev.blazeServer._
import korolev.execution._
import korolev.server._

import scala.concurrent.Future
import korolev.state.javaSerialization._

object FocusExample extends KorolevBlazeServer {

  val globalContext = Context[Future, Boolean, Any]

  import globalContext._
  import symbolDsl._

  // Handler to input
  val inputId = elementId()

  val service = blazeService[Future, Boolean, Any] from KorolevServiceConfig[Future, Boolean, Any](
    stateStorage = StateStorage.default(false),
    router = emptyRouter,
    render = {
      case _ =>
        'body(
          'div("Focus example"),
          'div(
            'input(
              inputId,
              'type /= "text",
              'placeholder /= "Wanna get some focus?"
            )
          ),
          'div(
            'button(
              event('click) { access =>
                access.focus(inputId)
              },
              "Click to focus"
            )
          )
        )
    }
  )
}

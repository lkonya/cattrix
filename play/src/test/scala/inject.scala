package chm

import javax.inject.Inject

import scala.concurrent.{Future, ExecutionContext}
import scala.collection.mutable.ArrayBuffer

import cats.~>
import org.specs2.Specification
import com.google.inject.{TypeLiteral, AbstractModule}
import play.api.Application
import play.api.mvc.{AbstractController, ControllerComponents, Results}
import play.api.inject.bind
import play.api.libs.ws.WSClient
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test._
import play.api.test.Helpers._
import mockws.MockWS
import mockws.MockWSHelpers.Action

import FutureInstances._

object Ctrl
{
  def metric = "met"
}

class Ctrl @Inject() (components: ControllerComponents, http: Http[Future])
(implicit ec: ExecutionContext)
extends AbstractController(components)
{
  def act = Action.async {
    http.get("url", Ctrl.metric).map {
      case Right(response) => Ok(response.body)
      case Left(error) => NotFound(error)
    }
  }
}

class MetricsSpec(implicit ec: ExecutionContext)
extends PlaySpecification
{
  val payload = "ok!"

  val ws = MockWS {
    case a => Action(Results.Ok(payload))
  }

  val metricsLog: ArrayBuffer[(String, Metric[Future, _])] = ArrayBuffer()

  def interpreter(resources: MetricTask[Codahale[Future]]): Metric[Future, ?] ~> Future =
    new (Metric[Future, ?] ~> Future) {
      def apply[A](action: Metric[Future, A]): Future[A] = {
        metricsLog.append((resources.metric, action))
        NoMetrics.interpreter[Future].apply(action)
      }
    }

  val coda = CustomInterpreter[Future](interpreter)

  val mod = new AbstractModule {
    override def configure(): Unit = {
      bind(new TypeLiteral[CodahaleInterpreter[Future]](){}).toInstance(coda)
    }
  }

  val application: Application =
    GuiceApplicationBuilder()
      .bindings(new FutureHttpModule)
      .overrides(mod)
      .overrides(bind[WSClient].to(ws))
      .build()

  def http = new WithApplication(application) {
    val ctrl = app.injector.instanceOf[Ctrl]
    val result = ctrl.act()(FakeRequest())
    contentAsString(result).must_==(payload)
      .and(metricsLog.toList.filter {
        case (_, Run(a)) => false
        case _ => true
      }.must_==(List(
        (Ctrl.metric, StartTimer("requestTimer")),
        (Ctrl.metric, IncCounter("activeRequests")),
        (Ctrl.metric, DecCounter("activeRequests")),
        (Ctrl.metric, StopTimer(TimerData("requestTimer", 0))),
        (Ctrl.metric, Mark("200")),
      )))
  }

  "inject a logging codahale interpreter" >> http
}

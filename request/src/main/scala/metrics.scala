package cattrix

import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.effect.Sync

object RequestMetricPrograms
{
  def responseMetric[F[_]: Sync, In, Out]
  (metric: RequestMetric[F, In, Out], response: Out)
  (implicit res: HttpResponse[F, Out])
  : Metrics.Step[F, Unit] = {
    for {
      _ <- Metrics.mark[F](res.status(response).toString)
      _ <- metric.error(response)
    } yield ()
  }

  def resultMetrics[F[_]: Sync, In, Out]
  (metric: RequestMetric[F, In, Out], result: Either[Throwable, Out])
  (implicit res: HttpResponse[F, Out])
  : Metrics.Step[F, Unit] =
    result match {
      case Right(r) => responseMetric(metric, r)
      case Left(_) => Metrics.mark[F]("fatal")
    }

  def simpleTimed[F[_]: Sync, M, In, Out](
    task: RequestTask[F, In, Out],
    request: () => F[Out],
  )
  (implicit res: HttpResponse[F, Out])
  : Metrics.Step[F, Out] = {
    for {
      t <- Metrics.timer("time")
      _ <- Metrics.incCounter("active")
      response <- Metrics.attempt(request)
      _ <- Metrics.decCounter("active")
      _ <- Metrics.time(t)
      _ <- resultMetrics(task.metric, response)
      result <- Metrics.result(response)
    } yield result
  }
}

object RequestMetrics
{
  def wrapRequest[F[_]: Sync, M, In, Out]
  (resources: M)
  (task: RequestTask[F, In, Out])
  (request: () => F[Out])
  (implicit metrics: Metrics[F, M], res: HttpResponse[F, Out])
  : F[Out] = {
    for {
      name <- task.metric.name(task.request)
      prog = RequestMetricPrograms.simpleTimed(task, request)
      result <- Metrics.compile(MetricTask(resources, name))(prog)
    } yield result
  }
}

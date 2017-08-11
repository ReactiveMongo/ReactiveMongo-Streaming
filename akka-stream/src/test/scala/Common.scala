object Common {
  import scala.concurrent._
  import scala.concurrent.duration._
  import reactivemongo.api._

  val primaryHost = Option(System getProperty "test.primaryHost").
    getOrElse("localhost:27017")

  val failoverRetries = Option(System getProperty "test.failoverRetries").
    flatMap(r => scala.util.Try(r.toInt).toOption).getOrElse(7)

  private val timeoutFactor = 1.25D
  def estTimeout(fos: FailoverStrategy): FiniteDuration =
    (1 to fos.retries).foldLeft(fos.initialDelay) { (d, i) =>
      d + (fos.initialDelay * ((timeoutFactor * fos.delayFactor(i)).toLong))
    }

  val failoverStrategy = FailoverStrategy(retries = failoverRetries)

  val timeout: FiniteDuration = {
    val maxTimeout = estTimeout(failoverStrategy)

    if (maxTimeout < 10.seconds) 10.seconds
    else maxTimeout
  }

  lazy val driver = new MongoDriver

  val DefaultOptions = {
    val opts = MongoConnectionOptions(
      failoverStrategy = failoverStrategy)

    if (Option(System getProperty "test.enableSSL").exists(_ == "true")) {
      opts.copy(sslEnabled = true, sslAllowsInvalidCert = true)
    } else opts
  }

  lazy val connection = driver.connection(List(primaryHost), DefaultOptions)

  lazy val db = {
    import ExecutionContext.Implicits.global

    val _db = for {
      d <- connection.database(
        s"rm-akkastream-${System identityHashCode getClass}")
      _ <- d.drop()
    } yield d

    Await.result(_db, timeout)
  }

  def close(): Unit = try {
    driver.close()
  } catch { case _: Throwable => () }
}

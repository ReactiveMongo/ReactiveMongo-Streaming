object Common {
  import scala.concurrent.{ Await, ExecutionContext }
  import scala.concurrent.duration._
  import reactivemongo.api.{
    FailoverStrategy,
    MongoDriver,
    MongoConnectionOptions
  }

  val logger = reactivemongo.util.LazyLogger("tests")

  val DefaultOptions = {
    val opts = MongoConnectionOptions.default

    if (Option(System getProperty "test.enableSSL").exists(_ == "true")) {
      opts.copy(sslEnabled = true, sslAllowsInvalidCert = true)
    } else opts
  }

  val primaryHost =
    Option(System getProperty "test.primaryHost").getOrElse("localhost:27017")

  val failoverRetries = Option(System getProperty "test.failoverRetries").
    flatMap(r => scala.util.Try(r.toInt).toOption).getOrElse(7)

  private val driverReg = Seq.newBuilder[MongoDriver]
  def newDriver(): MongoDriver = driverReg.synchronized {
    val drv = MongoDriver()

    driverReg += drv

    drv
  }

  lazy val driver = newDriver()
  lazy val connection = driver.connection(List(primaryHost), DefaultOptions)

  val failoverStrategy = FailoverStrategy(retries = failoverRetries)

  private val timeoutFactor = 1.2D
  def estTimeout(fos: FailoverStrategy): FiniteDuration =
    (1 to fos.retries).foldLeft(fos.initialDelay) { (d, i) =>
      d + (fos.initialDelay * ((timeoutFactor * fos.delayFactor(i)).toLong))
    }

  implicit val timeout: FiniteDuration = {
    val maxTimeout = estTimeout(failoverStrategy)
    if (maxTimeout < 10.seconds) 10.seconds else maxTimeout
  }

  //val timeoutMillis = timeout.toMillis.toInt

  lazy val db = {
    import ExecutionContext.Implicits.global

    val _db = connection.database(
      "specs2-reactivemongo-iteratees", failoverStrategy
    )

    Await.result(_db.flatMap { d => d.drop.map(_ => d) }, timeout)
  }

  def close(): Unit = {
    driverReg.result().foreach { driver =>
      try {
        driver.close(timeout)
      } catch {
        case e: Throwable =>
          e.printStackTrace()
        /*
          logger.warn(s"Fails to stop driver: $e")
          logger.debug("Fails to stop driver", e)
           */
      }
    }
  }

  Runtime.getRuntime.addShutdownHook(new Thread() {
    override def run() = close()
  })
}

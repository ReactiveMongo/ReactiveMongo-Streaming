object Common {
  import scala.concurrent._
  import scala.concurrent.duration._
  import reactivemongo.api._

  val timeout = 10 seconds

  lazy val driver = new MongoDriver
  lazy val connection = driver.connection(List("localhost:27017"))
  lazy val db = {
    import ExecutionContext.Implicits.global

    val _db = for {
      d <- connection.database("specs2-reactivemongo-akkastreams")
      _ <- d.drop()
    } yield d

    Await.result(_db, timeout)
  }

  def closeDriver(): Unit = try {
    driver.close()
  } catch { case _: Throwable => () }
}

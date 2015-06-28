# Akka Streams for ReactiveMongo

This is an Akka Streams extension for the ReactiveMongo cursors.

## Usage

In your `project/Build.scala`:

```scala
resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "rectivemongo" % "0.11.0",
  "org.reactivemongo" %% "reactivemongo-akkastreams" % "0.11.0-SNAPSHOT")
```

Then in your code:

```scala
import scala.concurrent.Future

import reactivemongo.bson.BSONDocument
import reactivemongo.api.Cursor

// Reactive streams imports
import org.reactivestreams.Publisher
import akka.stream.scaladsl.Source

// ReactiveMongo extensions
import reactivemongo.akkastreams.cursorProducer

implicit val system = akka.actor.ActorSystem("reactivemongo-akkastreams")
implicit val materializer = akka.stream.ActorFlowMaterializer.create(system)

val cursor = Cursor.flatten(collection(n).map(_.find(BSONDocument()).
  sort(BSONDocument("id" -> 1)).cursor[Int]))

val src: Future[Source[Int, Unit]] = cursor("sourceName").source()

val pub: Future[Publisher[Int]] = cursor("sourceName").publisher("publisher")
```

## Documentation

The API documentation is [available online](https://cchantep.github.io/RM-AkkaStreams/api/).

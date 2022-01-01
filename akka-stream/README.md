# Akka Streams for ReactiveMongo

This is an [Akka Streams](http://akka.io) extension for the ReactiveMongo cursors.

## Usage

In your `project/Build.scala`:

```ocaml
resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "rectivemongo" % VERSION,
  "org.reactivemongo" %% "reactivemongo-akkastream" % VERSION)
```

[![Maven](https://img.shields.io/maven-central/v/org.reactivemongo/reactivemongo-akkastream_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Creactivemongo-akkastream) [![Javadocs](https://javadoc.io/badge/org.reactivemongo/reactivemongo-akkastream_2.12.svg)](https://javadoc.io/doc/org.reactivemongo/reactivemongo-akkastream_2.12)

> Java 1.8+ is required.

Then in your code:

```scala
import scala.concurrent.Future

import reactivemongo.api.bson.{ BSONDocument, BSONDocumentReader }
import reactivemongo.api.bson.collection.BSONCollection

// Reactive streams imports
import org.reactivestreams.Publisher
import akka.stream.scaladsl.Source

// ReactiveMongo extensions
import reactivemongo.akkastream.{ AkkaStreamCursor, cursorProducer, State }

implicit def materializer: akka.stream.Materializer = ???

val ageReader = BSONDocumentReader.field[Int]("age")

def foo(collection: BSONCollection): (Source[Int, Future[State]], Publisher[Int]) = {
  implicit def reader: BSONDocumentReader[Int] = ageReader

  val cursor: AkkaStreamCursor[Int] =
    collection.find(BSONDocument.empty/* findAll */).
    sort(BSONDocument("id" -> 1)).cursor[Int]()

  val src: Source[Int, Future[State]] = cursor.documentSource()
  val pub: Publisher[Int] = cursor.documentPublisher()

  src -> pub
}
```

> More [examples](./src/test/scala/CursorSpec.scala)

## Documentation

The developer guide is [available online](http://reactivemongo.org/releases/0.12/documentation/tutorial/streaming.html#akka-stream).

The API documentation is [available online](https://reactivemongo.github.io/ReactiveMongo-Streaming/0.12/akka-stream/api/).

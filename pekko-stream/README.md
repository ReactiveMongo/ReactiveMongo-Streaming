# Pekko Streams for ReactiveMongo

This is an [Pekko Streams](http://pekko.apache.org) extension for the ReactiveMongo cursors.

## Usage

In your `project/Build.scala`:

```ocaml
resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "reactivemongo" % VERSION,
  "org.reactivemongo" %% "reactivemongo-pekkostream" % VERSION)
```

[![Maven](https://img.shields.io/maven-central/v/org.reactivemongo/reactivemongo-pekkostream_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Creactivemongo-pekkostream) [![Javadocs](https://javadoc.io/badge/org.reactivemongo/reactivemongo-pekkostream_2.12.svg)](https://javadoc.io/doc/org.reactivemongo/reactivemongo-pekkostream_2.12)

> Java 1.8+ is required, and `VERSION` must include the `-pekko` qualifier.

Then in your code:

```scala
import scala.concurrent.Future

import reactivemongo.api.bson.{ BSONDocument, BSONDocumentReader }
import reactivemongo.api.bson.collection.BSONCollection

// Reactive streams imports
import org.reactivestreams.Publisher
import org.apache.pekko.stream.scaladsl.Source

// ReactiveMongo extensions
import reactivemongo.pekkostream.{ PekkoStreamCursor, cursorProducer, State }

implicit def materializer: org.apache.pekko.stream.Materializer = ???

val ageReader = BSONDocumentReader.field[Int]("age")

def foo(collection: BSONCollection): (Source[Int, Future[State]], Publisher[Int]) = {
  implicit def reader: BSONDocumentReader[Int] = ageReader

  val cursor: PekkoStreamCursor[Int] =
    collection.find(BSONDocument.empty/* findAll */).
    sort(BSONDocument("id" -> 1)).cursor[Int]()

  val src: Source[Int, Future[State]] = cursor.documentSource()
  val pub: Publisher[Int] = cursor.documentPublisher()

  src -> pub
}
```

> More [examples](./src/test/scala/CursorSpec.scala)

## Documentation

The developer guide is [available online](http://reactivemongo.org/releases/1.0/documentation/tutorial/streaming.html#pekko-stream).

The API documentation is [available online](https://reactivemongo.github.io/ReactiveMongo-Streaming/1.0/pekko-stream/api/).

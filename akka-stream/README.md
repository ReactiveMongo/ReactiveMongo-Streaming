# Akka Streams for ReactiveMongo

This is an [Akka Streams](http://akka.io) extension for the ReactiveMongo cursors.

## Usage

In your `project/Build.scala`:

```ocaml
val reactiveMongoVer = "0.12.0-SNAPSHOT"

resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "rectivemongo" % reactiveMongoVer,
  "org.reactivemongo" %% "reactivemongo-akkastream" % reactiveMongoVer)
```

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.reactivemongo/reactivemongo-akkastream_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.reactivemongo/reactivemongo-akkastream_2.11/)

> Java 1.8+ is required.

Then in your code:

```scala
import scala.concurrent.ExecutionContext.Implicits.global

import reactivemongo.bson.{ BSONDocument, BSONDocumentReader }
import reactivemongo.api.collections.bson.BSONCollection

// Reactive streams imports
import org.reactivestreams.Publisher
import akka.stream.scaladsl.Source

// ReactiveMongo extensions
import reactivemongo.akkastream.{ AkkaStreamCursor, cursorProducer }

implicit val system = akka.actor.ActorSystem("reactivemongo-akkastream")
implicit val materializer = akka.stream.ActorMaterializer.create(system)

implicit val reader = BSONDocumentReader[Int] { doc =>
  doc.getAsTry[Int]("age").get
}

def foo(collection: BSONCollection): (Source[Int, akka.NotUsed], Publisher[Int]) = {
  val cursor: AkkaStreamCursor[Int] =
    collection.find(BSONDocument.empty/* findAll */).
    sort(BSONDocument("id" -> 1)).cursor[Int]()

  val src: Source[Int, akka.NotUsed] = cursor.documentSource()

  val pub: Publisher[Int] = cursor.documentPublisher()

  src -> pub
}
```

> More [examples](.src/test/scala/CursorSpec.scala)

## Documentation

The developer guide is [available online](http://reactivemongo.org/releases/0.12/documentation/tutorial/streaming.html#akka-stream).

The API documentation is [available online](https://reactivemongo.github.io/ReactiveMongo-Streaming/0.12/akka-stream/api/).

## Build manually

The Akka Streams extension for ReactiveMongo can be built from this source repository.

    sbt publish-local

To run the tests, use:

    sbt test

(https://travis-ci.org/ReactiveMongo/ReactiveMongo-Streaming): [![Build Status](https://travis-ci.org/ReactiveMongo/ReactiveMongo-Streaming.svg?branch=master)](https://travis-ci.org/ReactiveMongo/ReactiveMongo-Streaming)
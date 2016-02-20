# Akka Streams for ReactiveMongo

This is an Akka Streams extension for the ReactiveMongo cursors.

## Usage

In your `project/Build.scala`:

```scala
val reactiveMongoVer = "0.11.10"

resolvers += "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/"

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "rectivemongo" % reactiveMongoVer,
  "org.reactivemongo" %% "reactivemongo-akkastreams" % s"$reactiveMongoVer-SNAPSHOT")
```

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.reactivemongo/reactivemongo-akkastreams_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.reactivemongo/reactivemongo-akkastreams_2.11/)

> Java 1.8+ is required.

Then in your code:

```scala
import reactivemongo.bson.{ BSONDocument, BSONDocumentWriter }
import reactivemongo.api.Cursor

// Reactive streams imports
import org.reactivestreams.Publisher
import akka.stream.scaladsl.Source

// ReactiveMongo extensions
import reactivemongo.akkastreams.cursorProducer

implicit val system = akka.actor.ActorSystem("reactivemongo-akkastreams")
implicit val materializer = akka.stream.ActorMaterializer.create(system)

val cursor = Cursor.flatten(collection(n).map(_.find(BSONDocument()).
  sort(BSONDocument("id" -> 1)).cursor[Int]))

val src: Source[Int, akka.NotUsed] = cursor("sourceName").source()

val pub: Publisher[Int] = cursor("sourceName").publisher("publisher")
```

## Build manually

The Akka Streams extension for ReactiveMongo can be built from this source repository.

    sbt publish-local

To run the tests, use:

    sbt test

[Travis](https://travis-ci.org/cchantep/RM-AkkaStreams): [![Build Status](https://travis-ci.org/cchantep/RM-AkkaStreams.svg?branch=master)](https://travis-ci.org/cchantep/RM-AkkaStreams)

## Documentation

The API documentation is [available online](https://cchantep.github.io/RM-AkkaStreams/api/).

# Iteratees for ReactiveMongo

This is the Play Iteratees extension for the ReactiveMongo cursors.

## Usage

The [Play Iteratees](https://www.playframework.com/documentation/latest/Iteratees) library can work with streams of MongoDB documents.

The dependencies can be updated as follows in your `project/Build.scala`:

```ocaml
val reactiveMongoVer = "0.12.1"
val playVer = "2.3.10" // or greater

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "rectivemongo" % reactiveMongoVer,
  "org.reactivemongo" %% "reactivemongo-iteratees" % reactiveMongoVer,
  "com.typesafe.play" %% "play-iteratees" % playVer)
```

[![Maven Central](https://maven-badges.herokuapp.com/maven-central/org.reactivemongo/reactivemongo-iteratees_2.11/badge.svg)](https://maven-badges.herokuapp.com/maven-central/org.reactivemongo/reactivemongo-iteratees_2.11/)

> Java 1.8+ is required.

## Documentation

The developer guide is [available online](http://reactivemongo.org/releases/0.12/documentation/tutorial/streaming.html#play-iteratees).

The API documentation is [available online](https://reactivemongo.github.io/ReactiveMongo-Streaming/0.12/iteratees/api/).

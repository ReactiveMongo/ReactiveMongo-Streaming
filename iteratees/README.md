# Iteratees for ReactiveMongo

This is the Play Iteratees extension for the ReactiveMongo cursors.

## Usage

The [Play Iteratees](https://www.playframework.com/documentation/latest/Iteratees) library can work with streams of MongoDB documents.

The dependencies can be updated as follows in your `project/Build.scala`:

```ocaml
val playVer = "2.3.10" // or greater

libraryDependencies ++= Seq(
  "org.reactivemongo" %% "rectivemongo" % VERSION,
  "org.reactivemongo" %% "reactivemongo-iteratees" % VERSION,
  "com.typesafe.play" %% "play-iteratees" % playVer)
```

[![Maven](https://img.shields.io/maven-central/v/org.reactivemongo/reactivemongo-iteratees_2.12.svg)](http://search.maven.org/#search%7Cga%7C1%7Creactivemongo-iteratees) [![Javadocs](https://javadoc.io/badge/org.reactivemongo/reactivemongo-iteratees_2.12.svg)](https://javadoc.io/doc/org.reactivemongo/reactivemongo-iteratees_2.12)

> Java 1.8+ is required.

## Documentation

The developer guide is [available online](http://reactivemongo.org/releases/0.12/documentation/tutorial/streaming.html#play-iteratees).

The API documentation is [available online](https://reactivemongo.github.io/ReactiveMongo-Streaming/0.12/iteratees/api/).

name := "txGeneratorEncry"

version := "0.1"

scalaVersion := "2.12.8"

val http4sVersion = "0.21.0-M5"

resolvers ++= Seq(
  "Sonatype Public" at "https://oss.sonatype.org/content/groups/public/",
  "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Typesafe maven releases" at "https://repo.typesafe.com/typesafe/maven-releases/"
)

val fs2 = Seq(
  "co.fs2" %% "fs2-core" % "2.0.1",
  "co.fs2" %% "fs2-io" % "2.0.1",
)

libraryDependencies ++= Seq(
  "org.typelevel"  %% "cats-effect" % "2.0.0-RC2",
  "io.chrisdavenport" %% "log4cats-slf4j" % "0.4.0-M2",
  "com.comcast" %% "ip4s-cats" % "1.2.1",
  "com.typesafe.akka" %% "akka-actor" % "2.5.13",
  "ch.qos.logback" % "logback-core" % "1.2.3",
  "org.http4s" %% "http4s-dsl" % http4sVersion,
  "org.http4s" %% "http4s-blaze-server" % http4sVersion,
  "org.http4s" %% "http4s-blaze-client" % http4sVersion,
  "org.http4s" %% "http4s-circe" % http4sVersion,
  "org.iq80.leveldb" % "leveldb" % "0.9",
  "org.encry" %% "encry-common" % "0.9.2",
  "com.google.guava" % "guava" % "27.1-jre",
  "io.circe" %% "circe-generic" % "0.11.2",
) ++ fs2

addCompilerPlugin("org.typelevel"  % "kind-projector" % "0.11.0" cross CrossVersion.full)

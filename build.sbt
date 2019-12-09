name := "txGeneratorEncry"

version := "0.1"

scalaVersion := "2.12.8"

resolvers ++= Seq(
  "Sonatype Public" at "https://oss.sonatype.org/content/groups/public/",
  "Sonatype Releases" at "https://oss.sonatype.org/content/repositories/releases/",
  "Sonatype Snapshots" at "https://oss.sonatype.org/content/repositories/snapshots/",
  "Typesafe maven releases" at "https://repo.typesafe.com/typesafe/maven-releases/"
)

libraryDependencies ++= Seq(
  "org.typelevel"  %% "cats-effect" % "2.0.0-RC2",
  "io.chrisdavenport" %% "log4cats-slf4j" % "0.4.0-M2",
  "co.fs2" %% "fs2-io" % "2.1.0",
  "com.comcast" %% "ip4s-cats" % "1.2.1",
  "ch.qos.logback" % "logback-core" % "1.2.3",
  "org.iq80.leveldb" % "leveldb" % "0.9",
  "org.encry" %% "encry-common" % "0.9.2",
  "com.google.guava" % "guava" % "27.1-jre"
)

addCompilerPlugin("org.typelevel"  % "kind-projector" % "0.11.0" cross CrossVersion.full)

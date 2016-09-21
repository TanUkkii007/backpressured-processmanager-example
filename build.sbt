name := "backpressured-processmanager-example"

scalaVersion := "2.11.8"

scalacOptions ++= Seq("-feature", "-deprecation", "-language:implicitConversions", "-language:postfixOps")

organization := "github.com/TanUkkii007"

val akkaVersion = "2.4.10"
val scalatestVersion = "2.2.6"

libraryDependencies ++= Seq(
  "com.typesafe.akka" %% "akka-actor" % akkaVersion,
  "com.typesafe.akka" %% "akka-stream" % akkaVersion,
  "com.typesafe.akka" %% "akka-testkit" % akkaVersion % "test",
  "org.scalatest" %% "scalatest" % scalatestVersion % "test"
)

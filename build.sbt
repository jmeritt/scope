organization := "com.tjd"
name := "live-microservice"
version := "0.1"
scalaVersion := "2.11.7"


scalacOptions += "-feature"

libraryDependencies ++= {
	val akkaStreamVersion = "1.0"
	val akkaVersion = "2.3.11"

	Seq(
		"com.typesafe.akka" %% "akka-actor" % akkaVersion withSources(),
    "com.typesafe.akka" %% "akka-stream-experimental" % akkaStreamVersion withSources(),
    "com.typesafe.akka" %% "akka-http-experimental" % akkaStreamVersion withSources(),
    "com.typesafe.akka" %% "akka-http-core-experimental" % akkaStreamVersion withSources(),
    "com.typesafe.akka" %% "akka-http-spray-json-experimental" % akkaStreamVersion withSources()
	)
}

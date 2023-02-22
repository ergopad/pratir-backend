name := """pratir-backend"""
organization := "com.example"

version := "1.0-SNAPSHOT"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

scalaVersion := "2.12.17"

libraryDependencies += guice
libraryDependencies += "org.scalatestplus.play" %% "scalatestplus-play" % "5.0.0" % Test
libraryDependencies += "org.javatuples" % "javatuples" % "1.2"

libraryDependencies ++= Seq(
  "com.typesafe.play" %% "play-slick" % "5.0.0",
  "org.postgresql" % "postgresql" % "42.2.12"
)

// https://mvnrepository.com/artifact/org.ergoplatform/ergo-appkit
libraryDependencies += "org.ergoplatform" %% "ergo-appkit" % "5.0.0"

libraryDependencies ++= Seq(
  "org.springframework.security" % "spring-security-crypto" % "5.6.2"
)

// https://mvnrepository.com/artifact/com.amazonaws/aws-java-sdk
libraryDependencies ++= Seq(
  "com.amazonaws" % "aws-java-sdk" % "1.12.409",
  "com.fasterxml.jackson.module" %% "jackson-module-scala" % "2.12.1"
)

// Adds additional packages into Twirl
// TwirlKeys.templateImports += "com.example.controllers._"

// Adds additional packages into conf/routes
// play.sbt.routes.RoutesKeys.routesImport += "com.example.binders._"

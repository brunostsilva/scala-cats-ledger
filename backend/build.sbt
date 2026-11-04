ThisBuild / scalaVersion := "3.10.0"
ThisBuild / version := "0.1.0"

val http4sVersion = "0.23.38"
val circeVersion  = "0.14.17"

lazy val root = (project in file("."))
  .settings(
    name := "ledger-api",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % "3.7.1",
      "org.http4s"    %% "http4s-ember-server" % http4sVersion,
      "org.http4s"    %% "http4s-dsl"          % http4sVersion,
      "org.http4s"    %% "http4s-circe"        % http4sVersion,
      "io.circe"      %% "circe-core"          % circeVersion,
      "org.scalameta" %% "munit"               % "1.3.6" % Test,
      "org.typelevel" %% "munit-cats-effect"   % "2.2.1" % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )

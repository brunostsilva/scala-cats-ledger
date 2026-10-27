ThisBuild / scalaVersion := "3.3.3"
ThisBuild / version := "0.1.0"

val http4sVersion = "0.23.27"
val circeVersion  = "0.14.9"

lazy val root = (project in file("."))
  .settings(
    name := "ledger-api",
    libraryDependencies ++= Seq(
      "org.typelevel" %% "cats-effect"         % "3.5.4",
      "org.http4s"    %% "http4s-ember-server" % http4sVersion,
      "org.http4s"    %% "http4s-dsl"          % http4sVersion,
      "org.http4s"    %% "http4s-circe"        % http4sVersion,
      "io.circe"      %% "circe-core"          % circeVersion,
      "io.circe"      %% "circe-generic"       % circeVersion,
      "org.scalameta" %% "munit"               % "1.0.0"  % Test,
      "org.typelevel" %% "munit-cats-effect"   % "2.0.0"  % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )

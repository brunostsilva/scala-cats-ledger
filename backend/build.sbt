ThisBuild / scalaVersion := "3.10.0"
ThisBuild / version := "0.1.0"

val http4sVersion   = "0.23.38"
val circeVersion    = "0.14.17"
val log4catsVersion = "2.8.0"

lazy val root = (project in file("."))
  .settings(
    name := "ledger-api",
    libraryDependencies ++= Seq(
      "org.typelevel"  %% "cats-effect"         % "3.7.1",
      "org.http4s"     %% "http4s-ember-server" % http4sVersion,
      "org.http4s"     %% "http4s-dsl"          % http4sVersion,
      "org.http4s"     %% "http4s-circe"        % http4sVersion,
      "io.circe"       %% "circe-core"          % circeVersion,
      // Used directly (transfer streaming); pinned to the version http4s 0.23.38 is built against.
      "co.fs2"         %% "fs2-core"            % "3.14.0",
      // Logging as effects (log4cats) over SLF4J, with Logback as the backend.
      "org.typelevel"  %% "log4cats-slf4j"      % log4catsVersion,
      "ch.qos.logback"  % "logback-classic"     % "1.6.6",
      "org.scalameta"  %% "munit"               % "1.3.6" % Test,
      "org.typelevel"  %% "munit-cats-effect"   % "2.2.1" % Test,
      "org.typelevel"  %% "log4cats-testing"    % log4catsVersion % Test,
      "org.http4s"     %% "http4s-ember-client" % http4sVersion % Test
    ),
    testFrameworks += new TestFramework("munit.Framework")
  )

ThisBuild / scalaVersion     := "2.13.10"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "com.example"
ThisBuild / organizationName := "example"

val zioVersion = "2.0.5"
lazy val root = (project in file("."))
  .settings(
    name := "ubirch-scaling-problem",
    libraryDependencies ++= Seq(
      "dev.zio" %% "zio" % zioVersion,
      "dev.zio" %% "zio-streams" % zioVersion % Test,
      "dev.zio" %% "zio-test" % zioVersion % Test,
      "dev.zio" %% "zio-test-sbt" % zioVersion % Test,
      "dev.zio" %% "zio-kafka" % "2.0.1",
      "dev.zio" %% "zio-mock" % "1.0.0-RC9",
      "dev.zio" %% "zio-json" % "0.4.2"
    ),
    testFrameworks += new TestFramework("zio.test.sbt.ZTestFramework")
  )

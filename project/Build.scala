import sbt._, Keys._

object Webbing extends Build {
  val libVersion = "0.1.0"
  val utilVersion = "6.23.0"
  val finagleVersion = "6.24.0"

  val sharedSettings = Seq(
    version := libVersion,
    organization := "com.twitter",
    scalaVersion := "2.10.4",
    crossScalaVersions := Seq("2.10.4", "2.11.4"),
    libraryDependencies ++= Seq(
      "com.twitter" %% "util-core" % utilVersion,
      "org.scalatest" %% "scalatest" % "2.2.2" % "test",
      "junit" % "junit" % "4.10" % "test"
    ),
    scalacOptions ++= Seq("-deprecation")
  )

  lazy val webbing = Project(
    id = "webbing",
    base = file("."),
    settings = Project.defaultSettings ++ sharedSettings ++ Unidoc.settings ++ Seq(
      Unidoc.unidocExclude := Seq(webbingExample.id)
    )
  ).settings(
    name := "webbing"
  ).aggregate(webbingRoute, webbingRouteFinagleHttp, webbingExample)

  lazy val webbingRoute = Project(
    id = "webbing-route",
    base = file("route"),
    settings = Project.defaultSettings ++ sharedSettings
  ).settings(
    name := "webbing-route"
  )

  lazy val webbingRouteFinagleHttp = Project(
    id = "webbing-route-finagle-http",
    base = file("route-finagle-http"),
    settings = Project.defaultSettings ++ sharedSettings
  ).settings(
    name := "webbing-route-finagle-http",
    libraryDependencies ++= Seq(
      "commons-io" % "commons-io" % "2.4",
      "com.twitter" %% "finagle-http" % "6.24.0"
    )
  ).dependsOn(webbingRoute)

  lazy val webbingExample = Project(
    id = "webbing-example",
    base = file("example"),
    settings = Project.defaultSettings ++ sharedSettings
  ).settings(
    name := "webbing-example",
    libraryDependencies ++= Seq(
      "com.twitter" %% "twitter-server" % "1.9.0"
    ),
    resolvers += "twitter-repo" at "http://maven.twttr.com"
  ).dependsOn(webbingRouteFinagleHttp)
}

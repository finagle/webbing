val libVersion = "0.1.0"

val sharedSettings = Seq(
  version := libVersion,
  organization := "com.twitter",
  scalaVersion := "2.11.7",
  crossScalaVersions := Seq("2.10.5", "2.11.7"),
  libraryDependencies ++= Seq(
    "com.twitter" %% "util-core" % "6.28.0",
    "org.scalatest" %% "scalatest" % "2.2.5" % "test",
    "junit" % "junit" % "4.12" % "test"
  ),
  scalacOptions ++= Seq("-deprecation")
)

lazy val webbing = project.in(file("."))
  .settings(moduleName := "webbing")
  .settings(sharedSettings)
  .settings(unidocSettings)
  .settings {
    import UnidocKeys._

    unidocProjectFilter in (ScalaUnidoc, unidoc) := inAnyProject -- inProjects(example)
  }.aggregate(route, routeFinagleHttp, example)

lazy val route = project
  .settings(moduleName := "webbing-route")
  .settings(sharedSettings: _*)

lazy val routeFinagleHttp = project.in(file("route-finagle-http"))
  .settings(moduleName := "webbing-route-finagle-http")
  .settings(sharedSettings: _*)
  .settings(
    libraryDependencies ++= Seq(
      "commons-io" % "commons-io" % "2.4",
      "com.twitter" %% "finagle-httpx" % "6.29.0"
    )
  ).dependsOn(route)

lazy val example = project
  .settings(moduleName := "webbing-example")
  .settings(
    libraryDependencies += "com.twitter" %% "twitter-server" % "1.14.0",
    resolvers += "twitter-repo" at "https://maven.twttr.com"
  ).dependsOn(routeFinagleHttp)

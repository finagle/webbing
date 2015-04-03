val libVersion = "0.1.0"

val sharedSettings = Seq(
  version := libVersion,
  organization := "com.twitter",
  scalaVersion := "2.11.6",
  crossScalaVersions := Seq("2.10.5", "2.11.6"),
  libraryDependencies ++= Seq(
    "com.twitter" %% "util-core" % "6.23.0",
    "org.scalatest" %% "scalatest" % "2.2.3" % "test",
    "junit" % "junit" % "4.10" % "test"
  ),
  scalacOptions ++= Seq("-deprecation")
)

lazy val webbing = project.in(file("."))
  .settings(moduleName := "webbing")
  .settings(sharedSettings: _*)
  .settings(unidocSettings: _*)
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
      "com.twitter" %% "finagle-http" % "6.24.0"
    )
  ).dependsOn(route)

lazy val example = project
  .settings(moduleName := "webbing-example")
  .settings(
    libraryDependencies += "com.twitter" %% "twitter-server" % "1.9.0",
    resolvers += "twitter-repo" at "http://maven.twttr.com"
  ).dependsOn(routeFinagleHttp)

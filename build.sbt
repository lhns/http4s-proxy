lazy val scalaVersions = Seq("3.3.8", "2.13.18")

ThisBuild / scalaVersion := scalaVersions.head
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / organization := "de.lhns"
// Test / parallelExecution only serializes within a project, but sbt runs the test task of
// each matrix row concurrently. The integration suite stands up real servers on real ports
// and makes timing assertions, so two rows racing each other is enough to fail it.
Global / concurrentRestrictions += Tags.limit(Tags.Test, 1)
// Without this the sonatype staging bundle is written under the default 0.1.0-SNAPSHOT
// rather than the release version. Both fs2-compress and doobie-flyway carry it.
ThisBuild / version := (core.projectRefs.head / version).value
name := (core.projectRefs.head / name).value

val V = new {
  val catsEffect = "3.5.7"
  val fs2 = "3.14.0"
  val http4s = "0.23.36"
  val http4sJdkHttpClient = "0.10.0"
  val http4sTest = "0.23.36"
  val logbackClassic = "1.6.3"
  val munit = "1.3.5"
  val munitCatsEffect = "2.2.0"
}

lazy val commonSettings: SettingsDefinition = Def.settings(
  version := {
    val Tag = "refs/tags/v?([0-9]+(?:\\.[0-9]+)+(?:[+-].*)?)".r
    sys.env
      .get("CI_VERSION")
      .collect { case Tag(tag) => tag }
      .getOrElse("0.0.1-SNAPSHOT")
  },
  description := "Utilities to create proxies in http4s",
  licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0")),
  homepage := scmInfo.value.map(_.browseUrl),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/lhns/http4s-proxy"),
      "scm:git@github.com:lhns/http4s-proxy.git"
    )
  ),
  developers := List(
    Developer(
      id = "lhns",
      name = "Pierre Kisters",
      email = "pierrekisters@gmail.com",
      url = url("https://github.com/lhns/")
    )
  ),
  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit" % V.munit % Test,
    "org.typelevel" %%% "munit-cats-effect" % V.munitCatsEffect % Test,
    // The IO runtime is a test-only dependency: the library itself compiles against
    // cats-effect-kernel and -std so that it never forces a runtime on consumers.
    "org.typelevel" %%% "cats-effect" % V.catsEffect % Test,
    "org.typelevel" %%% "cats-effect-testkit" % V.catsEffect % Test
  ),
  testFrameworks += new TestFramework("munit.Framework"),
  Compile / doc / sources := Seq.empty,
  publishMavenStyle := true,
  publishTo := sonatypePublishToBundle.value,
  sonatypeCredentialHost := Sonatype.sonatypeCentralHost,
  credentials ++= (for {
    username <- sys.env.get("SONATYPE_USERNAME")
    password <- sys.env.get("SONATYPE_PASSWORD")
  } yield Credentials(
    "Sonatype Nexus Repository Manager",
    sonatypeCredentialHost.value,
    username,
    password
  )).toList
)

lazy val root: Project =
  project
    .in(file("."))
    .settings(commonSettings)
    .settings(
      publishArtifact := false,
      publish / skip := true
    )
    .aggregate(core.projectRefs: _*)

lazy val core = projectMatrix
  .in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "http4s-proxy",
    libraryDependencies ++= Seq(
      // %%% not %%: with %% the Scala.js rows resolved the JVM artifact, which compiles but
      // cannot link, so every published _sjs1 artifact up to 0.4.1 was unusable. There were no
      // tests to catch it.
      "org.http4s" %%% "http4s-core" % V.http4s,
      "org.http4s" %%% "http4s-client" % V.http4s,
      // kernel + std, never cats-effect core: this library is effect-polymorphic and must not
      // force the IO runtime on consumers.
      "org.typelevel" %%% "cats-effect-kernel" % V.catsEffect,
      "org.typelevel" %%% "cats-effect-std" % V.catsEffect,
      "co.fs2" %%% "fs2-core" % V.fs2
    )
  )
  // http4s-jdk-http-client is JVM only, so the integration tests that use it live in
  // src/test/scalajvm and their dependencies belong to the JVM rows alone. Those tests
  // stand up real servers and make timing assertions, so they neither fork-share a JVM
  // with the build nor run in parallel with each other.
  .jvmPlatform(
    scalaVersions,
    Seq(
      libraryDependencies ++= Seq(
        "ch.qos.logback" % "logback-classic" % V.logbackClassic % Test,
        "org.http4s" %% "http4s-dsl" % V.http4sTest % Test,
        "org.http4s" %% "http4s-ember-server" % V.http4sTest % Test,
        "org.http4s" %% "http4s-jdk-http-client" % V.http4sJdkHttpClient % Test
      ),
      Test / fork := true,
      Test / parallelExecution := false
    )
  )
  // Scala.js tests run under Node, which needs CommonJS modules.
  .jsPlatform(
    scalaVersions,
    Seq(Test / scalaJSLinkerConfig ~= (_.withModuleKind(ModuleKind.CommonJSModule)))
  )

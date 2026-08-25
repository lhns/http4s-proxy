lazy val scalaVersions = Seq("3.3.8", "2.13.18")

ThisBuild / scalaVersion := scalaVersions.head
ThisBuild / versionScheme := Some("early-semver")
ThisBuild / organization := "de.lhns"
name := (core.projectRefs.head / name).value

val V = new {
  // The compile-scope http4s version is the *floor* this library supports, deliberately kept low:
  // declaring a newer one would drag every consumer forward, and cats-effect / http4s are backward
  // but not forward binary compatible. Tests resolve newer versions through their own dependencies.
  val http4s = "0.23.27"
  val logbackClassic = "1.5.21"
  val munit = "1.2.4"
  val munitCatsEffect = "2.2.0"
}

lazy val commonSettings: SettingsDefinition = Def.settings(
  version := {
    val Tag = "refs/tags/v?([0-9]+(?:\\.[0-9]+)+(?:[+-].*)?)".r
    sys.env.get("CI_VERSION").collect { case Tag(tag) => tag }
      .getOrElse("0.0.1-SNAPSHOT")
  },

  licenses += ("Apache-2.0", url("https://www.apache.org/licenses/LICENSE-2.0")),

  homepage := scmInfo.value.map(_.browseUrl),
  scmInfo := Some(
    ScmInfo(
      url("https://github.com/lhns/http4s-proxy"),
      "scm:git@github.com:lhns/http4s-proxy.git"
    )
  ),
  developers := List(
    Developer(id = "lhns", name = "Pierre Kisters", email = "pierrekisters@gmail.com", url = url("https://github.com/lhns/"))
  ),

  libraryDependencies ++= Seq(
    "org.scalameta" %%% "munit" % V.munit % Test,
    "org.typelevel" %%% "munit-cats-effect" % V.munitCatsEffect % Test,
  ),

  testFrameworks += new TestFramework("munit.Framework"),

  Compile / doc / sources := Seq.empty,

  publishMavenStyle := true,

  publishTo := sonatypePublishToBundle.value,

  sonatypeCredentialHost := {
    if (sonatypeProfileName.value == "de.lolhens")
      "oss.sonatype.org"
    else
      "s01.oss.sonatype.org"
  },

  credentials ++= (for {
    username <- sys.env.get("SONATYPE_USERNAME")
    password <- sys.env.get("SONATYPE_PASSWORD")
  } yield Credentials(
    "Sonatype Nexus Repository Manager",
    sonatypeCredentialHost.value,
    username,
    password
  )).toList,

  pomExtra := {
    if (sonatypeProfileName.value == "de.lolhens")
      <distributionManagement>
        <relocation>
          <groupId>de.lhns</groupId>
        </relocation>
      </distributionManagement>
    else
      pomExtra.value
  }
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

lazy val core = projectMatrix.in(file("core"))
  .settings(commonSettings)
  .settings(
    name := "http4s-proxy",

    libraryDependencies ++= Seq(
      "org.http4s" %% "http4s-core" % V.http4s,
    ),
  )
  .jvmPlatform(scalaVersions)
  .jsPlatform(scalaVersions)

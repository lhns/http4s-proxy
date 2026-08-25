package de.lolhens.http4s.proxy

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.comcast.ip4s._
import munit.FunSuite
import org.http4s.dsl.io._
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.jdkhttpclient.JdkHttpClient
import org.http4s.{HttpApp, Uri}

import scala.concurrent.duration._

/** Canary for the JVM-only test row: proves that src/test/scalajvm is compiled, that the
  * JVM-only dependencies resolve, and that a real Ember server and JdkHttpClient can talk
  * to each other here. The Scala.js rows must never see this file.
  */
class EmberCanarySuite extends FunSuite {
  test("a real Ember server and JdkHttpClient round-trip on the JVM row") {
    val body = (for {
      server <- EmberServerBuilder
        .default[IO]
        .withHost(host"127.0.0.1")
        .withPort(port"0")
        .withHttpApp(HttpApp[IO] { _ => Ok("canary") })
        .withShutdownTimeout(1.second)
        .build
      client <- JdkHttpClient.simple[IO]
    } yield (server, client))
      .use { case (server, client) =>
        client.expect[String](Uri.unsafeFromString(s"http://127.0.0.1:${server.address.getPort}/"))
      }
      .unsafeRunSync()

    assertEquals(body, "canary")
  }
}

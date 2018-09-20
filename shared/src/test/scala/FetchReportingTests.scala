/*
 * Copyright 2016-2018 47 Degrees, LLC. <http://www.47deg.com>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package fetch

import scala.concurrent.{ExecutionContext, Future}

import org.scalatest.{AsyncFreeSpec, Matchers}

import fetch._

import cats.effect._
import cats.instances.list._
import cats.syntax.all._
import cats.temp.par._

class FetchReportingTests extends AsyncFreeSpec with Matchers {
  import TestHelper._

  override implicit val executionContext = ExecutionContext.Implicits.global
  implicit val timer: Timer[IO] = IO.timer(executionContext)
  implicit val cs: ContextShift[IO] = IO.contextShift(executionContext)

  "Plain values have no rounds of execution" in {
    def fetch[F[_] : ConcurrentEffect] =
      Fetch.pure[F, Int](42)

    val io = Fetch.runEnv[IO](fetch, InMemoryCache.empty)

    io.map({
      case (env, result) => env.rounds.size shouldEqual 0
    }).unsafeToFuture
  }

  "Single fetches are executed in one round" in {
    def fetch[F[_] : ConcurrentEffect] =
      one(1)

    val io = Fetch.runEnv[IO](fetch, InMemoryCache.empty)

    io.map({
      case (env, result) => env.rounds.size shouldEqual 1
    }).unsafeToFuture
  }

  "Single fetches are executed in one round per binding in a for comprehension" in {
    def fetch[F[_] : Concurrent] = for {
      o <- one(1)
      t <- one(2)
    } yield (o, t)

    val io = Fetch.runEnv[IO](fetch, InMemoryCache.empty)

    io.map({
      case (env, result) => env.rounds.size shouldEqual 2
    }).unsafeToFuture
  }

  "Single fetches for different data sources are executed in multiple rounds if they are in a for comprehension" in {
    def fetch[F[_] : Concurrent: Par] = for {
      o <- one(1)
      m <- many(3)
    } yield (o, m)

    val io = Fetch.runEnv[IO](fetch, InMemoryCache.empty)

    io.map({
      case (env, result) => env.rounds.size shouldEqual 2
    }).unsafeToFuture
  }

  "Single fetches combined with cartesian are run in one round" in {
    def fetch[F[_] : Concurrent : Par] =
      (one(1), many(3)).tupled

    val io = Fetch.runEnv[IO](fetch, InMemoryCache.empty)

    io.map({
      case (env, result) => env.rounds.size shouldEqual 1
    }).unsafeToFuture
  }

  "Single fetches combined with traverse are run in one round" in {
    def fetch[F[_] : Concurrent : Par] = for {
      manies <- many(3)                 // round 1
      ones   <- manies.traverse(one[F]) // round 2
    } yield ones

    val io = Fetch.runEnv[IO](fetch, InMemoryCache.empty)

    io.map({
      case (env, result) => env.rounds.size shouldEqual 2
    }).unsafeToFuture
  }

  "The product of two fetches from the same data source implies batching" in {
    def fetch[F[_] : Concurrent] =
      (one(1), one(3)).tupled

    val io = Fetch.runEnv[IO](fetch, InMemoryCache.empty)

    io.map({
      case (env, result) => env.rounds.size shouldEqual 1
    }).unsafeToFuture
  }

  "The product of concurrent fetches of the same type implies everything fetched in batches" in {
    def aFetch[F[_] : Concurrent] = for {
      a <- one(1)  // round 1
      b <- one(2)  // round 2
      c <- one(3)
    } yield c

    def anotherFetch[F[_] : Concurrent : Par] = for {
      a <- one(2)  // round 1
      m <- many(4) // round 2
      c <- one(3)
    } yield c

    def fetch[F[_] : Concurrent : Par] =
      ((aFetch, anotherFetch).tupled, one(3)).tupled

    val io = Fetch.runEnv[IO](fetch, InMemoryCache.empty)

    io.map({
      case (env, result) => env.rounds.size shouldEqual 2
    }).unsafeToFuture
  }
}

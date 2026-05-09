package simulations

import io.gatling.core.Predef._
import io.gatling.http.Predef._
import scala.concurrent.duration._

class RateLimiterSimulation extends Simulation {

  val httpProtocol = http
    .baseUrl("http://localhost:8080")
    .acceptHeader("application/json")
    .contentTypeHeader("application/json")

  // ── Scenario 1 — Baseline ─────────────────────────────────────
  // 10 users, 1 request every 2 seconds each
  // Expected: all pass, no blocking
  val baselineScenario = scenario("Baseline - Normal Traffic")
    .repeat(5) {
      exec(
        http("GET /api/v1/users")
          .get("/api/v1/users")
          .header("x-api-key", "baseline-client")
          .check(status.in(200, 429, 502, 503))
      ).pause(2.seconds)
    }

  // ── Scenario 2 — Burst Attack ─────────────────────────────────
  // 100 users fire simultaneously with no pause
  // Expected: ~70 pass (token bucket capacity), rest get 429
  val burstScenario = scenario("Burst Attack")
    .repeat(3) {
      exec(
        http("Burst Request")
          .get("/api/v1/users")
          .header("x-api-key", "burst-attacker")
          // Mark 429 as failed so we can see it in KO column
          .check(status.not(429))
      ).pause(100.milliseconds)
    }

  // ── Scenario 3 — Sustained Load ──────────────────────────────
  // 30 users, each fires 15 requests with short pause
  // Expected: risk scorer detects burst, throttles client
  val sustainedScenario = scenario("Sustained Load")
    .repeat(15) {
      exec(
        http("Sustained Request")
          .get("/api/v1/users")
          .header("x-api-key", "sustained-client")
          .check(status.in(200, 429, 502, 503))
      ).pause(200.milliseconds)
    }

  // ── Scenario 4 — Multi-client Fairness ───────────────────────
  // 5 different clients, each fires 10 requests
  // Expected: each gets own bucket, one doesn't affect others
  val apiKeyFeeder = Array(
    Map("apiKey" -> "client-alpha"),
    Map("apiKey" -> "client-beta"),
    Map("apiKey" -> "client-gamma"),
    Map("apiKey" -> "client-delta"),
    Map("apiKey" -> "client-epsilon")
  ).circular

  val fairnessScenario = scenario("Multi-client Fairness")
    .feed(apiKeyFeeder)
    .repeat(10) {
      exec(
        http("Fairness Request - #{apiKey}")
          .get("/api/v1/users")
          .header("x-api-key", "#{apiKey}")
          .check(status.in(200, 429, 502, 503))
      ).pause(1.second)
    }

  setUp(

    // Scenario 1: 10 users, normal pace
    baselineScenario.inject(
      rampUsers(10).during(10.seconds)
    ).protocols(httpProtocol),

    // Scenario 2: 100 users at once — burst
    burstScenario.inject(
      nothingFor(15.seconds),
      atOnceUsers(100)
    ).protocols(httpProtocol),

    // Scenario 3: 30 users, ramp over 10 seconds
    sustainedScenario.inject(
      nothingFor(30.seconds),
      rampUsers(30).during(10.seconds)
    ).protocols(httpProtocol),

    // Scenario 4: 5 clients, fairness test
    fairnessScenario.inject(
      nothingFor(80.seconds),
      rampUsers(5).during(5.seconds)
    ).protocols(httpProtocol)

  ).assertions(
    // p99 response time under 500ms
    global.responseTime.percentile(99).lt(500),
    // at least 1% of requests succeed
    global.successfulRequests.percent.gt(1)
  )
}
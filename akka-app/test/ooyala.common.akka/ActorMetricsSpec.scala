package ooyala.common.akka

import org.scalatest.matchers.ShouldMatchers
import org.scalatest.FunSpec
import akka.testkit.TestActorRef

import akka.actor.{Actor, ActorSystem}


class ActorMetricsSpec extends FunSpec with ShouldMatchers {
  implicit val system = ActorSystem("test")

  describe("actor metrics") {
    it("should increment receive count metric when a message is received") {
      val actorRef = TestActorRef(new DummyActor with ActorMetrics)
      val actor = actorRef.underlyingActor

      actorRef ! "me"
      actor.metricReceiveTimer.getCount should equal (1)
    }
  }
}
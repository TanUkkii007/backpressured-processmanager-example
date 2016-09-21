package tanukkii.backpressured.processmanager.example

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.WordSpecLike
import scala.concurrent.duration._

class BackpressuredProcessManagerTest extends TestKit(ActorSystem("BackpressuredProcessManagerTest"))
  with WordSpecLike with ImplicitSender {
  import system.dispatcher

  "BackpressuredProcessManager" must {
    "execute long running process up to the configured number concurrently" in {
      val pm = system.actorOf(BackpressuredProcessManager.props(50, 8))
      1 to 1000 foreach { i =>
        system.scheduler.scheduleOnce(20 * i millis, pm, ProcessManager.ProcessCommand(CommandRequestId.random, "test"))
      }
      receiveN(1000, 30 seconds)
    }
  }
}

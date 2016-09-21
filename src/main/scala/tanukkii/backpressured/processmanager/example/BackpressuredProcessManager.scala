package tanukkii.backpressured.processmanager.example

import akka.actor._
import akka.stream.ActorMaterializer
import akka.stream.actor.{MaxInFlightRequestStrategy, ActorSubscriber, ActorPublisher}
import akka.stream.scaladsl.{Source, Sink}
import tanukkii.backpressured.processmanager.example.BackpressuredProcessManager.ProcessCommandEnvelope
import tanukkii.backpressured.processmanager.example.ProcessManager.ProcessCommand
import scala.annotation.tailrec

class BackpressuredProcessManager(maxConcurrentProcesses: Int, maxBufferSize: Int)
  extends ActorPublisher[ProcessCommandEnvelope] with ActorSubscriber with ActorLogging {
  import BackpressuredProcessManager._
  import akka.stream.actor.ActorSubscriberMessage._
  import akka.stream.actor.ActorPublisherMessage._

  var queue: Vector[ProcessCommandEnvelope] = Vector()
  var processing: Set[CommandRequestId] = Set()


  override def supervisorStrategy: SupervisorStrategy = SupervisorStrategy.stoppingStrategy

  override val requestStrategy = new MaxInFlightRequestStrategy(max = maxConcurrentProcesses) {
    override def inFlightInternally: Int = queue.size + processing.size
    override def batchSize: Int = 5
  }

  implicit val materializer = ActorMaterializer()

  Source.fromPublisher(ActorPublisher(self))
    .runWith(Sink.fromSubscriber(ActorSubscriber[ProcessCommandEnvelope](self)))

  def receive: Receive = {
    // Source side
    case Request(_) => deliverBuf()
    case Cancel => context.stop(self)
    case cmd: ProcessManager.ProcessCommand if queue.size >= maxBufferSize =>
      log.error("Bufferoverflow failure. Current buffer size {}", maxBufferSize)
      sender() ! ProcessManagerBufferOverflowFailure(cmd)
    case cmd: ProcessManager.ProcessCommand =>
      val processMsg = ProcessCommandEnvelope(cmd, sender())
      if (totalDemand == 0) {
        queue = queue :+ processMsg
      } else {
        queue = queue :+ processMsg
        deliverBuf()
      }
    // Sink side
    case OnNext(processMsg @ ProcessCommandEnvelope(msg, replyTo)) =>
      context.watch(context.actorOf(ProcessManager.props, msg.id.value)).tell(msg, replyTo)
    case OnError(e) => log.error(e, "BackpressuredProcessManager stopped with error.")
    case OnComplete =>
      log.debug("BackpressuredProcessManager completed.")
      self ! PoisonPill
    case Terminated(pm) =>
      val commandRequestId = CommandRequestId(pm.path.name)
      processing -= commandRequestId
  }

  @tailrec final def deliverBuf(): Unit = {
    log.debug("queue: {}, processing: {}", queue.length, processing.size)
    if (totalDemand > 0) {
      if (totalDemand <= Int.MaxValue) {
        val (use, keep) = queue.splitAt(totalDemand.toInt)
        queue = keep
        processing = processing ++ use.map(v => v.cmd.id)
        use.foreach(v => onNext(v))
      } else {
        val (use, keep) = queue.splitAt(Int.MaxValue)
        queue = keep
        processing = processing ++ use.map(v => v.cmd.id)
        use.foreach(v => onNext(v))
        deliverBuf()
      }
    }
  }
}

object BackpressuredProcessManager {
  case class ProcessCommandEnvelope(cmd: ProcessCommand, replyTo: ActorRef)
  case class ProcessManagerBufferOverflowFailure(command: ProcessCommand)

  def props(maxConcurrentProcesses: Int, maxBufferSize: Int) = Props(new BackpressuredProcessManager(maxConcurrentProcesses, maxBufferSize))
}
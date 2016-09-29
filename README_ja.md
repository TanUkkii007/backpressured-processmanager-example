# プロセスマネジャーにバックプレッシャーを適用する

# プロセスマネジャーとは

プロセスマネジャーは長期にわたる処理（プロセス）の動的で中央集権的な管理する実装パターンです。
プロセスマネジャーは多数の処理ステップから構成されます。処理の進行を管理するため、状態機械として表現することができます。

プロセスマネジャーの実装方法は主に２つあり（RMP Ch. 7）、再利用可能なDSLで表現する方法と、ドメインに特化した単一の処理プロセスを束ねる方法です。
ここでは後者の方法で実装されることを想定します。RMPでも紹介されているようにプロセスマネジャーをアクターで実装します。

アクターを使うとアクター自体が状態機械ということもあり、プロセスマネジャーを比較的楽に実装することができます。
AkkaではFSMやPersistent FSMを使うとよいでしょう。

## 問題

プロセスマネジャーをアクターで実装する際の問題として、ユーザーからのリクエストに公開したときなど、
要求がダイナミックに変動する元ではシステム中に処理中のプロセスマネージャーが溢れてしまうことです。
プロセスマネジャーは処理の完了に時間がかかるので、要求が多いと未完了のプロセスが増え続けてしまいます。
この現象は特に応答を非同期に返した場合に深刻になります。つまり処理完了時ではなく、処理受付時に受け付けたことを示すレスポンスを返す場合です。
アクターのメッセージパッシングはPush型のモデルで、かつカプセル化により相手の状態が分からないため、
アクターで実装されたプロセスマネージャーはこの現象に陥りやすいのです。

## 解決策

アクターの状態機械を活かしつつ、この問題を解決するにはアクターにバックプレッシャーをもたらす必要があります。

バックプレッシャーはプロセスマネジャーの作成に対して適用する必要があります。
アクターの作成は軽い処理なので、BoundedMailboxを使ってメールボックスのサイズを制限してもあまり効果はありません。

アクターにバックプレッシャーをもたらすためには、ストリームを内部で使います。
アクターに入ってくるメッセージは必ず内部のストリームを通るようにします。
同時に処理できる処理量を超えるメッセージは一時的にバッファーに滞留します。
ストリームは同時に処理しているプロセスマネジャーの数とバッファーサイズによってバックプレッシャーをかけます。
同時に処理しているプロセスマネジャーの数が多くなればなるほど、ストリームが新しくメッセージを処理する量は減っていき、
バッファーの最大値に達すると新たなメッセージの受付を拒否します。

```
  /**
   *     +----------------------------+
   *     |            Actor           |
   *     |      +--------------+      |
   * message ~> |    buffer    |-+ back-pressured
   *     |      +-|------------+ | by buffer size
   *     |  +-----|+        +----|-+  |
   *     |  |     + ~message~>     |  | create process managers
   *     |  |source|        | sink |  | and let them comsume messages in sink
   *     |  |      <~request~      |  |
   *     |  +------+        +------+  |
   *     +----------------------------+
   */
```

実装にはAkka streamを使います。Akka streamはAkka actorと連携することが容易だからです。
ActorPublisherとActorSubscriberを使えば、アクターをストリームのsourceとsinkにすることができます。
sourceは１つの出力のみをもつストリームで、sinkは１つの入力のみをもつストリームです。
ここではActorPublisherとActorSubscriberの両方を使い、アクターをsource兼sinkにします。
そしてsourceとsinkを結合してストリームを動作させます。

実装は[ここ](https://github.com/TanUkkii007/backpressured-processmanager-example/blob/master/src/main/scala/tanukkii/backpressured/processmanager/example/BackpressuredProcessManager.scala)を見てください。
指定した数以上のプロセスマネジャーを処理しないようバックプレッシャーがかかっていることを確認したい場合はテストを実行してみてください。

```scala
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
```

このパターンを実装するときに注意すべきことは、プロセスマネジャーが完了（失敗で終わったときも含む）したときに必ずバッファーから処理中のメッセージを取り除くことです。
さもないとバッファーがいっぱいになりバックプレッシャーによって処理がとまります。
これを確実に成し遂げるには、バックプレッシャーをかけるアクターとプロセスマネジャーの間に親子関係をもたせることです。
バックプレッシャーをかけるアクターはプロセスマネジャーのスーパーバイザーになり、たとえプロセスマネジャーがクラッシュしても完了を補足できます。
実装ではwatchを使ってバッファーから完了したメッセージを取り除いていますが、スーパーバイザーストラテジーの中でそれを行っても良いし、
RequestStrategyをcontext.children.lengthをもとに計算してもいいでしょう。

メッセージはストリームを通るので、ストリームの多様なステージの恩恵も受けられます。
例えば処理スピードをコントロールしたければ、throttleステージを使うなどができます。

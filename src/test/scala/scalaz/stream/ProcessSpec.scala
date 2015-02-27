package scalaz.stream

import org.scalacheck.Prop._

import Cause._
import scalaz._
import cats.Monoid
import cats.syntax.eq._
import cats.std.all._

import org.scalacheck.{Gen, Arbitrary, Properties}
import scalaz.concurrent.{Task, Strategy}
import Util._
import process1._
import Process._
import TestInstances._
import scala.concurrent.duration._
import scala.concurrent.SyncVar

object ProcessSpec extends Properties("Process") {

  case object FailWhale extends RuntimeException("the system... is down")

  implicit val S = Strategy.DefaultStrategy
  implicit val scheduler = scalaz.stream.DefaultScheduler

  // Subtyping of various Process types:
  // * Process1 is a Tee that only read from the left (Process1[I,O] <: Tee[I,Any,O])
  // * Tee is a Wye that never requests Both (Tee[I,I2,O] <: Wye[I,I2,O])
  // This 'test' is just ensuring that this typechecks
    object Subtyping {
      def asTee[I,O](p1: Process1[I,O]): Tee[I,Any,O] = p1
      def asWye[I,I2,O](t: Tee[I,I2,O]): Wye[I,I2,O] = t
    }

  property("basic") = forAll { (p: Process0[Int], p2: Process0[String], n: Int) =>
    val f = (x: Int) => List.range(1, x.min(100))
    val g = (x: Int) => x % 7 == 0
    val pf: PartialFunction[Int, Int] = {case x: Int if x % 2 == 0 => x }

    val sm = Monoid[String]
//
//   println("##########"*10 + p)
//   println("P1 " + p.toList.map(_ + 1) )
//  println("P2 " +  p.pipe(lift(_ + 1)).toList )
//    println("====" +  (p.toList.map(_ + 1) === p.pipe(lift(_ + 1)).toList) )
  try {
    val examples = Seq(
        "map" |:  (p.toList.map(_ + 1) === p.map(_ + 1).toList)
       , "map-pipe" |: (p.toList.map(_ + 1) === p.pipe(lift(_ + 1)).toList)
       , "flatMap" |:   (p.toList.flatMap(f) === p.flatMap(f andThen Process.emitAll).toList)
    )

    examples.reduce(_ && _)
  } catch {
    case t : Throwable => t.printStackTrace(); throw t
  }



  }





  property("awakeEvery") = secure {
    Process.awakeEvery(100 millis).map(_.toMillis/100).take(5).runLog.run == Vector(1,2,3,4,5)
  }


  property("sinked") = secure {
    val p1 = Process.constant(1).toSource
    val pch = Process.constant((i:Int) => Task.now(())).take(3)

    p1.to(pch).runLog.run.size == 3
  }

  property("duration") =  {
    val firstValueDiscrepancy = duration.take(1).runLast.run.get
    val reasonableError = 200 * 1000000 // 200 millis
    (firstValueDiscrepancy.toNanos < reasonableError) :| "duration is near zero at first access"
  }


  import scala.concurrent.duration._
  val smallDelay = Gen.choose(10, 300) map {_.millis}


  property("every") =
    forAll(smallDelay) { delay: Duration =>
      type BD = (Boolean, Duration)
      val durationSinceLastTrue: Process1[BD, BD] = {
        def go(lastTrue: Duration): Process1[BD,BD] = {
          await1 flatMap { pair:(Boolean, Duration) => pair match {
            case (true , d) => emit((true , d - lastTrue)) fby go(d)
            case (false, d) => emit((false, d - lastTrue)) fby go(lastTrue)
          } }
        }
        go(0.seconds)
      }

      val draws = (600.millis / delay) min 10 // don't take forever

      val durationsSinceSpike = every(delay).
                                tee(duration)(tee zipWith {(a,b) => (a,b)}).
                                take(draws.toInt) |>
        durationSinceLastTrue

      val result = durationsSinceSpike.runLog.run.toList
      val (head :: tail) = result

      head._1 :| "every always emits true first" &&
        tail.filter   (_._1).map(_._2).forall { _ >= delay } :| "true means the delay has passed" &&
        tail.filterNot(_._1).map(_._2).forall { _ <= delay } :| "false means the delay has not passed"
    }

  property("fill") = forAll(Gen.choose(0,30) flatMap (i => Gen.choose(0,50) map ((i,_)))) {
    case (n,chunkSize) =>
      Process.fill(n)(42, chunkSize).toList == List.fill(n)(42)
  }

  property("forwardFill") = secure {
    import scala.concurrent.duration._
    val t2 = Process.awakeEvery(2 seconds).forwardFill.zip {
      Process.awakeEvery(100 milliseconds).take(100)
    }.run.timed(15000).run
    true
  }

  property("iterate") = secure {
    Process.iterate(0)(_ + 1).take(100).toList == List.iterate(0, 100)(_ + 1)
  }

  property("iterateEval") = secure {
    Process.iterateEval(0)(i => Task.delay(i + 1)).take(100).runLog.run == List.iterate(0, 100)(_ + 1)
  }

  property("kill executes cleanup") = secure {
    import TestUtil._
    val cleanup = new SyncVar[Int]
    val p: Process[Task, Int] = halt onComplete(eval_(Task.delay { cleanup.put(1) }))
    p.kill.expectedCause(_ == Kill).run.run
    cleanup.get(500).get == 1
  }

  property("kill") = secure {
    import TestUtil._
    ("repeated-emit" |: emit(1).repeat.kill.expectedCause(_ == Kill).toList == List())
  }

  property("kill ++") = secure {
    import TestUtil._
    var afterEmit = false
    var afterHalt = false
    var afterAwait = false
    def rightSide(a: => Unit): Process[Task, Int] = Process.awaitOr(Task.delay(a))(_ => rightSide(a))(_ => halt)
    (emit(1) ++ rightSide(afterEmit = true)).kill.expectedCause(_ == Kill).run.run
    (halt ++ rightSide(afterHalt = true)).kill.expectedCause(_ == Kill).run.run
    (eval_(Task.now(1)) ++ rightSide(afterAwait = true)).kill.expectedCause(_ == Kill).run.run
    ("after emit" |: !afterEmit) &&
      ("after halt" |: !afterHalt) &&
      ("after await" |: !afterAwait)
  }

  property("cleanup isn't interrupted in the middle") = secure {
    // Process p is killed in the middle of `cleanup` and we expect:
    // - cleanup is not interrupted
    var cleaned = false
    val cleanup = eval(Task.delay{ 1 }) ++ eval_(Task.delay(cleaned = true))
    val p = (halt onComplete cleanup)
    val res = p.take(1).runLog.run.toList
    ("result" |: res == List(1)) &&
      ("cleaned" |: cleaned)
  }

  property("cleanup propagates Kill") = secure {
    // Process p is killed in the middle of `cleanup` and we expect:
    // - cleanup is not interrupted
    // - Kill is propagated to `++`
    var cleaned = false
    var called = false
    val cleanup = emit(1) ++ eval_(Task.delay(cleaned = true))
    val p = (emit(0) onComplete cleanup) ++ eval_(Task.delay(called = true))
    val res = p.take(2).runLog.run.toList
    ("res" |: res == List(0, 1)) &&
      ("cleaned" |: cleaned) &&
      ("called" |: !called)
  }

  property("asFinalizer") = secure {
    import TestUtil._
    var called = false
    (emit(1) ++ eval_(Task.delay{ called = true })).asFinalizer.kill.expectedCause(_ == Kill).run.run
    called
  }

  property("asFinalizer, pipe") = secure {
    var cleaned = false
    val cleanup = emit(1) ++ eval_(Task.delay(cleaned = true))
    cleanup.asFinalizer.take(1).run.run
    cleaned
  }

  property("pipe can emit when predecessor stops") = secure {
    import TestUtil._
    val p1 = process1.id[Int].onComplete(emit(2) ++ emit(3))
    ("normal termination" |: (emit(1) |> p1).toList == List(1, 2, 3)) &&
      ("kill" |: ((emit(1) ++ Halt(Kill)) |> p1).expectedCause(_ == Kill).toList == List(1, 2, 3)) &&
      ("failure" |: ((emit(1) ++ fail(FailWhale)) |> p1).expectedCause(_ == Error(FailWhale)).toList == List(1, 2, 3))
  }

  property("feed1, disconnect") = secure {
    val p1 = process1.id[Int].onComplete(emit(2) ++ emit(3))
    p1.feed1(5).feed1(4).disconnect(Kill).unemit._1 == Seq(5, 4, 2, 3)
  }

  property("pipeIn") = secure {
    val q = async.boundedQueue[String]()
    val sink = q.enqueue.pipeIn(process1.lift[Int,String](_.toString))

    (Process.range(0,10).liftIO to sink).run.run
    val res = q.dequeue.take(10).runLog.timed(3000).run.toList
    q.close.run

    res === (0 until 10).map(_.toString).toList
  }

  // Single instance of original sink is used for all elements.
  property("pipeIn uses original sink once") = secure {
    // Sink starts by wiping `written`.
    var written = List[Int]()
    def acquire: Task[Unit] = Task.delay { written = Nil }
    def release(res: Unit): Task[Unit] = Task.now(())
    def step(res: Unit): Task[Int => Task[Unit]] = Task.now((i: Int) => Task.delay { written = written :+ i  })
    val sink = io.resource(acquire)(release)(step)

    val source = Process(1, 2, 3).toSource

    val transformer = process1.lift((i: Int) => i + 1)
    source.to(sink.pipeIn(transformer)).run.run

    written == List(2, 3, 4)
  }


  property("range") = secure {
    Process.range(0, 100).toList == List.range(0, 100) &&
      Process.range(0, 1).toList == List.range(0, 1) &&
      Process.range(0, 0).toList == List.range(0, 0)
  }

  property("ranges") = forAll(Gen.choose(1, 101)) { size =>
    Process.ranges(0, 100, size).liftIO.flatMap { case (i,j) => emitAll(i until j) }.runLog.run ==
      IndexedSeq.range(0, 100)
  }

  property("unfold") = secure {
    Process.unfold((0, 1)) {
      case (f1, f2) => if (f1 <= 13) Some(((f1, f2), (f2, f1 + f2))) else None
    }.map(_._1).toList == List(0, 1, 1, 2, 3, 5, 8, 13)
  }

  property("unfoldEval") = secure {
    unfoldEval(10)(s => Task.now(if (s > 0) Some((s, s - 1)) else None))
      .runLog.run.toList == List.range(10, 0, -1)
  }

  property("kill of drained process terminates") = secure {
    val effect: Process[Task,Unit] = Process.repeatEval(Task.delay(())).drain
    effect.kill.runLog.timed(1000).run.isEmpty
  }

  // `p.suspendStep` propagates `Kill` to `p`
  property("suspendStep propagates Kill") = secure {
    var fallbackCausedBy: Option[EarlyCause] = None
    var received: Option[Int] = None
    val p = awaitOr(Task.delay(1))({early => fallbackCausedBy = Some(early); halt })({a => received = Some(a); halt })
    p.suspendStep.flatMap {
      case Step(p, cont) => (p +: cont).suspendStep.flatMap {
        case Step(p, cont) => p +: cont
        case hlt@Halt(_) => hlt
      }
      case hlt@Halt(_) => hlt
    }.injectCause(Kill).runLog.run // `injectCause(Kill)` is like `kill` but without `drain`.
    fallbackCausedBy == Some(Kill) && received.isEmpty
  }

  property("pipeO stripW ~= stripW pipe") = forAll { (p1: Process1[Int,Int]) =>
    val p = logged(range(1, 11).toSource)
    p.pipeO(p1).stripW.runLog.run == p.stripW.pipe(p1).runLog.run
  }

  property("pipeW stripO ~= stripO pipe") = forAll { (p1: Process1[Int,Int]) =>
    val p = logged(range(1, 11).toSource)
    p.pipeW(p1).stripO.runLog.run == p.stripO.pipe(p1).runLog.run
  }

  property("process.sequence returns elements in order") = secure {
    val random = util.Random
    val p = Process.range(1, 10).map(i => Task.delay { Thread.sleep(random.nextInt(100)); i })

    p.sequence(4).runLog.run == p.flatMap(eval).runLog.run
  }

  property("runAsync cleanup") = secure {

    val q = async.boundedQueue[Int]()
    val q2 = async.boundedQueue[Int]()

    @volatile var cleanupCalled = false
    val sync = new SyncVar[Cause \/ (Seq[Int], Process.Cont[Task,Int])]
    val deque = q.dequeue.onComplete(eval_(Task.delay{cleanupCalled = true}))
    val interrupt =
      (deque observe q2.enqueue).runAsync(sync.put)

    Thread.sleep(100)
    interrupt(Kill)

    sync.get(3000).isDefined && cleanupCalled

  }

}

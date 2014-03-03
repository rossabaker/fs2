package scalaz.stream

import scala.annotation.tailrec
import scalaz.\/._
import scalaz._
import scalaz.concurrent.Task
import scalaz.stream.Process.Env

/**
 * Created by pach on 01/03/14.
 */

sealed trait Process2[+F[_], +O] {

  import Process2._
  import Util._

  /** Ignore all outputs of this `Process`. */
  final def drain: Process2[F, Nothing] = {
    this match {
      case h@Halt(_)       => h
      case aw@Await(_, _)  => aw.extend(_.drain)
      case ap@Append(p, n) => ap.extend(_.drain)
      case Emit(h)         => halt
    }
  }

  /** Send the `Kill` signal to the next `Await`, then ignore all outputs. */
  final def kill: Process2[F, Nothing] = this.disconnect.drain

  /** Causes subsequent await to fail with the `Kill` exception. */
  final def disconnect: Process2[F, O] = {
    this match {
      case h@Halt(_)       => h
      case Await(_, rcv)   => suspend { rcv(left(Kill)).run }
      case ap@Append(p, n) => ap.extend(_.disconnect)
      case Emit(h)         => this
    }
  }


  /**
   * Replace the `Halt` at the end of this `Process` with whatever
   * is produced by `f`.
   */
  final def onHalt[F2[x] >: F[x], O2 >: O](f: Throwable => Process2[F2, O2]): Process2[F2, O2] = this match {
    case Append(p, n) => Append(p, n :+ f.asInstanceOf[Throwable => Process2[F, O]]).compact
    case _            => Append(this, Vector(f)).compact
  }


  /**
   * If this process halts without an error, attaches `p2` as the next step.
   *
   * Please note that even when p2 is passed lazily,
   * it may be evaluated _before_ it is actually needed for performance reasons
   * `p2` is lazy here due to stack-safety.
   *
   */
  final def append[F2[x] >: F[x], O2 >: O](p2: => Process2[F2, O2]): Process2[F2, O2] = {
    onHalt {
      case End | Kill => p2
      case rsn        => fail(rsn)
    }

  }


  final def ++[F2[x] >: F[x], O2 >: O](p2: => Process2[F2, O2]): Process2[F2, O2] = append(p2)

  /**
   * If process is Append, compacts append in such a way, that head (p) is never Append.
   * @return
   */
  final def compact: Process2[F, O] =
    this match {
      case Append(p, n) =>
        @tailrec
        def go(p1: Process2[F, O], n1: Vector[Throwable => Process2[F, O]]): Process2[F, O] = {
          p1 match {
            case Append(p0, n0) => go(p0, n0 fast_++ n1)
            case _              => Append(p1, n1)
          }
        }
        go(p, n)

      case _ => this
    }


  final def onComplete[F2[x] >: F[x], O2 >: O](p2: => Process2[F2, O2]): Process2[F2, O2] =
    onHalt {
      case (End | Kill) => p2
      case e            => p2.onHalt {
        case (End | Kill) => fail(e)
        case e2           => fail(Process.CausedBy(e2, e))
      }
    }

  final def onFailure[F2[x] >: F[x], O2 >: O](p2: => Process2[F2, O2]): Process2[F2, O2] =
    onHalt {
      case e@(End | Kill) => fail(e)
      case e              => p2.onHalt {
        case (End | Kill) => fail(e)
        case e2           => fail(Process.CausedBy(e2, e))
      }
    }


  final def flatMap[F2[x] >: F[x], O2](f: O => Process2[F2, O2]): Process2[F2, O2] = {
    this match {
      case h@Halt(_)              => h
      case aw@Await(_, _)         => aw.extend(_ flatMap f)
      case ap@Append(p, n)        => ap.extend(_ flatMap f)
      case Emit(os) if os.isEmpty => halt
      case Emit(os)               => os.tail.foldLeft(f(os.head))(_ append f(_))
    }
  }


  final def map[O2](f: O => O2): Process2[F, O2] =
    flatMap { o => emit(f(o)) }

  final def pipe[O2](p2: Process21[O, O2]): Process2[F, O2] = {

    def go(
      cur: Process2[F, O]
      , cur1: Process21[O, O2]
      , stack: Vector[Throwable => Process2[F, O]]
      , stack1: Vector[Throwable => Process21[O, O2]]): Process2[F, O2] = {

      cur1 match {
        case Halt(rsn) if stack1.isEmpty => Append(cur, stack).kill onComplete fail(rsn)
        case Halt(rsn)                   => go(cur, Try(stack1.head(rsn)), stack, stack1.tail)
        case emit@Emit(_)                => emit append go(cur, halt, stack, stack1)
        case Append(p, n)                => go(cur, p, stack, n fast_++ stack1)
        case aw@Await21(rcv1)            => this match {
          case hlt@Halt(rsn) if stack.isEmpty => go(halt, Try(rcv1(left(rsn))), stack, stack1)
          case hlt@Halt(rsn)                  => go(Try(stack.head(rsn)), cur1, stack.tail, stack1)
          case Emit(h) if h.isEmpty           => go(halt, Try(rcv1(left(End))), stack, stack1)
          case Emit(h)                        => go(Emit(h.tail), Try(rcv1(right(h.head))), stack, stack1)
          case aw@Await(req, rcv)             => aw.extend(go(_, cur1, stack, stack1))
          case ap@Append(p, n)                => go(p, cur1, n fast_++ stack, stack1)
        }
      }

    }

    go(this.compact, p2.compact, Vector(), Vector())

  }

  final def tee[F2[x] >: F[x], O2, O3](p2: Process2[F2, O2])(t: Tee[O, O2, O3]): Process2[F2, O3] = {
    import scalaz.stream.Process.{Emit => EmitP, Halt => HaltP}
    import scalaz.stream.tee.{AwaitL, AwaitR}
    t match {
      case HaltP(e)            => this.kill onComplete p2.kill onComplete fail(e)
      case EmitP(h, tl)        => emitAll(h) ++ this.tee(p2)(tl)
      case AwaitL(recv, fb, c) => this match {
        case Halt(End) => halt.tee(p2)(fb.disconnect)
        case Halt(e)   => fail(e).tee(p2)(c)
        case Emit(h)   =>
          ???
        //          suspend {
        //          if (h.nonEmpty) (emitAll(h.tail) ++ tl).tee(p2)(recv(h.head))
        //          else tl.tee(p2)(t)
        //        }
        case a@Await(_, _) => a.extend(_.tee(p2)(t))
        case Append(p, n)  => ???
      }
      case AwaitR(recv, fb, c) => p2 match {
        case Halt(End) => this.tee(halt)(fb)
        case Halt(e)   => this.tee(fail(e))(c)
        // casts required since Scala seems to discard type of `p2` when
        // pattern matching on it - assigns `F2` and `O2` to `Any` in patterns
        case e@Emit(_) => ???
        //          e.asInstanceOf[Emit[F2, O2]].extend {
        //          (h: Seq[O2], tl: Process2[F2, O2]) =>
        //            if (h.nonEmpty) this.tee(emitAll(h.tail) ++ tl)(recv(h.head))
        //            else this.tee(tl)(t)
        //        }
        case a@Await(_, _) => a.asInstanceOf[Await[F2, Any, O2]].extend(
          p2 => this.tee(p2)(t))
        case Append(p, n)  => ???
      }
    }
  }

  final def runFoldMap[F2[x] >: F[x], B](f: O => B)(implicit F: Monad[F2], C: Catchable[F2], B: Monoid[B]): F2[B] = {
    import Util._

    debug("####" * 100)
    def go(cur: Process2[F2, O], acc: B, stack: Seq[Throwable => Process2[F2, O]]): F2[B] = {
      debug(s" cur: $cur, acc: $acc, stack: ${stack.size }")
      cur match {
        case Emit(h) if stack.isEmpty   =>
          F.point(h.foldLeft(acc)((x, y) => B.append(x, f(y))))
        case Emit(h)                    =>
          go(Try(stack.head(End)), h.asInstanceOf[Seq[O]].foldLeft(acc)((x, y) => B.append(x, f(y))), stack.tail)
        case Halt(e) if (stack.isEmpty) =>
          e match {
            case (End | Kill) => F.point(acc)
            case _            => C.fail(e)
          }
        case Halt(e)                    => go(stack.head(e), acc, stack.tail)
        case Append(p, n)               => go(p.asInstanceOf[Process2[F2, O]], acc, n.asInstanceOf[Seq[Throwable => Process2[F2, O]]] fast_++ stack)
        case Await(req, rcv)            =>
          F.bind(C.attempt(req.asInstanceOf[F2[AnyRef]])) {
            e =>
              rcv(e).attemptRun match {
                case -\/(e) => go(Halt(e), acc, stack)
                case \/-(p) => go(p.asInstanceOf[Process2[F2, O]], acc, stack)
              }

          }
      }
    }
    go(this, B.zero, Vector())

  }


  /**
   * Collect the outputs of this `Process[F,O]`, given a `Monad[F]` in
   * which we can catch exceptions. This function is not tail recursive and
   * relies on the `Monad[F]` to ensure stack safety.
   */
  final def runLog[F2[x] >: F[x], O2 >: O](implicit F: Monad[F2], C: Catchable[F2]): F2[IndexedSeq[O2]] = {
    runFoldMap[F2, IndexedSeq[O2]](IndexedSeq(_))(
      F, C,
      // workaround for performance bug in Vector ++
      Monoid.instance[IndexedSeq[O2]](
        (a, b) => b.foldLeft(a)(_ :+ _),
        IndexedSeq.empty)
    )
  }
}


object Process2 {

  // We are just using `Task` for its exception-catching and trampolining,
  // just defining local alias to avoid confusion
  type Trampoline[+A] = Task[A]
  val Trampoline = Task

  /**
   * A single input stream transducer. Accepts input of type `I`,
   * and emits values of type `O`.
   */
  type Process21[-I, +O] = Process2[Env[I, Any]#Is, O]

  /**
   * Represents state of process, that was terminated
   * If the e eq [[scalaz.stream.Process.End]] that indicates that process terminated due to the fact that elements
   * of the process were consumed (normal termination)
   * @param e
   */
  case class Halt(e: Throwable) extends Process2[Nothing, Nothing]

  /**
   * Represents state of process, where process awaits deferred computation of `A`.
   * After receiving the `A` recv function is applied to create next state of process.
   * recv function potentially contains as well any cleanup code, that may need to be run.
   */
  case class Await[+F[_], A, +O](
    req: F[A]
    , rcv: (Throwable \/ A) => Trampoline[Process2[F, O]]
    ) extends Process2[F, O] {

    /**
     * Helper to modify the result of `rcv` parameter of await stack-safely on trampoline.
     */
    def extend[F2[x] >: F[x], O2](f: Process2[F, O] => Process2[F2, O2]): Process2[F2, O2] =
      Await[F2, A, O2](req, e => Trampoline.suspend(rcv(e)).map(f))
  }

  /**
   * Represents state of process, where there are possibly elements ready to be _emitted_ in `head`.
   *
   */
  case class Emit[O](head: Seq[O]) extends Process2[Nothing, O] {

    /** attach supplied p in case head is nonempty, else returns suspended p **/
    def extend[F[_]](p: => Process2[F, O]): Process2[F, O] = {
      if (head.nonEmpty) this append p else p
    }

  }

  //  /**
  //   * Represents state of the process where evaluation of the next state is suspended,
  //   * that means it is evaluated when needed. This is particularly usefull with Emit.
  //   * @param get
  //   * @tparam F
  //   * @tparam O
  //   */
  //  case class Suspend[F[_], O](get: Trampoline[Process2[F, O]]) extends Process2[F, O]


  case class Append[F[_], O](p: Process2[F, O], next: Vector[Throwable => Process2[F, O]]) extends Process2[F, O] {


    /**
     * Helper to modify the p and appended processes
     */
    def extend[F2[x] >: F[x], O2](f: Process2[F, O] => Process2[F2, O2]): Process2[F2, O2] =
      Append(f(p) , next.map(_ andThen f))


  }


  /**
   * Wraps the evaluation of supplied next process state in Suspend. Note that means
   * that the process evaluation will happen when the driver will need that process state, not before
   *
   * Note, that the `p` is reevaluated always when the driver need that next process state.
   * If this is not desired, any ou would like the evaluation happen only once, please use `lazily`
   * @return
   */
  def suspend[F[_], O](p: => Process2[F, O]): Process2[F, O] =
    Append(halt, Vector({
      case End | Kill => p
      case rsn        => fail(rsn)
    }: Throwable => Process2[F, O]))

  /**
   * Similar to `suspend`, except it caches the evaluation of `p` for subsequent computations
   * @return
   */
  def lazily[F[_], O](p: => Process2[F, O]): Process2[F, O] = {
    lazy val pe = p
    suspend(pe)
  }

  /**
   * Helper to wrap evaluation of `p` that may cause side-effects by throwing exception.
   */
  private[stream] def Try[F[_], A](p: => Process2[F, A]): Process2[F, A] =
    try p
    catch {case e: Throwable => Halt(e) }

  /** safely evaluates trampoline of process to produce process **/
  private[stream] def safely[F[_], A](p: => Trampoline[Process2[F, A]]): Process2[F, A] =
    p.attemptRun match {
      case \/-(op) => op.asInstanceOf[Process2[F, A]]
      case -\/(e)  => Halt(e)
    }


  val halt = Halt(End)

  def fail(err: Throwable): Process2[Nothing, Nothing] =
    Halt(err)

  def fail(msg: String): Process2[Nothing, Nothing] =
    Halt(new Exception(msg))

  def emit[O](o: O): Process2[Nothing, O] =
    Emit[O](Vector(o))

  def emitAll[O](s: Seq[O]): Process2[Nothing, O] =
    Emit[O](s)

  def emitSeq[F[_], O](
    head: Seq[O],
    tail: Process2[F, O] = halt): Process2[F, O] =
    if (tail != halt) Emit(head) ++ tail
    else Emit(head)


  def await[F[_], A, O](req: F[A])(rcv: Throwable \/ A => Trampoline[Process2[F, O]]): Process2[F, O] =
    Await[F, A, O](req, rcv)

  object Await21 {
    def unapply[I, O](self: Process21[I, O]):
    Option[Throwable \/ I => Process21[I, O]] = self match {
      case Await(_, rcv) => Some((r: Throwable \/ I) => safely(rcv(r)))
      case _             => None
    }
  }

  def eval[F[_], O](req: F[O]): Process2[F, O] =
    Await[F, O, O](req, _.fold(
      e => Trampoline.now(fail(e)),
      a => Trampoline.now(emit(a))
    ))

  /**
   * Special exception indicating normal termination due to
   * input ('upstream') termination. An `Await` may respond to an `End`
   * by switching to reads from a secondary source.
   */
  case object End extends Exception {
    override def fillInStackTrace = this
  }
  /**
   * Special exception indicating downstream termination.
   * An `Await` should respond to a `Kill` by performing
   * necessary cleanup actions, then halting.
   */
  case object Kill extends Exception {
    override def fillInStackTrace = this
  }


  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////
  //
  //  SYNTAX
  //
  ////////////////////////////////////////////////////////////////////////////////////////////////////////////////////


}
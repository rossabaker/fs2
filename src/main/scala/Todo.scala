import cats.{Apply, Eq}
import cats.data.{NonEmptyList, Or}

import scalaz.concurrent.Task

package object scalaz {

  type \/[+A, +B] = A Or B
  val  \/         = Or

  type -\/[+A]    = Or.LeftOr[A]
  val  -\/        = Or.LeftOr

  type \/-[+B]    = Or.RightOr[B]
  val  \/-        = Or.RightOr

  trait Catchable[F[_]] {
    def attempt[A](f: F[A]): F[Throwable \/ A]
    def fail[A](e: Throwable): F[A]
  }

  trait Nondeterminism[F[_]] {
    def gatherUnordered[A](fs: Seq[F[A]]): F[List[A]]
    def gather[A](fs: Seq[F[A]]): F[List[A]]
    def both[A, B](a: F[A], b: F[B]): F[(A, B)]
  }
  object Nondeterminism {
    def apply[F[_]](implicit F: Nondeterminism[F]): Nondeterminism[F] = F
  }

  sealed abstract class Either3[+A, +B, +C] extends Product with Serializable {
    def fold[Z](left: A => Z, middle: B => Z, right: C => Z): Z = this match {
      case Left3(a)   => left(a)
      case Middle3(b) => middle(b)
      case Right3(c)  => right(c)
    }

    def eitherLeft: (A \/ B) \/ C = this match {
      case Left3(a)   => -\/(-\/(a))
      case Middle3(b) => -\/(\/-(b))
      case Right3(c)  => \/-(c)
    }

    def eitherRight: A \/ (B \/ C) = this match {
      case Left3(a)   => -\/(a)
      case Middle3(b) => \/-(-\/(b))
      case Right3(c)  => \/-(\/-(c))
    }

    def leftOr[Z](z: => Z)(f: A => Z)   = fold(f, _ => z, _ => z)
    def middleOr[Z](z: => Z)(f: B => Z) = fold(_ => z, f, _ => z)
    def rightOr[Z](z: => Z)(f: C => Z)  = fold(_ => z, _ => z, f)
  }

  final case class Left3[+A, +B, +C](a: A) extends Either3[A, B, C]
  final case class Middle3[+A, +B, +C](b: B) extends Either3[A, B, C]
  final case class Right3[+A, +B, +C](c: C) extends Either3[A, B, C]

  object Either3 {
    def left3[A, B, C](a: A):   Either3[A, B, C] = Left3(a)
    def middle3[A, B, C](b: B): Either3[A, B, C] = Middle3(b)
    def right3[A, B, C](c: C):  Either3[A, B, C] = Right3(c)
  }

  implicit class ListOps[A](self: List[A]) {
    def minimum: Option[A] = ???
    def maximum: Option[A] = ???
    def splitWith(a: Int => Boolean): List[NonEmptyList[A]] = ???
    def intersperse(a: A): List[A] = ???
  }

  implicit class NonEmptyListOps[A](self: NonEmptyList[A]) {
    def toList: List[A] = ???
  }

  implicit def tuple2Eq[A, B]: Eq[(A, B)] = ???
  implicit def tuple3Eq[A, B, C]: Eq[(A, B, C)] = ???

  implicit class ApplyOps[F[_]: Apply, A](self: F[A]) {
    def *>[B](fb: F[B]): F[B] = ???
  }
}

package scalaz {

import java.util.concurrent.{ScheduledExecutorService, ExecutorService}

import cats.Monad

import scala.concurrent.duration.Duration

package object concurrent {

  trait Strategy {
    def apply[A](a: => A): () => A

  }
  object Strategy {
    val DefaultExecutorService: ExecutorService = ???
    val DefaultTimeoutScheduler: ScheduledExecutorService = ???
    implicit val DefaultStrategy: Strategy = ???
    def Executor(es: ExecutorService): Strategy = ???
  }

  trait Task[+A] {
    def run: A = ???
    def attemptRun: Throwable Or A = ???
    def runAsync(cb: (Throwable Or A) => Unit): Unit = ???
    def attempt: Task[Throwable Or A] = ???
    def map[B](f: A => B): Task[B] = ???
    def flatMap[B](f: A => Task[B]): Task[B] = ???
    def timed(timeoutInMillis: Long)(implicit scheduler:ScheduledExecutorService): Task[A] = ???
    def timed(timeout: Duration)(implicit scheduler:ScheduledExecutorService = Strategy.DefaultTimeoutScheduler): Task[A] = ???
  }

  object Task {
    def now[A](a: A): Task[A] = ???
    def delay[A](a: => A): Task[A] = ???
    def fail[A](e: Throwable): Task[A] = ???
    def async[A](cb: ((Throwable Or A) => Unit) => Unit): Task[A] = ???
    def apply[A](a: A)(implicit es: ExecutorService = Strategy.DefaultExecutorService): Task[A] = ???
    def fork[A](a: Task[A])(implicit es: ExecutorService = Strategy.DefaultTimeoutScheduler): Task[A] = ???

    implicit def taskInstance[A]: Monad[Task] with Catchable[Task] with Nondeterminism[Task] =
      new Monad[Task] with Catchable[Task] with Nondeterminism[Task] {
        def flatMap[A, B](fa: Task[A])(f: A => Task[B]): Task[B] = fa.flatMap(f)
        def pure[A](x: A): Task[A] = Task.now(x)
        def attempt[A](f: Task[A]): Task[\/[Throwable, A]] = ???
        def fail[A](e: Throwable): Task[A] = ???
        def gatherUnordered[A](fs: Seq[Task[A]]): Task[List[A]] = ???
        def both[A, B](a: Task[A], b: Task[B]): Task[(A, B)] = ???
        def gather[A](fs: Seq[Task[A]]): Task[List[A]] = ???
      }
  }

  case class Actor[A](handler: A => Unit)(implicit S: Strategy) {
    def !(a: A): Unit = ???
  }
}
}

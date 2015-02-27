package scalaz.stream

import org.scalacheck.{Arbitrary, Gen}
import org.scalacheck.Arbitrary.arbitrary
import cats.Eq
import cats.std.all._
import cats.data.Or._
import scalaz.concurrent.Task
import scodec.bits.ByteVector

import Process._
import ReceiveY._
import process1._

object TestInstances {
  implicit val arbitraryByteVector: Arbitrary[ByteVector] =
    Arbitrary(arbitrary[String].map(s => ByteVector.view(s.getBytes)))

  implicit def arbitraryIndexedSeq[A: Arbitrary]: Arbitrary[IndexedSeq[A]] =
    Arbitrary(arbitrary[List[A]].map(_.toIndexedSeq))

  implicit def arbitraryProcess0[A: Arbitrary]: Arbitrary[Process0[A]] =
    Arbitrary(arbitrary[List[A]].map(list => emitAll(list)))

  implicit def arbitraryProcess1[A]: Arbitrary[Process1[A,A]] = {
    val ps0Gen: Gen[Process1[A,A]] =
      Gen.oneOf(Seq(bufferAll, dropLast, id, last, skip))

    val ps1Int: Seq[Int => Process1[A,A]] =
      Seq(buffer, drop, take)

    val ps1IntGen: Gen[Process1[A,A]] =
      Gen.oneOf(ps1Int).flatMap(p => Gen.posNum[Int].map(i => p(i)))

    Arbitrary(Gen.oneOf(ps0Gen, ps1IntGen))
  }

  implicit val arbitraryProcess1Int: Arbitrary[Process1[Int,Int]] = {
    val ps0Gen: Gen[Process1[Int,Int]] =
      Gen.oneOf(Seq(prefixSums, sum))

    val ps1: Seq[Int => Process1[Int,Int]] =
      Seq(intersperse, i => lastOr(i), i => shiftRight(i))

    val ps1Gen: Gen[Process1[Int,Int]] =
      Gen.oneOf(ps1).flatMap(p => arbitrary[Int].map(i => p(i)))

    Arbitrary(Gen.oneOf(ps0Gen, arbitraryProcess1[Int].arbitrary))
  }

  implicit def arbitraryReceiveY[A: Arbitrary,B: Arbitrary]: Arbitrary[ReceiveY[A,B]] =
    Arbitrary(Gen.oneOf(
      arbitrary[A].map(ReceiveL(_)),
      arbitrary[B].map(ReceiveR(_))
    ))

  implicit def equalProcess0[A: Eq]: Eq[Process0[A]] =
    Eq.by(_.toList)

  implicit def equalProcessTask[A:Eq]: Eq[Process[Task,A]] =
    new Eq[Process[Task, A]] {
      def eqv(x: Process[Task, A], y: Process[Task, A]): Boolean = x.runLog.attemptRun == y.runLog.attemptRun
    }
}

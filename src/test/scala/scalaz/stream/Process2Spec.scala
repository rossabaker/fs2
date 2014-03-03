package scalaz.stream

import Process2._
import scalaz.Monoid
import scalaz.concurrent.Task


/**
 * Created by pach on 01/03/14.
 */
object Process2Test extends App {

  def time[A](a: => A, l: String = ""): A = {
    val start = System.currentTimeMillis
    val result = a
    val stop = System.currentTimeMillis
    // println("result: " + result)
    println(s"$l took ${(stop - start) / 1000.0 } seconds")
    result
  }

  def bad(n: Int): Process2[Task, Int] =
    (0 to n).map(emit).foldLeft(emit(0))(_ ++ _)

  def good(n: Int): Process2[Task, Int] =
    (0 to n).map(emit).reverse.foldLeft(emit(0))(
      (acc, h) => h ++ acc
    )


  def P1badFlatMap2(n: Int): Process[Task, Int] =
    if (n == 0) Process.halt
    else Process.emit(1).flatMap(_ => P1badFlatMap2(n - 1) ++ P1badFlatMap2(n - 1))

  def badFlatMap2(n: Int): Process2[Task, Int] =
    if (n == 0) halt
    else emit(1).flatMap(_ => badFlatMap2(n - 1) ++ badFlatMap2(n - 1))

  def P1badFlatMap1(n: Int): Process[Task, Int] =
    (0 to n).map(Process.emit).foldLeft(Process.emit(0))((acc, h) =>
      acc.flatMap(acc => h.map(h => acc + h)))

  // check for left-associated binds
  def badFlatMap1(n: Int): Process2[Task, Int] =
    (0 to n).map(emit).foldLeft(emit(0))((acc, h) =>
      acc.flatMap(acc => h.map(h => acc + h)))

  // check for right-associated binds
  def goodFlatMap1(n: Int): Process2[Task, Int] =
    (0 to n).reverse.foldLeft(emit(0))((acc, h) =>
      acc.flatMap(acc => emit(h + acc)))

  def goodFlatMap2(n: Int): Process2[Task, Int] =
    (0 to n).reverse.map(emit).foldLeft(emit(0))((acc, h) =>
      h.flatMap(h => acc.map(_ + h)))

  def worstCaseScenario(n: Int): Process2[Task, Int] = {
    @annotation.tailrec
    def churn(p: Process2[Task, Int], m: Int): Process2[Task, Int] =
      if (m == 0) p
      else churn(p.flatMap(emit), m - 1) // does nothing, but adds another flatMap
    churn(bad(n), n)
  }

  implicit val B = Monoid.instance[Int]((a, b) => a + b, 0)


  println("############bad append")
  time { bad(1).runFoldMap(identity).run }
  time { bad(10).runFoldMap(identity).run }
  time { bad(100).runFoldMap(identity).run }
  time { bad(1000).runFoldMap(identity).run }
  time { bad(10000).runFoldMap(identity).run }
  time { bad(100000).runFoldMap(identity).run }
  time { bad(1000000).runFoldMap(identity).run }

  println("#########good append")
  time { good(1).runFoldMap(identity).run }
  time { good(10).runFoldMap(identity).run }
  time { good(100).runFoldMap(identity).run }
  time { good(1000).runFoldMap(identity).run }
  time { good(10000).runFoldMap(identity).run }
  time { good(100000).runFoldMap(identity).run }
  time { good(1000000).runFoldMap(identity).run }

  println("good flatMap 1")
  time { goodFlatMap1(1).runFoldMap(identity).run }
 time { goodFlatMap1(10).runFoldMap(identity).run }
 time { goodFlatMap1(100).runFoldMap(identity).run }
 time { goodFlatMap1(1000).runFoldMap(identity).run }
  time { goodFlatMap1(100000).runFoldMap(identity).run }
  time { goodFlatMap1(100000).runFoldMap(identity).run }
  time { goodFlatMap1(1000000).runFoldMap(identity).run }
  //
  println("good flatMap 2")
  time { goodFlatMap2(1).runFoldMap(identity).run }
  time { goodFlatMap2(10).runFoldMap(identity).run }
  time { goodFlatMap2(100).runFoldMap(identity).run }
  time { goodFlatMap2(1000).runFoldMap(identity).run }
  time { goodFlatMap2(10000).runFoldMap(identity).run }
  time { goodFlatMap2(100000).runFoldMap(identity).run }
  time { goodFlatMap2(1000000).runFoldMap(identity).run }
  //
  println("bad flatMap 1")
  time { badFlatMap1(1).runFoldMap(identity).run }
  time { badFlatMap1(10).runFoldMap(identity).run }
  time { badFlatMap1(100).runFoldMap(identity).run }
  time { badFlatMap1(1000).runFoldMap(identity).run }
  time { badFlatMap1(10000).runFoldMap(identity).run }
  time { badFlatMap1(100000).runFoldMap(identity).run }
  time { badFlatMap1(1000000).runFoldMap(identity).run }

  //
  println("bad flatMap 2")
  time { badFlatMap2(14).runFoldMap(identity).run }
  time { badFlatMap2(15).runFoldMap(identity).run }
  time { badFlatMap2(16).runFoldMap(identity).run }
  time { badFlatMap2(17).runFoldMap(identity).run }
  time { badFlatMap2(18).runFoldMap(identity).run }
  time { badFlatMap2(19).runFoldMap(identity).run }
  time { badFlatMap2(20).runFoldMap(identity).run }
  time { badFlatMap2(21).runFoldMap(identity).run }
  //
  println("worst case scenario")
  time { worstCaseScenario(1).runFoldMap(identity).run }
  time { worstCaseScenario(2).runFoldMap(identity).run }
  time { worstCaseScenario(4).runFoldMap(identity).run }
  time { worstCaseScenario(8).runFoldMap(identity).run }
  time { worstCaseScenario(16).runFoldMap(identity).run }
  time { worstCaseScenario(32).runFoldMap(identity).run }
  time { worstCaseScenario(64).runFoldMap(identity).run }
  time { worstCaseScenario(128).runFoldMap(identity).run }
  time { worstCaseScenario(256).runFoldMap(identity).run }
  time { worstCaseScenario(512).runFoldMap(identity).run }
  time { worstCaseScenario(1024).runFoldMap(identity).run }
  time { worstCaseScenario(2048).runFoldMap(identity).run }
}
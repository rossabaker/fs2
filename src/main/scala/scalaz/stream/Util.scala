package scalaz.stream

import scala.annotation.tailrec

/**
 * Various utilities and helpers helping the internal implementation
 */
private[stream] object Util {

  implicit class AppendSyntax[A](val self:Seq[A]) extends AnyVal{

    /**
     * Helper to fix performance issue on Vector append Seq
     * hopefully this can be removed in scala 2.11
     */
    def fast_++[B >: A](other:Seq[B]) : Vector[B] = {
      @tailrec
      def append(acc:Vector[B], rem:Seq[B]) : Vector[B] = {
        debug(s"AP: self: ${self.size} other: ${other.size}")
        if (rem.nonEmpty) append(acc :+ rem.head, rem.tail)
        else acc
      }

      @tailrec
      def prepend(acc:Vector[B], rem:Seq[B]) : Vector[B] = {
        debug(s"PREP: self: ${self.size} other: ${other.size}")
        if (rem.nonEmpty) prepend(rem.head +: acc, rem.tail)
        else acc
      }

      if (self.size < other.size) prepend(other.toVector,self)
      else append(self.toVector, other)
    }
  }


  def debug(s: => String) = {
  //println(s)
  }



}

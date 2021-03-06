package LOWERCASE_DSL_NAME.shared

import scala.annotation.unchecked.uncheckedVariance
import scala.reflect.{Manifest,SourceContext}
import scala.virtualization.lms.common._

// Front-end
trait ForgeArrayOps extends Base {
  /**
   * We use ForgeArray[T] instead of T so we don't get any subtle errors related to shadowing Array[T]
   */
  type ForgeArray[T]
  implicit def forgeArrayManifest[T:Manifest]: Manifest[ForgeArray[T]]

  /**
   * Applications may need direct access to ForgeArrays, if, for example, they use string fsplit
   * How do we allow DSLs to only optionally include the Array API for end users?
   */
  def array_apply[T:Manifest](__arg0: Rep[ForgeArray[T]],__arg1: Rep[Int])(implicit __imp0: SourceContext): Rep[T]
  def array_length[T:Manifest](__arg0: Rep[ForgeArray[T]])(implicit __imp0: SourceContext): Rep[Int]

  /* Required for apps to be able use 'args' */
  implicit class ScalaArrayOps[T:Manifest](x: Rep[Array[T]]) {
    def apply(n: Rep[Int])(implicit ctx: SourceContext) = scala_array_apply(x,n)
    def length = scala_array_length(x)
  }
  // the implicit class method 'length' is not working for an unknown reason, possibly related to the problem mentioned in ForgeArrayCompilerOps below
  // omitting SourceContext is a hacky way to avoid conflicts with Forge DSLs without using an arbitrary Overloaded parameter
  def infix_length[T:Manifest](x: Rep[Array[T]]) = scala_array_length(x)
  def scala_array_apply[T:Manifest](x: Rep[Array[T]], n: Rep[Int])(implicit __imp0: SourceContext): Rep[T]
  def scala_array_length[T:Manifest](x: Rep[Array[T]])(implicit __imp0: SourceContext): Rep[Int]
}
trait ForgeArrayCompilerOps extends ForgeArrayOps {
  /**
   * There are some unfortunate scalac typer crashes when we try to use the nicer front-end from DSLs :(
   */
  object Array {
    def empty[T:Manifest](__arg0: Rep[Int])(implicit __imp0: SourceContext) = array_empty[T](__arg0)
    def copy[T:Manifest](__arg0: Rep[ForgeArray[T]],__arg1: Rep[Int],__arg2: Rep[ForgeArray[T]],__arg3: Rep[Int],__arg4: Rep[Int])(implicit __imp0: SourceContext) = array_copy(__arg0,__arg1,__arg2,__arg3,__arg4)
  }

  implicit class ForgeArrayOps[T:Manifest](x: Rep[ForgeArray[T]]) {
    def update(n: Rep[Int], y: Rep[T])(implicit ctx: SourceContext) = array_update(x,n,y)
    def apply(n: Rep[Int])(implicit ctx: SourceContext) = array_apply(x,n)
    def Clone(implicit ctx: SourceContext) = array_clone(x)
  }

  def array_empty[T:Manifest](__arg0: Rep[Int])(implicit __imp0: SourceContext): Rep[ForgeArray[T]]
  def array_empty_imm[T:Manifest](__arg0: Rep[Int])(implicit __imp0: SourceContext): Rep[ForgeArray[T]]
  def array_copy[T:Manifest](__arg0: Rep[ForgeArray[T]],__arg1: Rep[Int],__arg2: Rep[ForgeArray[T]],__arg3: Rep[Int],__arg4: Rep[Int])(implicit __imp0: SourceContext): Rep[Unit]
  def array_update[T:Manifest](__arg0: Rep[ForgeArray[T]],__arg1: Rep[Int],__arg2: Rep[T])(implicit __imp0: SourceContext): Rep[Unit]
  def array_clone[T:Manifest](__arg0: Rep[ForgeArray[T]])(implicit __imp0: SourceContext): Rep[ForgeArray[T]]
  def array_take[T:Manifest](__arg0: Rep[ForgeArray[T]],__arg1: Rep[Int]): Rep[ForgeArray[T]]
  def array_map[T:Manifest,R:Manifest](__arg0: Rep[ForgeArray[T]], __arg1: Rep[T] => Rep[R])(implicit __imp0: SourceContext): Rep[ForgeArray[R]]
  def array_sort[T:Manifest:Ordering](__arg0: Rep[ForgeArray[T]])(implicit __imp0: SourceContext): Rep[ForgeArray[T]]
  def array_fromseq[T:Manifest](__arg0: Seq[Rep[T]])(implicit __imp0: SourceContext): Rep[ForgeArray[T]]
  def array_string_split(__arg0: Rep[String], __arg1: Rep[String])(implicit __imp0: SourceContext): Rep[ForgeArray[String]]

  def scala_array_apply[T:Manifest](__arg0: Rep[Array[T]],__arg1: Rep[Int])(implicit __imp0: SourceContext): Rep[T]
  def scala_array_length[T:Manifest](__arg0: Rep[Array[T]])(implicit __imp0: SourceContext): Rep[Int]
}

trait ForgeArrayBufferOps extends Base {
  type ForgeArrayBuffer[T]
}
trait ForgeArrayBufferCompilerOps extends ForgeArrayBufferOps {
  def array_buffer_empty[T:Manifest](__arg0: Rep[Int])(implicit __imp0: SourceContext): Rep[ForgeArrayBuffer[T]]
  def array_buffer_copy[T:Manifest](__arg0: Rep[ForgeArrayBuffer[T]],__arg1: Rep[Int],__arg2: Rep[ForgeArrayBuffer[T]],__arg3: Rep[Int],__arg4: Rep[Int])(implicit __imp0: SourceContext): Rep[Unit]
  def array_buffer_update[T:Manifest](__arg0: Rep[ForgeArrayBuffer[T]],__arg1: Rep[Int],__arg2: Rep[T])(implicit __imp0: SourceContext): Rep[Unit]
  def array_buffer_apply[T:Manifest](__arg0: Rep[ForgeArrayBuffer[T]],__arg1: Rep[Int])(implicit __imp0: SourceContext): Rep[T]
  def array_buffer_length[T:Manifest](__arg0: Rep[ForgeArrayBuffer[T]])(implicit __imp0: SourceContext): Rep[Int]
  def array_buffer_set_length[T:Manifest](__arg0: Rep[ForgeArrayBuffer[T]],__arg1: Rep[Int])(implicit __imp0: SourceContext): Rep[Unit]
  def array_buffer_append[T:Manifest](__arg0: Rep[ForgeArrayBuffer[T]],__arg1: Rep[T])(implicit __imp0: SourceContext): Rep[Unit]
  def array_buffer_indexof[T:Manifest](__arg0: Rep[ForgeArrayBuffer[T]],__arg1: Rep[T])(implicit __imp0: SourceContext): Rep[Int]
}

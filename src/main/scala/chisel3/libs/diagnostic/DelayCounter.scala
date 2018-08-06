package chisel3.libs.diagnostic

import chisel3.{Data, _}
import chisel3.core.{BaseModule, ChiselAnnotation, RunFirrtlTransform, dontTouch}
import chisel3.experimental.{MultiIOModule, RawModule, annotate, withRoot}
import firrtl.{AnnotationSeq, CircuitForm, CircuitState, HighForm, LowForm, MidForm, RegKind, RenameMap, Transform, WRef}
import firrtl.annotations._
import firrtl.ir.{Input => _, Module => _, Output => _, _}
import firrtl.passes.memlib.AnalysisUtils
import firrtl.passes.memlib.AnalysisUtils.Connects
import firrtl.passes.wiring.WiringInfo

import scala.collection.JavaConverters._
import scala.collection.mutable


/** Options for me to work on
  *
  * Get executing test for BreakPoint
  * Add transform for DelayCounter
  * Start working on Named
  * Work on pitch to Jonathan
  *
  */
object DelayCounter {

  /**
    *
    * @param name Ref of the hardware breakpoint instance
    * @param root Location where the breakpoint will live
    * @param f Function to build breakpoint hardware
    * @tparam T Type of the root hardware
    * @return BreakPoint annotation
    */
  def apply[T<: BaseModule](root: T, source: Data, sink: Data): Seq[ChiselAnnotation] = {

    // Build Names for references
    //val circuitName = CircuitName(root.circuitName)
    //val moduleName = ModuleName(root.name, circuitName)
    //def toNamed(ref: Data): ComponentName = ComponentName(ref.pathTo(root).mkString("."), moduleName)

    // Return Annotations
    withRoot(root){
      Seq(DelayCounterAnnotation(source.toNamed, sink.toNamed, root.toNamed, None))
    }
  }
}

class DelayCounterTransform extends firrtl.Transform {
  override def inputForm: CircuitForm = LowForm
  override def outputForm: CircuitForm = LowForm

  /** Counts the number of registers from sink to source
    *
    * @param expr Expression to walk
    * @param source Source to find
    * @return For each path from sink to source, count number of register crossings
    */
  def countDelays(expr: Expression, source: String, delaySoFar: Int, connections: Connects): collection.Set[Int] = expr match {
    case WRef(`source`, _, _, _) => Set(delaySoFar)
    case WRef(n, _, _, _) if !connections.contains(n) => Set.empty[Int]
    case WRef(r, _, RegKind, _) => countDelays(connections(r), source, delaySoFar + 1, connections)
    case other =>
      val delays = mutable.HashSet[Int]()

      def onExpr(e: Expression): Expression = {
        val delay = countDelays(e, source, delaySoFar, connections)
        delays ++= delay
        e
      }

      other mapExpr onExpr
      delays
  }

  override def execute(state: CircuitState): CircuitState = {
    val adas = state.annotations.groupBy{
      case a: DelayCounterAnnotation => "delay"
      case other => "other"
    }
    val adaMap = adas.getOrElse("delay", Nil).asInstanceOf[Seq[DelayCounterAnnotation]].groupBy(_.enclosingModule.name)

    val moduleMap = state.circuit.modules.map(m => m.name -> m).toMap
    val errors = mutable.ArrayBuffer[String]()

    // TODO: Assert no CMR's, future work should enable this

    val newAnnotations = mutable.ArrayBuffer[DelayCounterAnnotation]()
    state.circuit.modules.foreach { m =>
      val connections = AnalysisUtils.getConnects(m)
      adaMap.getOrElse(m.name, Nil).map { case DelayCounterAnnotation(source, sink, enclosingModule, delay) =>
        val delays = countDelays(connections(sink.name), source.name, 0, connections)
        newAnnotations += DelayCounterAnnotation(source, sink, enclosingModule, Some(delays))
      }
    }

    state.copy(annotations = adas.getOrElse("other", Nil) ++ newAnnotations)
  }
}

case class DelayCounterAnnotation(source: ComponentName, sink: ComponentName, enclosingModule: ModuleName, delay: Option[collection.Set[Int]]) extends Annotation with RunFirrtlTransform {
  override def toFirrtl: Annotation = this
  override def transformClass: Class[_ <: Transform] = classOf[DelayCounterTransform]
  private val errors = mutable.ArrayBuffer[String]()
  private def rename(n: Component, renames: RenameMap): Component = (n, renames.get(n)) match {
    case (c: Component, Some(Seq(x: Component))) => x
    case (_, None) => n
    case (_, other) =>
      errors += s"Bad rename in ${this.getClass}: $n to $other"
      n
  }
  override def update(renames: RenameMap): Seq[Annotation] = {
    val newSource = rename(source, renames)
    val newSink = rename(sink, renames)
    val newEncl = rename(enclosingModule, renames)
    if(errors.nonEmpty) {
      throw new Exception(errors.mkString("\n"))
    }
    Seq(DelayCounterAnnotation(newSource, newSink, newEncl, delay))
  }
}

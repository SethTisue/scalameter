package org.scalameter

import java.io.File
import org.scalameter.Key._
import org.scalameter.execution.invocation.InvocationCountMatcher
import org.scalameter.execution.invocation.instrumentation.{MethodSignature, Instrumentation, MethodInvocationCounter}
import scala.collection.Seq
import scala.collection.mutable


/** Mixin for all [[org.scalameter.Measurer]] implementations that perform any kind of method invocation counting. */
trait InvocationCount extends Measurer {
  def matcher: InvocationCountMatcher

  def units: String = "#"

  def measure[T, U](context: Context, measurements: Int, setup: (T) => Any, tear: (T) => Any, regen: () => T, snippet: (T) => Any): Seq[Double] = {
    val invocationsBuilder = List.newBuilder[Double]
    var obj: Any = null.asInstanceOf[Any]
    val numMethods = context.goe(exec.measurers.methodInvocationLookupTable,
      sys.error("Measurer.prepareContext should be called before Measurer.measure")).length

    def measureSnippet(value: T): Any = {
      MethodInvocationCounter.setup(numMethods)
      setup(value)

      MethodInvocationCounter.start()
      val obj = snippet(value)
      MethodInvocationCounter.stop()
      tear(value)

      invocationsBuilder += MethodInvocationCounter.counts.sum
      obj
    }

    if (context(exec.assumeDeterministicRun)) obj = measureSnippet(regen())
    else {
      var iteration = 0
      while (iteration < measurements) {
        obj = measureSnippet(regen())
        iteration += 1
      }
    }

    val invocations = invocationsBuilder.result()
    log.verbose("Measurements: " + invocations.mkString(", "))
    invocations
  }

  override def usesInstrumentedClasspath: Boolean = true

  /** Creates the [[Key.exec.measurers.instrumentedJarPath]] with an abstract temporary file,
   *  and the [[Key.exec.measurers.methodInvocationLookupTable]] with an empty [[scala.collection.mutable.AbstractBuffer]].
   *
   *  @param context [[org.scalameter.Context]] that should the setup tree context
   */
  override def prepareContext(context: Context): Context = {
    val jar = File.createTempFile(s"scalameter-bench-", "-instrumented.jar")
    jar.deleteOnExit()

    context ++ Context(
      exec.measurers.methodInvocationLookupTable -> mutable.ArrayBuffer.empty[MethodSignature],
      exec.measurers.instrumentedJarPath -> jar
    )
  }

  /** Creates a jar with instrumented classes under the location pointed by [[Key.exec.measurers.instrumentedJarPath]],
   *  and saves the internal method lookup table under the [[Key.exec.measurers.methodInvocationLookupTable]].
   *
   *  @param context [[org.scalameter.Context]] that should be a result of the [[prepareContext]]
   */
  override def beforeExecution(context: Context) = {
    val jar = context.goe(exec.measurers.instrumentedJarPath, sys.error("Measurer.beforeExecution should be called after Measurer.prepareContext"))
    val lookupTable = context.goe(exec.measurers.methodInvocationLookupTable, sys.error("Measurer.beforeExecution should be called after Measurer.prepareContext"))

    lookupTable ++= Instrumentation.writeInstrumentedClasses(ctx = context, matcher = matcher, to = jar)
  }

  /** Removes instrumented jar from filesystem.
   *
   *  @param context [[org.scalameter.Context]] that should be a result of the [[prepareContext]]
   */
  override def afterExecution(context: Context) = {
    val jar = context.goe(exec.measurers.instrumentedJarPath, sys.error("Measurer.afterExecution should be called after Measurer.prepareContext"))
    jar.delete()
  }
}
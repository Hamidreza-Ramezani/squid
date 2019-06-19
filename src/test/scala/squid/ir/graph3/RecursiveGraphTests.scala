package squid
package ir
package graph3

import utils._
import squid.lib.matching._
import squid.lib
import squid.ir.graph.{SimpleASTBackend => AST}

object RecursiveGraphTests extends HaskellGraph with HaskellGraphScheduling2 {
  val DummyTyp = Any
  override def staticModuleType(name: String): TypeRep = DummyTyp
  override def methodApp(self: Rep, mtd: MtdSymbol, targs: List[TypeRep], argss: List[ArgList], tp: TypeRep) = mtd match {
    case Apply.Symbol => super.methodApp(self, mtd, targs, argss, tp)
    case _ =>
      val mod = StaticModule(mtd.name.toString).toRep
      val base = self.node match {
        case ConcreteNode(StaticModule("squid.lib.package")) => mod
        case _ => Apply(mod, self, tp).toRep
      }
      argss.flatMap(_.reps).foldLeft(base) { case (cur,r) => Apply(cur,r,tp).toRep }
  }
  override def byName(arg: => Rep) = arg
}

class RecursiveGraphTests extends MyFunSuite(RecursiveGraphTests) with GraphRewritingTester[RecursiveGraphTests.type] {
  import DSL.Predef._
  import DSL.Quasicodes._
  import haskell.Prelude.compose
  
  object Tr extends SimpleRuleBasedTransformer with RecursiveGraphTests.SelfTransformer {
    
  }
  
  def letrec[A:CodeType,B:CodeType](f: OpenCode[A => B] => OpenCode[A => B]): ClosedCode[A => B] = {
    val rec: Variable[A => B] = Variable[A => B]
    /*
    val body = f(rec)
    // does not work: (capture does not happen because val bindings are not supposed to be recursive)
    //code"val $rec = $body; $rec".unsafe_asClosedCode
    */
    import RecursiveGraphTests._
    val v = rec.`internal bound`
    val rv = mkValRep(v)
    reificationContext += v -> rv
    val recCde = rec.toCode
    val body = f(recCde)
    val cde = body.rep
    if (cde.bound.name.contains('$')) cde.bound.name_! = "rec"
    recCde.rep.rewireTo(cde)
    reificationContext -= v
    Code(cde)
  }
  
  test("Simple Count") {
    
    val cde = letrec((f: OpenCode[Int => Int]) => code"(n: Int) => if (n > 0) $f(n-1)+1 else 0")
    println(cde.rep.showGraph)
    
    if (DSL.simplifyGraph(cde.rep, recurse = false) also println)
      println(cde.rep.showGraph)
    
    if (DSL.simplifyGraph(cde.rep, recurse = false) also println)
      println(cde.rep.showGraph)
    
    println(DSL.scheduleRec(cde.rep))
    
  }
  
  test("Weird Count") {
    
    //val f = letrec((f: OpenCode[Int => Int]) => code"(n: Int) => if (n > 0) $f(n-1) else 0")
    val f = letrec((f: OpenCode[Int => Int]) => code"(n: Int) => if (n > 1) $f(n-1)+$f(n/2) else n")
    //val cde = f
    val cde = code"($f,$f)"
    println(cde.rep.showGraph)
    
    // Does not work: the recursive binding creates a stack overflow in the scheduling's analysis
    /*
    RecursiveGraphTests.ScheduleDebug debugFor
      println(cde.rep.show)
    //doTest(cde)()
    */
    
    //println(DSL.simplifyGraph(cde.rep)) // Stack overflow: simplifier tried to follow the recursive calls
    
    if (DSL.simplifyGraph(cde.rep, recurse = false) also println)
      println(cde.rep.showGraph)
    
    if (DSL.simplifyGraph(cde.rep, recurse = false) also println)
      println(cde.rep.showGraph)
    
    println(DSL.scheduleRec(cde.rep))
    
    // TODO count tailrec
    
  }
  
  
  test("Oops 1") {
    
    val cde = letrec[Int,Int](f => code"(n: Int) => $f(n)-1")
    
    if (DSL.simplifyGraph(cde.rep, recurse = false) also println)
      println(cde.rep.showGraph)
    
    println(DSL.scheduleRec(cde.rep))
    
  }
  
  test("Oops 2") {
    
    val cde = letrec[Int,Int](f => code"(n: Int) => $f(n-1)")
    
    // FIXME SOF
    //if (DSL.simplifyGraph(cde.rep, recurse = false) also println)
    //  println(cde.rep.showGraph)
    
    println(DSL.scheduleRec(cde.rep))
    
  }
  
  test("Omega") {
    
    // TODO
    
  }
  
}
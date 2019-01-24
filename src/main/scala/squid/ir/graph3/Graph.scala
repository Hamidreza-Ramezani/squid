// Copyright 2019 EPFL DATA Lab (data.epfl.ch)
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package squid.ir
package graph3

import squid.utils._
import squid.utils.CollectionUtils.MutSetHelper
import squid.utils.meta.{RuntimeUniverseHelpers => ruh}

import scala.collection.immutable.ListSet
import scala.collection.mutable

/*

TODO:
  - insert Stop nodes at the right places when reifying lambdas...

Possible imporvements:
  - an unrolling factor which needs parametrizing tokens and branches, allowing to push a stop past a branch of the same Val

*/
class Graph extends AST with GraphScheduling with GraphRewriting with CurryEncoding { graph =>
  
  override protected def freshNameImpl(n: Int) = "$"+n
  
  
  object CallId {
    private var curId = 0; def reset(): Unit = curId = 0
  }
  class CallId(val v: Val) {
    val uid: Int = CallId.curId alsoDo (CallId.curId += 1)
    //def uidstr: String = s"${v.name}$uid"
    def uidstr: String = s"$v$uid"
    override def toString: String = uidstr
  }
  
  //abstract class Node(val bound: Val) {
  abstract class Node {
    //val bound: Val
    val bound: Val = freshBoundVal(typ)
    def withBound(bound: Val): Node
    //def typ: TypeRep
    ////def dfn = bound
    def children: Iterator[Rep]
    //def iterator = graph.iterator(this)
    //def showGraph = graph.showGraph(this)
    ////def showFullGraph = graph.showGraph(this,true)
    //def showRep = graph.showRep(this)
    //def typ = bound.typ
    def typ: TypeRep
    def mkRep = new Rep(this)
  }
  class Rep(var node: Node) {
    ////val bound: Val = freshBoundVal(typ)
    //def typ = dfn.typ
    //def children = dfn.children
    def bound: Val = node.bound
    def showGraph = graph.showGraph(this)
    def showFullGraph = showGraph // TODO
    def eval = graph.eval(this)
    def typ: TypeRep = node.typ
    
    def fullString = toString // TODO
    
    override def toString = s"$node"
  }
  //case class ConcreteNode(dfn: Def) extends Node(freshBoundVal(dfn.typ)) {
  case class ConcreteNode(dfn: Def) extends Node {
    def typ: TypeRep = dfn.typ
    def children = dfn.children
    def withBound(_bound: Val): Node = new ConcreteNode(dfn) {
      override val bound = _bound
    }
    //def mkRep = new Rep(this)
    override def toString = s"$bound = $dfn"
  }
  //case class Call(cid: CallId, res: Rep) extends Node(freshBoundVal(res.typ)) {
  case class Call(cid: CallId, res: Rep) extends Node {
    def typ = res.typ
    def children: Iterator[Rep] = Iterator.single(res)
    def withBound(_bound: Val): Node = new Call(cid, res) {
      override val bound = _bound
    }
    override def toString = s"$bound = [$cid! ${res.bound}]"
  }
  //case class Branch(cid: CallId, lhs: Rep, rhs: Rep) extends Node(freshBoundVal(ruh.uni.lub(lhs.typ.tpe::rhs.typ.tpe::Nil))) {
  case class Branch(cid: CallId, lhs: Rep, rhs: Rep) extends Node {
    def typ: TypeRep = ruh.uni.lub(lhs.typ.tpe::rhs.typ.tpe::Nil)
    def children: Iterator[Rep] = Iterator(lhs,rhs)
    def withBound(_bound: Val): Node = new Branch(cid, lhs, rhs) {
      override val bound = _bound
    }
    override def toString = s"$bound = [$cid? ${lhs.bound} ¿ ${rhs.bound}]"
  }
  case class Stop(v: Val, res: Rep) extends Node {
    def typ = res.typ
    def children: Iterator[Rep] = Iterator.single(res)
    def withBound(_bound: Val): Node = new Stop(v,res) {
      override val bound = _bound
    }
    override def toString = s"$bound = [Ø ${res.bound}]"
  }
  
  //def dfn(r: Rep): Def = r.dfn
  def dfn(r: Rep): Def = r.bound
  //def rep(dfn: Def) = new Rep(dfn, dfn.children.toList)
  def rep(dfn: Def) = dfn match {
    case v: Val if reificationContext.contains(v) => reificationContext(v)
    case _ => new Rep(ConcreteNode(dfn))
  }
  //def simpleRep(dfn: Def): Rep = rep(dfn)
  def repType(r: Rep) = r.typ
  
  
  
  val reificationContext = mutable.Map.empty[Val,Rep]
  
  def letinImpl(bound: BoundVal, _value: Rep, mkBody: => Rep) = { 
    require(!reificationContext.contains(bound))
    val value = new Rep(_value.node.withBound(bound))
    try {
      reificationContext += bound -> value
      mkBody
    } finally reificationContext -= bound 
  }
  
  override def letin(bound: BoundVal, value: Rep, body: => Rep, bodyType: TypeRep) =
    letinImpl(bound,value,body)
  
  override def abs(param: BoundVal, body: => Rep): Rep = {
    val occ = param.toRep
    lambdaBound.put(param, occ)
    letinImpl(param, occ, super.abs(param, body))
  }
  
  //class MirrorVal(v: Val) extends BoundVal("@"+v.name)(v.typ,Nil)
  protected val lambdaBound = new java.util.WeakHashMap[Val,Rep]
  
  override def substituteVal(r: Rep, v: BoundVal, mkArg: => Rep): Rep = {
    val cid = new CallId(v)
    
    val occ = Option(lambdaBound.get(v)).getOrElse(???) // TODO B/E
    val newOcc = v.toRep
    lambdaBound.put(v,newOcc)
    
    val arg = mkArg
    val bran = Branch(cid, Stop(v,arg).mkRep, newOcc)
    occ.node = bran
    
    Call(cid, r).mkRep
  }
  
  
  def iterator(r: Rep): Iterator[Rep] = mkIterator(r)(false,mutable.HashSet.empty)
  def mkIterator(r: Rep)(implicit rev: Bool, done: mutable.HashSet[Rep]): Iterator[Rep] = done.setAndIfUnset(r, {
    Iterator.single(r) ++ r.node.children.flatMap(mkIterator)
  }, Iterator.empty)
  
  def showGraph(rep: Rep, full: Bool = false): String = s"$rep" + {
    val defsStr = iterator(rep).toList.distinct.filterNot(_ === rep).collect {
      // TODO use 'full'
      case r => s"\n\t$r;"
      //case r if full =>
      //  s"\n\t${r.bound} = ${r.boundTo.mkString(false,false)};"
      //case r @ Rep(ConcreteNode(d)) if !d.isSimple => s"\n\t${r.bound} = ${d};"
    }.mkString
    if (defsStr.isEmpty) "" else " where:" + defsStr
  }
  
  implicit class GraphDefOps(private val self: Def) {
    def isSimple = self match {
      //case _: SyntheticVal => false  // actually considered trivial?
      case _: LeafDef => true
      //case Bottom => true
      case _ => false
    }
  }
  
  
  override def prettyPrint(d: Def) = (new DefPrettyPrinter)(d)
  class DefPrettyPrinter(showInlineNames: Bool = true, showInlineCF:Bool = true) extends super.DefPrettyPrinter {
    val printed = mutable.Set.empty[Rep]
    override val showValTypes = false
    override val desugarLetBindings = false
    var curCol = Console.BLACK
    //override def apply(r: Rep): String = printed.setAndIfUnset(r, (r.boundTo match {
    //  case _ if !showInlineCF => super.apply(r.bound)
    //  case ConcreteNode(d) if !d.isSimple => super.apply(r.bound)
    //  case n => (if (showInlineNames) Debug.GREY +r.bound+":" + curCol else "")+apply(n)
    //}) alsoDo {printed -= r}, s"[RECURSIVE ${super.apply(r.bound)}]")
    //override def apply(d: Def): String = d match {
    //  case Bottom => "⊥"
    //  case MirrorVal(v) => s"<$v>"
    //  case _ => super.apply(d)
    //}
    //def apply(n: Node): String = n match {
    //  case Pass(cid, res) =>
    //    val col = colorOf(cid)
    //    s"$col⟦$cid⟧$curCol ${res |> apply}"
    //  case Call(cid, res) =>
    //    val col = colorOf(cid)
    //    s"$col⟦$cid$curCol ${res |> apply}$col⟧$curCol"
    //  case Arg(cid, res) =>
    //    val col = colorOf(cid)
    //    //s"$col$cid⟨⟩$curCol${res|>apply}"
    //    s"⟦$col$cid⟧$curCol${res|>apply}"
    //  case Branch(Condition(ops,cid), cbr, els) =>
    //    val oldCol = curCol
    //    curCol = colorOf(cid)
    //    //s"${cid}⟨${cbr |> apply}⟩$oldCol${curCol = oldCol; apply(els)}"
    //    s"(${ops.map{case(k,c)=>s"$k$c;"}.mkString}$curCol$cid ? ${cbr |> apply} ¿ $oldCol${curCol = oldCol; apply(els)})"
    //  case cn@ConcreteNode(v:Val) => apply(cn.mkRep)
    //  case ConcreteNode(d) => apply(d)
    //}
  }
  
  
  
}


trait GraphRewriting extends AST { graph: Graph =>
  
  // TODO
  type XCtx = Unit
  def newXCtx: XCtx = ()
  
}
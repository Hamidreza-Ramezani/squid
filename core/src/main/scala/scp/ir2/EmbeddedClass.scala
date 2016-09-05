package scp
package ir2

import utils._
import lang2._
import scp.utils.meta.{RuntimeUniverseHelpers => ruh}
import ruh.sru.{MethodSymbol => Mtd}

abstract class EmbeddedClass[B <: Base](val base: B) {
  import base._
  
  val defs: Map[Mtd, Lazy[SomeIR]]
  val parametrizedDefs: Map[Mtd, List[TypeRep] => SomeIR]
  
  def mtd(sym: Mtd) = defs get sym
  
  val Object: { val Defs: Any }
  val Class: { val Defs: Any }
  
}

abstract trait EmbeddedableClass {
  def embedIn(base: Base): EmbeddedClass[base.type]
}

/** Just a small class to help the IDE feel less confused about the @embed macro annotation... */
trait SquidObject extends ir2.EmbeddedableClass


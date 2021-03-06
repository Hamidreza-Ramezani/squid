// Copyright 2017 EPFL DATA Lab (data.epfl.ch)
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

package squid
package feature

class FreeVariables extends MyFunSuite {
  
  import TestDSL.Predef._
  
  test("Explicit Free Variables") {
    
    val x: Q[Int,{val x: Int}] = code"?x: Int"
    assert(x.rep match {
      case base.RepDef(base.Hole("x")) => true  // Note: no `base.Ascribe` node because ascriptions to the same type are removed
      case _ => false
    })
    
    val d = code"$x.toDouble" : Q[Double, {val x: Int}]
    
    val s = code"(?str: String) + $d" : Q[String, {val x: Int; val str: String}]
    
    val closed = code"(str: String) => (x: Int) => $s" : Q[String => Int => String, {}]
    val closed2 = code"(x: Int) => (str: String) => $s" : Q[Int => String => String, {}]
    
    assert(closed =~= code"(a: String) => (b: Int) => a + b.toDouble")
    assert(closed2 =~= code"(b: Int) => (a: String) => a + b.toDouble")
    
    
    assertDoesNotCompile(""" code"42: $$t" """) // Quasiquote Error: Unquoted type does not type check: not found: value t
    
  }
  
  test("Rep extraction") {
    hopefully(code"Some(?x:Int)".rep extractRep code"Some(42)".rep isDefined)
    hopefully(code"Some(42)".rep extractRep code"Some(?x:Int)".rep isEmpty)
  }
  
  test("Term Equivalence") {
    
    //val a = ir"($$x: Int)"
    //val b = ir"($$x: Int):Int"
    //println(a.rep extractRep b.rep, b.rep extractRep a.rep)
    
    assert(code"(?x: Int)" =~= code"(?x: Int)")
    assert(!(code"(?x: Int)" =~= code"(?y: Int)"))
    
    assert(code"(?x: Int)" =~= code"(?x: Int):Int")
    assert(!(code"(?x: Int)" =~= code"(?y: Int)+1"))
    assert(!(code"(?x: Int)" =~= code"(?y: String)"))
    
    assert(code"(?x: Int) + (?y: Int)" =~= code"(?x: Int) + (?y: Int)")
    
    assert(!(code"(?x: Int) + (?y: Int)" =~= code"(?y: Int) + (?x: Int)"))
    
  }
  
  test("Ascription and Hole Types are Checked") {
    import base.hole
    
    val N = typeRepOf[Nothing]
    
    hopefullyNot(code"?str:String" =~=  code"?str:Any")
    hopefullyNot(code"?str:String" =~= base.`internal Code`(hole("str", N)))
    
    hopefully(hole("str", N) =~=  hole("str", N))
    eqt( (hole("str", typeRepOf[Any]) extractRep hole("str", N)).get._1("str"), hole("str", N) )
    hopefullyNot(hole("str", N) =~=  hole("str", typeRepOf[Int]))
    hopefullyNot(hole("str", typeRepOf[String]) =~=  hole("str", typeRepOf[Int]))
    
  }
  
  
}























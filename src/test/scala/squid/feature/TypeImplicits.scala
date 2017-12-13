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

import utils._

class TypeImplicits extends MyFunSuite {
  import TestDSL.Predef._
  
  test("Using Existential Type Representations") {
    
    val typs = List[CodeType[_]](codeTypeOf[Int], codeTypeOf[Double], codeTypeOf[String])
    
    assert((typs map {
      case typ: CodeType[t] =>
        code"Option.empty[($typ,t)]"
    }, List(
      code"Option.empty[(Int,Int)]",
      code"Option.empty[(Double,Double)]",
      code"Option.empty[(String,String)]"
    )).zipped forall (_ =~= _))
    
    typs match {
      case (t0:CodeType[t1]) :: (t1:CodeType[t0]) :: _ :: Nil =>  // makes sure resolution is no more based on names
        code"Map.empty[$t0,$t1]" eqt code"Map.empty[Int,Double]"
        code"Map.empty[ t0, t1]" eqt code"Map.empty[Double,Int]"
      case _ => fail
    }
    
    // Note how here we get an existential type `_$1` inserted, but the QQ now keeps track of inserted types and finds the corresponding tree
    code"Map.empty[${typs.head},${typs.tail.head}]" eqt code"Map.empty[Int,Double]"
    
  }
  
  
}

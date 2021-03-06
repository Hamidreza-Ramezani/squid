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

package squid
package classlift

import squid.utils._
import squid.utils.shims._

class ClassLiftingTests extends MyFunSuite {
  import TestDSL.Predef._
  import TestDSL.TopLevel._
  
  test("MyClass") {
    
    val cls: ClassWithObject[MyClass] = MyClass.reflect(TestDSL)
    val obj = cls.companion.value
    
    cls.methods.find(_.symbol == TestDSL.methodSymbol[MyClass]("bar")) |>! {
      case Some(mtd) =>
        val v = mtd.vparams.head.head.asInstanceOf[Variable[Int]]
        mtd.body eqt code"$v + 1"
    }
    cls.methods.find(_.symbol == TestDSL.methodSymbol[MyClass]("foo")) |>! {
      case Some(mtd) =>
        val str = mtd.vparams.head.head.asInstanceOf[Variable[String]]
        mtd.body eqt code"${cls.self}.bar($str.length)"
    }
    obj.methods.find(_.symbol == TestDSL.methodSymbol[MyClass.type]("swap")) |>! {
      case Some(mtd) =>
        mtd.typeParams.head |> { implicit A =>
          val x = mtd.vparams.head.head.asInstanceOf[Variable[(A.Typ,A.Typ)]]
          val name = mtd.vparams.tail.head.head.asInstanceOf[Variable[Symbol]]
          mtd.body eqt code"$name -> $x.swap"
        }
    }
    
    assert(cls.methods.head.symbol.asMethodSymbol.owner.isClass)
    assert(obj.methods.head.symbol.asMethodSymbol.owner.isModuleClass)
    
  }
  
  test("OrphanObject") {
    
    val obj: ObjectWithoutClass[OrphanObject.type] = OrphanObject.reflect(TestDSL)
    
    obj.methods.find(_.symbol == TestDSL.methodSymbol[OrphanObject.type]("test")) |>! {
      case Some(mtd) =>
        mtd.typeParams.head |> { implicit A =>
          val a = mtd.vparams.head.head.asInstanceOf[Variable[A.Typ]]
          mtd.body eqt code"($a, $a)"
        }
    }
    
    assert(obj.methods.head.symbol.asMethodSymbol.owner.isModuleClass)
    
  }
  
  test("MyClass2") {
    
    val cls: ClassWithObject[MyClass2] = MyClass2.reflect(TestDSL)
    val obj = cls.companion.value
    
    obj.methods.find(_.symbol == TestDSL.methodSymbol[MyClass2.type]("testo")) |>! {
      case Some(mtd) =>
        val x = mtd.vparams.head.head.asInstanceOf[Variable[Int]]
        mtd.body eqt code"val m = new MyClass2; Some(m.mut + $x)"
    }
    
    assert(cls.methods.head.symbol.asMethodSymbol.owner.isClass)
    assert(obj.methods.head.symbol.asMethodSymbol.owner.isModuleClass)
    
    eqt(cls.fields.head.A, codeTypeOf[Int])
    
  }
  
}

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
package ir

import squid.lang.InspectableBase
import utils._
import utils.CollectionUtils._
import utils.meta.{RuntimeUniverseHelpers => ruh}

import scala.collection.mutable


/** 
  * TODO: more efficent online rewriting by pre-partitioning rules depending on what they can match!
  * 
  **/
trait AST extends InspectableBase with ScalaTyping with ASTReinterpreter with RuntimeSymbols with RuntimeSymbolsBase with ClassEmbedder with ASTHelpers { self =>
  
  val newExtractedBindersSemantics = true
  
  
  /* --- --- --- Required defs --- --- --- */
  
  def rep(dfn: Def): Rep
  def dfn(r: Rep): Def
  
  
  
  /* --- --- --- Provided defs --- --- --- */
  
  
  /** Invoked when the Rep is only transitory and not supposed to be kept around in the AST, as in for pattern matching a Def with QQs */
  def simpleRep(dfn: Def): Rep = rep(dfn)
  
  def readVal(v: BoundVal): Rep = rep(v)
  
  def hole(name: String, typ: TypeRep) = rep(Hole(name)(typ))
  def splicedHole(name: String, typ: TypeRep): Rep = rep(SplicedHole(name)(typ))
  
  def const(value: Any): Rep = rep(Constant(value))
  def bindVal(name: String, typ: TypeRep, annots: List[Annot]) = new BoundVal(name)(typ, annots)
  def freshBoundVal(typ: TypeRep) = BoundVal(freshName)(typ, Nil) alsoDo (varCount += 1)
  protected def freshNameImpl(n: Int) = "val$"+n
  protected final def freshName: String = freshNameImpl(varCount)
  private var varCount = 0
  
  /** AST does not implement `lambda` and only supports one-parameter lambdas. To encode multiparameter-lambdas, consider mixing in CurryEncoding */
  def abs(param: BoundVal, body: => Rep): Rep = rep(Abs(param, body)(lambdaType(param.typ::Nil, body.typ)))
  
  override def ascribe(self: Rep, typ: TypeRep): Rep = Ascribe.mk(self, typ).fold(self)(rep)
  
  override def tryInline(fun: Rep, arg: Rep)(retTp: TypeRep): Rep = fun match {
    case RepDef(a @ Abs(p,b)) => inline(p,b,arg)
    case _ => super.tryInline(fun,arg)(retTp)
  }
  override def tryInline2(fun: Rep, arg0: Rep, arg1: Rep)(retTp: TypeRep): Rep = fun match {
    case AbsN(x0::x1::Nil,b) => inline(x0,b,arg0) |> (inline(x1,_,arg1))
    case _ => super.tryInline2(fun,arg0,arg1)(retTp)
  }
  override def tryInline3(fun: Rep, arg0: Rep, arg1: Rep, arg2: Rep)(retTp: TypeRep): Rep = fun match {
    case AbsN(x0::x1::x2::Nil,b) => inline(x0,b,arg0) |> (inline(x1,_,arg1)) |> (inline(x2,_,arg2))
    case _ => super.tryInline3(fun,arg0,arg1,arg2)(retTp)
  }
  
  def newObject(tp: TypeRep): Rep = rep(NewObject(tp))
  def staticModule(fullName: String): Rep = rep({
    StaticModule(fullName: String)
  })
  def module(prefix: Rep, name: String, typ: TypeRep): Rep = rep(Module(prefix, name, typ))
  def methodApp(self: Rep, mtd: MtdSymbol, targs: List[TypeRep], argss: List[ArgList], tp: TypeRep): Rep =
    rep(MethodApp(self, mtd, targs, argss, tp))
  
  def byName(arg: => Rep): Rep = ByName(arg)
  // TODO
  // We encode thunks (by-name parameters) as functions from some dummy 'ThunkParam' to the result
  //def byName(arg: => Rep): Rep = dsl"(_: lib.ThunkParam) => ${Quote[A](arg)}".rep
  //def byName(arg: => Rep): Rep = ??? //lambda(Seq(freshBoundVal(typeRepOf[lib.ThunkParam])), arg)
  
  
  def inline(param: BoundVal, body: Rep, arg: Rep) = bottomUp(body) {
    case RepDef(`param`) => arg
    case r => r
  }
  
  override def substituteLazy(r: => Rep, defs: Map[String, () => Rep]): Rep = if (defs isEmpty) r else bottomUp(r) { r => dfn(r) match {
    case h @ Hole(n) => defs get n map (_()) getOrElse r
    case h @ SplicedHole(n) => defs get n map (_()) getOrElse r
    case _ => r
  }}
  override def substitute(r: => Rep, defs: Map[String, Rep]): Rep = if (defs isEmpty) r else bottomUp(r) { r => dfn(r) match {
    case h @ Hole(n) => defs getOrElse (n, r)
    case h @ SplicedHole(n) => defs getOrElse (n, r)
    case _ => r
  }}
  
  def mapDef(f: Def => Def)(r: Rep) = {
    val d = dfn(r)
    val newD = f(d)
    if (newD eq d) r else rep(newD)
  }
  
  def boundValUniqueName(bv: BoundVal): String = s"${bv.name}@${System.identityHashCode(bv).toHexString}"
  
  def extractVal(r: Rep): Option[BoundVal] = r |>? { case RepDef(bv:BoundVal) => bv }
  
  
  override def traverseTopDown(f: Rep => Unit)(r: Rep): Unit = traverse(f(_) thenReturn true)(r)
  //override def traverseBottomUp(f: Rep => Unit)(r: Rep): Unit = ??? // TODO
  
  
  def transformRep(r: Rep)(pre: Rep => Rep, post: Rep => Rep = identity): Rep = (new RepTransformer(pre,post))(r)
  
  //def transformOtherDef(d: Def)(pre: Rep => Rep, post: Rep => Rep): Def =
  //  lastWords(s"Not supported: transformation of $d")
  
  class RepTransformer(pre: Rep => Rep, post: Rep => Rep) extends SimpleTransformer {
    
    //def apply(r: Rep): Rep = r |> pre |> dfn |> apply |> rep |> post
    /* Optimized version that prevents recreating too many Rep's: */
    override def apply(_r: Rep): Rep = {
      //post(r |> mapDef(apply))
      try post(pre(_r) |> super.apply) |> ascribeIfNot(_r.typ) catch { // Q: semantics if `post` throws??
        case EarlyReturnAndContinueExc(cont) =>
          val r = nestDbg(cont(apply))
          debug(s"${Console.RED}Returned early and continued with:${Console.RESET} $r")
          r
      }
    }
    def ascribeIfNot(tp: TypeRep)(r: Rep) = {
      val wtp = tp.tpe.widen     // It's okay to transform a term of type Int(42) to, say, Int(43).
      val wrtp = r.typ.tpe.widen // This is because Scala's type system does not (yet?) treat these differently from Int (their widening)
      // A better and more comprehensive approach would be to distinguish the (tightest) type of a term and the type at which it is used
      // Currently the rewrite engine is unsound because one can match a term of type T1 with a hole typed Any and transform it to an unrelated T2 because T2 <: Any 
      if (wrtp =:= wtp) r
      else if (wrtp <:< wtp) ascribe(r,wtp)
      else System.err.println(s"Term of type $tp was rewritten to a term of type ${wrtp}, not a known subtype.") thenReturn r
    }
    
  }
  
  class SimpleTransformer {
    
    def apply(r: Rep): Rep = mapDef(apply)(r)
    
    def apply(d: Def): Def = {
      //println(s"Traversing $r")
      val rec: Rep => Rep = apply
      val ret = d match {
        // Special handling of redexes so that they are traversed argument-first (otherwise, it would be body-first)
        case app @ Apply(a @ RepDef(_: Abs),v) => // This is so that let-bindings are traversed in the expected order...
          val newV = rec(v)
          val newA = rec(a)
          val tp = app.typ
          if (newA eq a) if (newV eq v) d else Apply(a,newV,tp) else Apply(newA,newV,tp)
        case a @ Abs(p, b) =>
          val newB = rec(b)
          if (newB eq b) d else Abs(p, newB)(a.typ) // Note: we do not transform the parameter; it could lead to surprising behaviors (esp. in case of erroneous transform)
        case Ascribe(r,t) => 
          val newR = rec(r)
          if (newR eq r) d else Ascribe.mk(newR,t) getOrElse dfn(newR)
        case Hole(_) | SplicedHole(_) | NewObject(_) => d
        case StaticModule(fullName) => d
        case Module(pref, name, typ) =>
          val newPref = rec(pref)
          if (newPref eq pref) d else Module(newPref, name, typ)
        case MethodApp(self, mtd, targs, argss, tp) =>
          val newSelf = rec(self)
          var sameArgs = true
          def tr2 = (r: Rep) => {
            //if (r.asInstanceOf[ANF#Rep].uniqueId == 125)
            //  42
            val newR = rec(r)
            if (!(newR eq r))
              sameArgs = false
            newR
          }
          def trans(args: Args) = Args(args.reps map tr2: _*)
          val newArgss = argss map { // TODO use List.mapConserve in Args if possible?
            case as: Args => trans(as)
            case ArgsVarargs(as, vas) => ArgsVarargs(trans(as), trans(vas))
            case ArgsVarargSpliced(as, va) => ArgsVarargSpliced(trans(as), tr2(va))
          }
          if ((newSelf eq self) && sameArgs) d else MethodApp(newSelf, mtd, targs, newArgss, tp)
        case v: BoundVal => v
        case Constant(_) => d
        case cs: CrossStageValue => cs
        //case d => transformOtherDef(d)(pre,post)
        //case or: OtherRep => or.transform(f)
      }
      //println(s"Traversed $r, got $ret")
      ret
    }
    
  }
  class PartialTransformer(transformf: PartialTransformer => PartialFunction[Rep, Rep]) extends SimpleTransformer {
    val transform = transformf(this)
    override def apply(r: Rep): Rep = transform.applyOrElse(r, super.apply)
  }
  
  def bottomUp(r: Rep)(f: Rep => Rep): Rep = transformRep(r)(identity, f)
  def topDown(r: Rep)(f: Rep => Rep): Rep = transformRep(r)(f)
  
  //def bottomUpPartial(r: Rep)(f: PartialFunction[Rep, Rep]): Rep = bottomUp(r)(r => f applyOrElse (r, identity[Rep]))
  
  // Note that this will not wrap refined type in an upcast (it doesn't apply 'ascribeIfNot')
  def substituteVal(r: Rep, v: BoundVal, mkArg: => Rep): Rep = {
    val arg = Lazy(mkArg)
    val freeInArg = Lazy(freeVariables(arg.value)) // we can't do this early because of the lazy semantics of `substituteVal`
    def captureAvoiding(self: PartialTransformer, v2: Val, body: Rep) = {
      val v3 = bindVal(v2.name+"_", v2.typ, v2.annots)
      val newBody = self(substituteValFastUnhygienic(body, v2, v3 |> rep))
      Abs(v3, newBody)(r.typ) |> rep
    }
    new PartialTransformer(self => {
      case RepDef(`v`) => arg.value
      case r @ RepDef(Abs(`v`,_)) => r
      case r @ RepDef(Abs(v2,body)) if arg.computed => // if we can already look at the free variables, this is faster
        if (freeInArg.value.contains(v2)) captureAvoiding(self,v2,body)
        else {
          val newBody = self(body)
          if (newBody eq body) r else Abs(v2,newBody)(r.typ) |> rep
        }
      case r @ RepDef(Abs(v2,body)) =>
        assert(!arg.computed && !freeInArg.computed)
        val newBody = self(body) // need to try with 'body' in case it evaluates 'arg'
        if (arg.computed && freeInArg.value.contains(v2)) {
          captureAvoiding(self,v2,body)
          // ^ we need to rebuild the substitution to avoid mixing the legit occrrences witht he ones introduced by arg!!
        } else if (newBody eq body) r else Abs(v2,newBody)(r.typ) |> rep
    })(r)
  }
  protected def substituteValFastUnhygienic(r: Rep, v: BoundVal, arg: Rep): Rep = new PartialTransformer(self => {
    case RepDef(`v`) => arg
    case r @ RepDef(Abs(`v`,_)) => r
  })(r)
  
  
  
  protected def extract(xtor: Rep, xtee: Rep): Option[Extract] = dfn(xtor) extractImpl xtee
  
  //def spliceExtract(xtor: Rep, t: Args): Option[Extract] = ???
  protected def spliceExtract(xtor: Rep, args: Args): Option[Extract] = xtor match {
    case RepDef(SplicedHole(name)) => Some(Map(), Map(), Map(name -> args.reps))
    case RepDef(h @ Hole(name)) => // If we extract ($xs*) using ($xs:_*), we have to build a Seq in the object language and return it
      //val rep = methodApp(
      //  moduleObject("scala.collection.Seq", SimpleTypeRep(ru.typeOf[Seq.type])),
      //  loadSymbol(true, "scala.collection.Seq", "apply"),
      //  h.typ.asInstanceOf[ScalaTypeRep].targs.head :: Nil, // TODO check; B/E
      //  Args()(args.reps: _*) :: Nil, h.typ)
      //Some(Map(name -> rep), Map(), Map())
      //Seq(12)
      
      val tp = ruh.sru.lub(args.reps map (_.typ.tpe) toList)
      
      val rep = methodApp(
        staticModule("scala.collection.Seq"),
        loadMtdSymbol(
          //loadTypSymbol("scala.collection.Seq"),
          loadTypSymbol("scala.collection.generic.GenericCompanion"),
          "apply", None),
        tp :: Nil,
        Args()(args.reps: _*) :: Nil, staticTypeApp(loadTypSymbol("scala.collection.Seq"), tp :: Nil))
      Some(Map(name -> rep), Map(), Map())
    case _ => throw IRException(s"Trying to splice-extract with invalid extractor $xtor")
  }
  
  
  /**
  * Note: Works well with FVs (represented as holes),
  * since it checks that each extraction extracts exactly a hole with the same name
  */
  def repEq(a: Rep, b: Rep): Boolean = {
    val a_e_b = a extractRep b
    if (a_e_b.isEmpty) return false
    (a_e_b, b extractRep a) match {
      //case (Some((xs,xts)), Some((ys,yts))) => xs.keySet == ys.keySet && xts.keySet == yts.keySet
      case (Some((xs,xts,fxs)), Some((ys,yts,fys))) =>
        // Note: could move these out of the function body:
        val extractsHole: ((String, Rep)) => Boolean = {
          case (k: String, RepDef(Hole(name))) if k == name => true
          case _ => false
        }
        val extractsTypeHole: ((String, TypeRep)) => Boolean = {
          //case (k: String, TypeHoleRep(name)) if k == name => true // FIXME
          case _ => false
        }
        fxs.isEmpty && fys.isEmpty && // spliced term lists are only supposed to be present in extractor terms, which are not supposed to be compared
        (xs forall extractsHole) && (ys forall extractsHole) && (xts forall extractsTypeHole) && (yts forall extractsTypeHole)
      case _ => false
    }
  }

  
  case class Lowering(phases: Symbol*) extends ir.Lowering with SelfTransformer {
    val loweredPhases = phases.toSet
  }
  class Desugaring extends Lowering('Sugar)
  object Desugaring extends Desugaring with TopDownTransformer
  
  
  object Const extends ConstAPI {
    def unapply[T: CodeType](cde: AnyCode[T]): Option[T] = dfn(cde.rep) match {
      case cst @ Constant(v) if typLeq(cst.typ, codeTypeOf[T].rep) => Some(v.asInstanceOf[T])
      case _ => None
    }
  }
  
  
  protected def crossStage(value: Any, trep: TypeRep): Rep = value match {
    case () | _:Bool | _:Char | _:Short | _:Int  | _:Long  | _:Float  | _:Double | _:String | _:Class[_] => const(value)
    case _ => CrossStageValue(value, trep) |> rep
  }
  def extractCrossStage(r: Rep): Option[Any] = r |> dfn |>?? {
    //case c: ConstantLike => c.value
    // ^ this causes problems because `extractCrossStage` is used to separate cross-stage values from the rest, and we
    //   don't want random constants to be interpreted as cross-stage values in this case 
    case CrossStageValue(v, _) => Some(v)
    case Ascribe(r, _) => extractCrossStage(r)
  }
  
  
  
  /* --- --- --- Node Definitions --- --- --- */
  
  
  lazy val ExtractedBinderSym = loadTypSymbol(ruh.encodedTypeSymbol(ruh.sru.typeOf[squid.lib.ExtractedBinder].typeSymbol.asType))
  
  type Val = BoundVal
  // To do later: refactor this class for more convenience -- use a unique id in addition to the name
  case class BoundVal(name: String)(val typ: TypeRep, val annots: List[Annot]) extends Def {
    def isExtractedBinder = annots exists (_._1.tpe.typeSymbol === ExtractedBinderSym)
    def renew = new BoundVal(name+freshName)(typ,annots) alsoDo (varCount += 1)
    
    def toHole(model: BoundVal): Extract -> Hole = {
      val newName = model.name
      val extr: Extract =
        //if (model.annots exists (_._1.tpe.typeSymbol === ExtractedBinderSym)) (Map(newName -> rep(this)),Map(),Map())
        if (model isExtractedBinder) (Map(newName -> readVal(this)),Map(),Map())
        else EmptyExtract
      extr -> Hole(newName)(typ, Some(this), Some(model))
    }
    override def equals(that: Any) = that match { case that: AnyRef => this eq that  case _ => false }
    //override def hashCode(): Int = name.hashCode // should be inherited
  }
  def boundValType(bv: BoundVal) = bv.typ
  
  /**
  * In xtion, represents an extraction hole
  * In ction, represents a free variable
  * TODO Q: should holes really be pure?
  */
  // TODO streamline these defs – were changed because the old ones did not have the right hashMap/equals implems
  //case class Hole(name: String)(val typ: TypeRep, val originalSymbol: Option[BoundVal] = None, val matchedSymbol: Option[BoundVal] = None) extends NonTrivialDef
  //case class SplicedHole(name: String)(val typ: TypeRep) extends NonTrivialDef // should probably be a wrapper over Hole
  type Hole = HoleClass
  object Hole {
    def apply(name: String)(typ: TypeRep,originalSymbol: Option[BoundVal] = None, matchedSymbol: Option[BoundVal] = None) = HoleClass(name: String,typ: TypeRep)(originalSymbol,matchedSymbol)
    def unapply(x:Hole) = HoleClass.unapply(x) map (_._1)
  }
  case class HoleClass(name: String, typ: TypeRep)(val originalSymbol: Option[BoundVal] = None, val matchedSymbol: Option[BoundVal] = None) extends NonTrivialDef
  type SplicedHole = SplicedHoleClass
  object SplicedHole {
    def apply(name: String)(typ: TypeRep) = SplicedHoleClass(name: String,typ: TypeRep)
    def unapply(x:SplicedHole) = SplicedHoleClass.unapply(x) map (_._1)
  }
  case class SplicedHoleClass(name: String, typ: TypeRep) extends NonTrivialDef // should probably be a wrapper over Hole
  
  override def hopHole(name: String, typ: TypeRep, yes: List[List[Val]], no: List[Val]) = rep(new HOPHole(name, typ, yes, no))
  class HOPHole(name: String, typ: TypeRep, val yes: List[List[Val]], val no: List[Val]) extends HoleClass(name, typ)()
  
  sealed trait ConstantLike { val value: Any }
  
  case class Constant(value: Any) extends Def with ConstantLike {
    lazy val typ = value match {
      case () => TypeRep(ruh.Unit)
      //case null => TypeRep(ruh.Null) // Not necessary; Scala creates a constant type Null(null) -- note: that type is a strict subtype of Null...
      case cls: Class[_] => // Runtime classes are considered like constants, for some reason
        val tp = ruh.srum.classSymbol(cls).toType
        val clsSym = ruh.srum.classSymbol(cls.getClass)
        staticTypeApp(loadTypSymbol(ruh.encodedTypeSymbol(clsSym)), tp :: Nil)
      case _ => constType(value)
    }
  }
  
  // Note: would have probably been simpler to just give Constant a smart constructor/destructor and discriminate based on type...
  case class CrossStageValue protected(value: Any, typ: TypeRep) extends Def with ConstantLike
  
  case class Abs(param: BoundVal, body: Rep)(val typ: TypeRep) extends Def {
    def ptyp = param.typ
    //val typ = funType(ptyp, repType(body))
    
    def inline(arg: Rep) = bottomUpPartial(body) { //body withSymbol (param -> arg)
      case rep if dfn(rep) === `param` => arg
    }
    
  }
  
  case class Ascribe private(self: Rep, typ: TypeRep) extends Def {
    //require(!(self.typ =:= typ)) // in the future, re-enable this?
    //if (self.typ =:= typ) sys.error(s"Warning: annotating term $self of type ${self.typ} with equivalent type $typ")
    // ^ should never happened, as constructor is now private
  }
  object Ascribe {
    // Note: naming this `apply` works in Sala 2.12 but not 2.11 due to overloading with private case class ctor...
    def mk(self: Rep, typ: TypeRep): Option[Def] = if (self.typ =:= typ) None else self match {
      case RepDef(Ascribe(trueSelf, _)) => mk(trueSelf, typ) // Hopefully Scala's subtyping is transitive
      case _ => Some(new Ascribe(self, typ))
    }
  }
  
  case class NewObject(typ: TypeRep) extends NonTrivialDef
  
  case class StaticModule(fullName: String) extends Def {
    lazy val typ = staticModuleType(fullName)
  }
  
  case class MethodApp(self: Rep, sym: MtdSymbol, targs: List[TypeRep], argss: List[ArgList], typ: TypeRep) extends NonTrivialDef {
    
    lazy val phase = { // TODO cache annotations for each sym
      import ruh.sru._
      val annots =
        if (sym.isAccessor) // Scala stores a field's annotation not in its accessor, but in the associated private value of the same name plus a space...
          (sym.owner.typeSignature member TermName(sym.name+" ") optionIf (_ =/= NoSymbol) getOrElse sym.asMethodSymbol).annotations
        else sym.annotations
      annots.iterator map (_ tree) collectFirst {
        case q"new $tp(scala.Symbol.apply(${Literal(ruh.sru.Constant(name:String))}))" if tp.symbol.fullName == "squid.quasi.phase" =>
          Symbol(name)
      }
    }
    
  }
  case class Module(prefix: Rep, name: String, typ: TypeRep) extends Def
  
  sealed trait NonTrivialDef extends Def { override def isTrivial: Bool = false }
  sealed trait Def { // Q: why not make it a class with typ as param?
    val typ: TypeRep
    def isTrivial = true
    
    lazy val unboundVals = self.unboundVals(this)
    def isClosed = unboundVals.isEmpty
    
    def children: Iterator[Rep] = this match {
      case a @ Abs(p, b) => dfn(b).children
      case Ascribe(r,t) => Iterator(r)
      case Hole(_) | SplicedHole(_) | NewObject(_) | _:ConstantLike | (_:BoundVal) | StaticModule(_) => Iterator.empty
      case Module(pref, name, typ) => Iterator(pref) //dfn(pref).children
      case MethodApp(self, mtd, targs, argss, tp) => Iterator(self) ++ argss.flatMap(_.repsIt)
    }
    
    lazy val size: Int = 1 + children.map(dfn).map(_.size).sum
    
    def extractImpl(r: Rep): Option[Extract] = {
      //println(s"${this.show} << ${t.show}")
      //dbgs(s"${this} << ${r}")
      dbgs(s"${Console.BOLD}M${Console.RESET}  "+this);dbgs("<< "+r)
      //dbgs("   "+this);dbgs("<< "+r.show)
      //dbgs(s"${showRep(rep(this))} << ${showRep(r)}")  // may create mayhem, as showRep may use scalaTree, which uses a Reinterpreter that has quasiquote patterns!
      
      val d = dfn(r)
      val ret: Option[Extract] = nestDbg { (this, d) match {
          
        case (_, Ascribe(v,tp)) => // Note: even if 'this' is a Hole, it is needed for term equivalence to ignore ascriptions
          //mergeOpt(typ extract (tp, Covariant), extractImpl(v))
          /** ^ It should not matter what the matchee is ascribed to. We'd like `42` and `42:Any` to be equi-matchable */
          extractImpl(v)
          
        case (Ascribe(v,tp), _) =>
          mergeOpt(tp extract (d.typ, Covariant), v.extract(r))
          
        case (h:HOPHole, _) =>
          typ extract (d.typ, Covariant) flatMap { e =>
            val List(ps) = h.yes // FIXME: generalize
            // This part is a little tricky: we have no easy way to retrieve the types of HOPV parameters...
            // if some of these parameters do not even appear in the term, we give them type Any, which is sound thanks 
            // to function parameter contravariance.
            val psMap = mutable.Map[Val,Val]().withDefault(hs => hs.copy()(typ = Predef.implicitType[Any].rep,hs.annots))
            val rebound = bottomUpPartial(r) {
              case RepDef(h0:Hole) if h0.matchedSymbol exists (ps contains _) => 
                psMap += h0.matchedSymbol.get -> h0.originalSymbol.get
                readVal(h0.originalSymbol.get)
              case RepDef(h0:Hole) if h0.matchedSymbol exists (h.no contains _) =>
                return None
            }
            val f = lambda(ps map psMap, rebound)
            merge(e, repExtract(h.name -> f))
          }
          
        case (Hole(name), _) => // Note: will also extract holes... which is important to asses open term equivalence
          // Note: it is not necessary to replace 'extruded' symbols here, since we use Hole's to represent free variables (see case for Abs)
          typ extract (d.typ, Covariant) flatMap { merge(_, (Map(name -> r), Map(), Map())) }
          
        case (bv @ BoundVal(n1), h @ Hole(n2)) if n1 == n2 && h.matchedSymbol == Some(bv) => // This is needed when we do function matching (see case for Abs); n2 is to be seen as a FV
          //Some(EmptyExtract) // I thought the types could not be wrong here (case for Abs checks parameter types)
          // Before we had `matchedSymbol`, matching extracted binders could cause problems if we did not check the type; it's probably not necessary anymore
          typ.extract(d.typ, Covariant)
          
        // Q: really still needed?
        case (bv: BoundVal, h: Hole) if h.originalSymbol exists (_ === bv) => // This is needed when we match an extracted binder with a hole that comes from that binder
          Some(EmptyExtract)
          
        case (_, Hole(_)) => None
          
        case (v1: BoundVal, v2: BoundVal) =>
          // Bound variables are never supposed to be matched;
          // if we match a binder, we'll replace the bound variable with a free variable first
          //throw new AssertionError("Bound variables are not supposed to be matched.")
          
          // actually now with extracted bindings they may be...
          if (v1 == v2) Some(EmptyExtract) else None // check same type?
          
          //if (v1.name == v2.name) Some(EmptyExtract) else None // check same type?
          
        /** It may be okay to consider constants `1` (Int) and `1.0` (Double) equivalent;
          * however, it's probably a good idea to ensure transitivity of the match-relation, so that (a <~ b and b <~ c) => a <~ c
          * but we'd have 1 <~ 1.0 and 1.0 <~ ($x:Double) but not 1 <~ ($x:Double) */
        //case (Constant(v1), Constant(v2)) => if (v1 == v2) Some(EmptyExtract) else None
        case (Constant(v1), Constant(v2)) => mergeOpt(extractType(typ, d.typ, Covariant), if (v1 == v2) Some(EmptyExtract) else None)
          
        case (a1: Abs, a2: Abs) =>
          // The body of the matched function is recreated with a *free variable* in place of the parameter, and then matched with the
          // body of the matcher, so what the matcher extracts contains potentially (safely) extruded variables.
          for {
            pt <- a1.ptyp extract (a2.ptyp, Contravariant)
            //b <- a1.body.extract(a2.inline(rep(a2.param.toHole(a1.param.name))))
            (hExtr,h) = a2.param.toHole(a1.param)
            b <-
              if (a1.param.isExtractedBinder && newExtractedBindersSemantics) 
                   a1.inline(a2.param|>readVal).extract(a2.body) flatMap (merge(hExtr, _))
                   // ^ (generates warning cf type change)  TODO: thread extraction context with var mapping instead
              else a1.body.extract(a2.inline(rep(h))) flatMap (merge(hExtr, _))
                   // ^ 'a2.param.toHole' is a free variable that 'retains' the memory that it was bound to 'a2.param'
            m <- merge(pt, b)
          } yield m
          
        case (StaticModule(fullName1), StaticModule(fullName2)) if fullName1 == fullName2 =>
          Some(EmptyExtract) // Note: not necessary to test the types: object with identical paths should be identical

        case Module(pref0, name0, tp0) -> Module(pref1, name1, tp1) =>
          // Note: if prefixes are matchable, module types should be fine
          // If prefixes are different _but_ type is the same, then it should be the same module!
          // TODO also cross-test with ModuleObject, and ideally later MethodApp... 
          pref0 extract pref1 flatMap If(name0 == name1) orElse extractType(tp0,tp1,Invariant)
          
        case (NewObject(tp1), NewObject(tp2)) => tp1 extract (tp2, Covariant)
          
        case (MethodApp(self1,mtd1,targs1,args1,tp1), MethodApp(self2,mtd2,targs2,args2,tp2))
          //if mtd1 == mtd2
          //if {val r = mtd1 == mtd2; debug(s"Symbol: ${mtd1.fullName} ${if (r) "===" else "=/="} ${mtd2.fullName}"); r}
          if mtd1 === mtd2 || { debug(s"Symbol: ${mtd1.fullName} =/= ${mtd2.fullName}"); false }
        =>
          assert(args1.size == args2.size, s"Inconsistent number of argument lists for method $mtd1: $args1 and $args2")
          assert(targs1.size == targs2.size, s"Inconsistent number of type arguments for method $mtd1: $targs1 and $targs2")
          
          for {
            s <- self1 extract self2
            t <- {
              /** The following used to end with '... extract (b, Variance of p.asType)'.
                * However, method type parameters seem to always be tagged as invariant.
                * This was kind of restrictive. For example, you could not match apply functions like "Seq()" with "Seq[Any]()"
                * We now match method type parameters covariantly, although I'm not sure it is sound. At least the fact we
                * now also match the *returned types* prevents obvious unsoundness sources. [EDIT: we no longer do]
                * See also the relevant comments in squid/src/test/scala/squid/functional/Soundness.scala */
              //mergeAll( (targs1 zip targs2 zip mtd1.typeParams) map { case ((a,b),p) => a extract (b, Covariant) } )
              mergeAll( (targs1 zip targs2) map { case (a,b) => a extract (b, Covariant) } )
            }
            a <- mergeAll( (args1 zip args2) map { case (as,bs) => extractArgList(as, bs) } )  //oh_and print("[Args:] ") and println
            
            /** It should not be necessary to match return types, knowing that we already match all term and type arguments.
              * On the other hand, doing so would break legitimate things like (()=>42).apply():Any =~= (()=>42:Any).apply(),
              * in which case the return type of .apply is Int in the former and Any in the latter, but everything else is equivalent */
            //rt <- tp1 extract (tp2, Covariant)  //oh_and print("[RetType:] ") and println
            rt = EmptyExtract
            
            m0 <- merge(s, t)
            m1 <- merge(m0, a)
            m2 <- merge(m1, rt)
          } yield m2
          
        case (a:ConstantLike) -> (b:ConstantLike) if a.value === b.value => Some(EmptyExtract)
          
        //  // TODO?
        //case (or: OtherRep, r) => or extractRep r
        //case (r, or: OtherRep) => or getExtractedBy r
          
        case _ => None
      }
      } // closing nestDbg
      
      //println(s">> ${r map {case(mv,mt,msv) => (mv mapValues (_ show), mt, msv mapValues (_ map (_ show)))}}")
      dbgs(s">> $ret")
      ret
    }
    
    override def toString = prettyPrint(this)
  }
  
  object RepDef {
    def unapply(x: Rep) = Some(dfn(x))
  }
  
  
  
  
  
  object Typed { def unapply(r: Def): Some[(Def, TypeRep)] = Some(r,r.typ) }
  
}












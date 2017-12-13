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
package quasi

import utils._
import MacroUtils._
import utils.CollectionUtils._
import squid.lang.Base

import collection.mutable
import scala.reflect.macros.whitebox
import scala.reflect.macros.blackbox


object QuasiMacros {
  val deprecated_qqInterpolators = Set("ir", "dbg_ir")
  val qqInterpolators = deprecated_qqInterpolators ++ Set("code", "dbg_code", "c")
}
class QuasiMacros(val c: whitebox.Context) {
  import c.universe._
  import QuasiMacros._
  
  //val debug = { val mc = MacroDebugger(c); mc[NoDebug] } // always debug
  val debug = { val mc = MacroDebugger(c); mc[MacroDebug] } // will cut dbg unless MacroUtils.DebugLevel <: MacroDebug or the macro application symbol has @MacroSetting(debug = true)
  
  object Helpers extends {val uni: c.universe.type = c.universe} with meta.UniverseHelpers[c.universe.type] with ScopeAnalyser[c.universe.type]
  import Helpers._
  
  def mkConfig(Config: Type): QuasiConfig = {
    val ctor = Config.member(termNames.CONSTRUCTOR)
    
    if (NoSymbol === ctor)
      throw QuasiException(s"Could not find constructor on type $Config")
    
    if (ctor.alternatives.size =/= 1)
      throw QuasiException(s"More than one constructor on type $Config")
    
    // TODO check that ctor has unique param of 'whitebox.Context'
    
    val cm = srum.reflectClass(Config.typeSymbol.asClass.asInstanceOf[sru.ClassSymbol])
    val mm = cm.reflectConstructor(ctor.asMethod.asInstanceOf[sru.MethodSymbol])
    
    mm(c).asInstanceOf[QuasiConfig]
  }
  
  /** Generates a proper macro abort/error if a quasi or embedding exception is raised,
    * unless debugging is on (in which case it is often useful to see the stack trace) */
  def wrapError[T](code: => T): T = try code catch {
    case e: Throwable =>
      val (err, report) = e match {
        case QuasiException(msg) => "Quasiquote Error: "+msg -> true
        case EmbeddingException(msg) => "Embedding Error: "+msg -> true
        case e => e.getMessage -> false
      }
      if (debug.debugOptionEnabled) {
        debug("Macro failed with: "+e)
        //debug(e.getStackTrace mkString "\n")
        throw e
      }
      else if (report) c.abort(c.enclosingPosition, err)
      else {
        c.warning(c.enclosingPosition, "Macro failed with: "+e)
        throw e
      }
  }
  
  def deprecated(msg: String, since: String, disableOnImplicit: Type = NoType, warnInMacros: Bool = false) =
    if (disableOnImplicit == NoType || c.inferImplicitValue(disableOnImplicit).isEmpty)
    if (warnInMacros || !c.enclosingPosition.toString.startsWith("source-<macro>")) // don't warn if used within a macro (such as `assertCompiles`)
      c.warning(c.enclosingPosition, s"$msg (since $since)")
  
  lazy val `use of ir instead of code` = typeOf[Warnings.`use of ir instead of code`.type]
  
  def forward$(q: Tree*): Tree = c.macroApplication match {
    case q"$qc.$$[$t,$c](..$code)" => q"$qc.qcbase.$$[$t,$c](..$code)"
  }
  def forward$2(q: Tree): Tree = c.macroApplication match {
    case q"$qc.$$[$t,$s,$c]($code)" => q"$qc.qcbase.$$[$t,$s,$c]($code)"
  }
  def forward$$(name: Tree): Tree = {
    deprecated("The `$$[T]('x)` free variable syntax is deprecated; use syntax `(?x:T)` instead.", "0.2.0")
    c.macroApplication match {
      case q"$qc.$$$$[$t]($n)" => q"$qc.qcbase.$$$$[$t]($n)"
      case q"$qc.?[$t]($n)" => q"$qc.qcbase.$$$$[$t]($n)"
    }
  }
  
  def isQQInterpolators(str: String) = {
    if (deprecated_qqInterpolators(str)) 
      deprecated(s"Use the `code` quasiquote/quasicode interpolator instead of the deprecated `$str`.", "0.2.0", `use of ir instead of code`)
    qqInterpolators(str)
  }
  
  lazy val SubstituteVarargSym = typeOf[QuasiBase].member(TypeName("__*").encodedName)
  //lazy val SubstituteVarargSym = symbolOf[QuasiBase#__*] // nope: gets 'trait Seq' (dealias) ...
  
  /** Note: quasicode currently does not support inserted types, but it could in principle (though it would need another syntax). */
  def quasicodeImpl[Config: c.WeakTypeTag](tree: c.Tree) = wrapError {
    
    debug(s"Typed[${tree.tpe}]: "+showCode(tree))
    
    val quasiBase = c.macroApplication match {
      case x @ q"$qc.${id @ TermName(name)}[$tp]($code)" if isQQInterpolators(name) =>
        debug("Found quasicode base: "+qc)
        qc
    }
    val base = c.typecheck(q"$quasiBase.qcbase")
    
    val config = mkConfig(weakTypeOf[Config])
    
    val code = tree //transform { case q"$qc.$$[$typ,$ctx]($t)" =>  }
    //val code = tree transform { case q"$qc.$$[$typ,$ctx](..$xs)" if xs.size != 1 => ??? }
    
    object Embedder extends QuasiEmbedder[c.type](c)
    val res = Embedder(
      baseTree = base,
      rawTree = code,
      termScopeParam = Nil,
      config = config,
      unapply = None,
      typeSymbols = Map(),
      holeSymbols = Set(),
      holes = Seq(),
      splicedHoles = Set(),
      hopvHoles = Map(),
      termHoles = Set(),
      typeHoles = Set(),
      typedTree = code,
      typedTreeType = code.tpe,
      stmts = Nil,
      convNames = Set(),
      unquotedTypes = Seq()
    )
    
    debug("Generated:\n"+showCode(res))
    
    res: c.Tree
    //c.parse(showCode(res))
  }
  
  
  lazy val CodeTSym = symbolOf[QuasiBase#CodeType[_]]
  lazy val CodeSym = symbolOf[QuasiBase#Code[_,_]]
  lazy val AnyCodeSym = symbolOf[QuasiBase#AnyCode[_]]
  lazy val FunSym = symbolOf[_ => _]
  
  def asIR(tp: Type, typeOfBase: Type) = tp.baseType(CodeSym) |>? {
    case TypeRef(typeOfBase0, CodeSym, typ :: ctx :: Nil) if typeOfBase =:= typeOfBase0 => (typ, ctx)
  }
  class AsIR(typeOfBase: Type) { def unapply(x:Type) = asIR(x, typeOfBase) }
  
  def asCode(tp: Type, typeOfBase: Type) = tp.baseType(AnyCodeSym) |>? {
    case TypeRef(typeOfBase0, AnyCodeSym, typ :: Nil) if typeOfBase =:= typeOfBase0 => typ
  }
  class AsCode(typeOfBase: Type) { def unapply(x:Type) = asCode(x, typeOfBase) }
  
  object AsFun {
    def unapply(x:Type) = x.baseType(FunSym) match {
      case TypeRef(_, FunSym, t0 :: tr :: Nil) => Some(t0,tr)
      case _ => None
    }
  }
  
  
  def unapplyImpl[L: c.WeakTypeTag](scrutinee: c.Tree) = wrapError {
    import c.universe._
    
    val quasiBase = c.macroApplication match {
      case x @ q"$base.QuasiContext(scala.StringContext.apply(..$_)).${id @ TermName(name)}.unapply($_)"
      if isQQInterpolators(name)
      =>
        debug("Found xtion base: "+base)
        base
    }
    
    val base = c.typecheck(q"$quasiBase.base")
    
    /** [INV:Quasi:reptyp]: we only match Code[_] types */
    // TODO if in contextual mode, nake sure IR terms are used!
    //
    //if (asIR(scrutinee.tpe, base.tpe).isEmpty) {
    //  throw EmbeddingException(s"Cannot match type `${scrutinee.tpe}`, which is not a proper subtype of `$base.${IRSym.name}[_,_]`"
    //    +"\n\tTry matching { case x: IR[_,_] => ... } first.")
    if (asCode(scrutinee.tpe, base.tpe).isEmpty) {
      throw EmbeddingException(s"Cannot match type `${scrutinee.tpe}`, which is not a proper subtype of `$base.${AnyCodeSym.name}[_]`"
        +"\n\tTry matching { case x: Code[_] => ... } first.")
    }
    
    quasiquoteImpl[L](base, Some(scrutinee))
  }
  
  
  def applyImpl[Config: c.WeakTypeTag](inserted: c.Tree*) = wrapError {
    
    val quasiBase = c.macroApplication match {
      case x @ q"$base.QuasiContext(scala.StringContext.apply(..$_)).${id @ TermName(name)}.apply(..$_)"
      if isQQInterpolators(name)
      =>
        debug("Found ction base: "+base)
        base
    }
    
    val base = c.typecheck(q"$quasiBase.base")
    
    quasiquoteImpl[Config](base, None)
  }
  
  
  def quasiquoteImpl[Config: c.WeakTypeTag](base: Tree, scrutinee: Option[Tree]) = {
    
    val isUnapply = scrutinee.isDefined
    
    val config = mkConfig(weakTypeOf[Config])
    
    val builder = new quasi.PgrmBuilder[c.type](c)(isUnapply)
    
    var holes: List[(Either[TermName,TypeName], Tree)] = Nil // (Left(value-hole) | Right(type-hole), original-hole-tree)
    val splicedHoles = mutable.Set[TermName]()
    val hopvHoles = mutable.Map[TermName,List[List[TermName]]]()
    var typeBounds: List[(TypeName, EitherOrBoth[Tree,Tree])] = Nil
    
    // Keeps track of which holes still have not been found in the source code
    val remainingHoles = mutable.Set[TermName](builder.holes.keysIterator.toSeq: _*)
    
    var unquotedTypes = List[(TypeName, Type, Tree)]() // fresh name; type; type rep tree
    
    def unquoteType(name: TypeName, tp: Type, tree: Tree) = tp.baseType(CodeTSym) match {
      case TypeRef(tpbase,CodeTSym,tp::Nil) if tpbase =:= base.tpe =>
        unquotedTypes ::= ((name.toTypeName, tp, tree))
        tq"$tp"
      case TypeRef(_,_,_) => throw EmbeddingException(s"Cannot unquote type '$tp': it is not from base $base.")
      case _ => throw EmbeddingException(s"Cannot unquote type '$tp': it is not a CodeType[_].")
    }
    
    var hasStuckSemi = Option.empty[TermName]
    
    object AsCode extends AsIR(base.tpe)
    object AsAnyCode extends AsCode(base.tpe)
    
    def mkTermHole(name: TermName, followedBySplice: Boolean) = {
      val h = builder.holes(name)
      remainingHoles -= name
      
      //debug("HOLE: "+h)
      
      if (isUnapply) {
        
        val n = h.name filter (_.toString != "_") getOrElse c.freshName(TermName("ANON_HOLE")) toTermName;
        holes ::= Left(n) -> h.tree
        
        if (followedBySplice) {
          // This is to help Scala typecheck the spliced hole; 
          // if we don't and call $$ instead of $$_*, typechecking usually silently fails and makes <error> types, for some reason...
          q"$base.$$$$_*(${Symbol(n toString)}): _*"
        }
        else  {
          if (h.vararg) splicedHoles += n
          else h.tree match { case pq"$p @ __*" => splicedHoles += n   case _ => }
          if (splicedHoles(n)) q"$base.$$$$_*(${Symbol(n toString)}): _*"
          else
          q"$base.$$$$(${Symbol(n toString)})"
        }
        
      } else {
        h.tree match {
          //case t @ q"$_: _*" => q"$base.$$($t: _*)"  // not actually useful...
          case q"$t: _*" => q"$base.$$($t: _*)"
          //case q"$t: _*" => q"$base.$$($t: _*): _*" // Adding the _* so that typing does not think of this term as a Seq[T] (see vararg $'s type) -- EDIT: won't work, as ModEmb will interpret it as an object-language splice (which we don't want)
          case q"($t: $tp)" if (tp.tpe match { /** This is specifically for handling the {{{xs : __*}}} syntax (which is there as a complementary of the {{{xs @ __*}}} pattern) */
            case TypeRef(btp, sym, Nil) => (btp =:= base.tpe) && sym == SubstituteVarargSym
            case _ => false
          }) => q"$base.$$($t: _*)"
          case t if h.vararg => q"$base.$$($t: _*)"
            
          case t => 
            // Here we try to retrieve the IR type of the inserted code, so that we can generate a typed call to `$`
            // This is only necessary when we want a type coercion to happen during type checking of the shallow program;
            // (such as when we ascribe a term with a type and rely on pat-mat subtyping knowledge to apply the coercion)
            // if we don't do that, the expected type we want to coerce to will be propagated all the way inside the 
            // unquote, and the coercion would have to happen outside of the shallow program! (not generally possible)
            
            def dep = deprecated("Insertion of AnyCode terms is unsafe and deprecated.", "0.2.0")
            
            // TODO: also handle auto-lifted function types of greater arities...
            t.tpe match {
              case AsCode(typ, ctx) => q"$base.$$[$typ,$ctx]($t)"
              case AsFun(AsCode(t0,ctx0), AsCode(tr,ctxr)) => q"$base.$$[$t0,$tr,$ctx0 with $ctxr]($t)"
              case AsAnyCode(typ) => dep; q"$base.$$Code[$typ]($t)"
              case AsFun(AsAnyCode(t0), AsAnyCode(tr)) => dep; q"$base.$$Code[$t0,$tr]($t)"
              case _ => q"$base.$$($t)"
            }
            
        }
      }
    }
    
    val code = (builder.tree: Tree) transformRec( rec => {
      
      /** Note: value/parameters may be named "_", while anonymous function parameters are desugared to names of the form x$n
        * Anonymous function parameters are not a threat to soundness, so we do allow using them. */
      case ValDef(_, TermName(name), _, _) if isUnapply && (name == "_" /*|| (name startsWith "x$")*/) =>
        throw QuasiException("All extracted bindings should be named.")
        
      /** Extracted binder: adds corresponding hole and an annotation to tell the IR and QuasiEmbedded to extract this binder */
      case ValDef(mods, name, tpt, rhs) if isUnapply && builder.holes.contains(name) =>
        val hole = builder.holes(name)
        mkTermHole(name, false)
        val n = hole.tree match {
          case Bind(n, _) => n
          case _ => throw QuasiException(s"All extracted bindings must be named. In: $${${hole.tree}}")
        }
        val newMods = Modifiers(mods.flags, mods.privateWithin, q"new _root_.squid.lib.ExtractedBinder" :: mods.annotations)
        val r = ValDef(newMods, TermName(n.toString), rec(tpt), rec(rhs))
        r
        
      // This is to help Scala typecheck the spliced hole; if we don't and call $$ instead of $$_*, typechecking usually silently fails and makes <error> types, for some reason...
      case q"${Ident(name: TermName)}: _*" if isUnapply && builder.holes.contains(name) =>
        mkTermHole(name, true)
        
      case Ident(name: TermName) if builder.holes.contains(name) =>
        mkTermHole(name, false)
        
      // Identify and treat Higher-Order Pattern Variables (HOPV)
      case q"${Ident(name: TermName)}(...$argss)" if isUnapply && builder.holes.contains(name) =>
        val idents = argss map (_ map {
          case Ident(name:TermName) => name
          case e => throw EmbeddingException(s"Unexpected expression in higher-order pattern variable argument: ${showCode(e)}")
        })
        val hole = builder.holes(name)
        val n = hole.name filter (_.toString != "_") getOrElse (
          throw QuasiException("All higher-order holes should be named.") // Q: necessary restriction?
        ) toTermName;
        hopvHoles += n -> idents
        mkTermHole(name, false)
        
      // Interprets bounds on extracted types, like in: `case List[$t where (Null <:< t <:< AnyRef)]`:
      case tq"${Ident(name: TypeName)} where $bounds" if isUnapply && builder.holes.contains(name.toTermName) =>
        val HoleName = builder.holes(name.toTermName).name.get.toTypeName // FIXME
        bounds match {
          case tq"$lb <:< ${Ident(HoleName)}"         => typeBounds ::= HoleName -> First (lb   )
          case tq"${Ident(HoleName)} <:< $ub"         => typeBounds ::= HoleName -> Second(   ub)
          case tq"$lb <:< ${Ident(HoleName)} <:< $ub" => typeBounds ::= HoleName -> Both  (lb,ub)
          case _ => throw EmbeddingException(s"Illegal bounds specification shape: `${showCode(bounds)}`. " +
            s"It shoule be of the form: `LB <:< $HoleName` or `$HoleName <:< UB` or `LB <:< $HoleName <:< UB`.")
        }
        rec(Ident(name))
        
      case Ident(name: TypeName) if builder.holes.contains(name.toTermName) => // in case we have a hole in type position ('name' is a TypeName but 'holes' only uses TermNames)
        val hole = builder.holes(name.toTermName)
        remainingHoles -= name.toTermName
        if (hole.vararg) throw EmbeddingException(s"Varargs are not supported in type position.${showPosition(hole.tree.pos)}") // (for hole '${hole.tree}').")
        
        if (isUnapply) {
          val n = hole.name.filter(_.toString != "_")
            .getOrElse(throw QuasiException("All extracted types should be named.")).toTypeName // TODO B/E // TODO relax?
          holes ::= Right(n) -> hole.tree
          tq"$n"
        }
        else { // !isUnapply
          unquoteType(name.toTypeName, hole.tree.tpe, hole.tree)
        }
        
      //case t @ q"$$(..$args)" if unapply => throw EmbeddingException(s"Unsupported alternative unquoting syntax in unapply position: '$t'")
      case t @ q"$$(..$args)" => // alternative unquote syntax
        q"$base.$$(..$args)"
        // ^ TODO remove this syntax
        
      case t @ q"${Ident(tn: TermName)}?" => // better FV syntax, old version
        deprecated("The `x?` free variable syntax is deprecated; use syntax `?x` instead.", "0.2.0")
        if (!isUnapply) {
          holes ::= Left(tn) -> q"$tn" // holes in apply mode are interpreted as free variables
        }
        q"$base.$$$$(${Symbol(tn.toString)})"
        
      // Special case for when the question mark is stuck to a semicolon... as in ir"x?:Int"
      // Unfortunately, there is not much we can soundly do as the argument has been parsed as a term, not a type.
      // So we showCode it and reparse it as a type...
      case t @ q"${id @ Ident(tn: TermName)} ?: $wannabeType" =>
        hasStuckSemi = Some(tn)
        try rec(c.parse(s"$tn ? : "+showCode(wannabeType))) catch {
          case scala.reflect.macros.ParseException(pos, msg) => builder.parseError(msg)
        }
        
        
      case t @ Ident(name: TermName) if name.decodedName.toString.startsWith("$") => // escaped unquote syntax
        //q"open(${name.toString})"
        val bareName = name.toString.tail
        if (bareName.isEmpty) throw EmbeddingException(s"Empty escaped unquote name: '$$$t'")
        
        if (isUnapply) {
          q"$base.$$(${TermName(bareName)})" // escaped unquote in unapply mode does a normal unquote
        }
        else { // !isUnapply
          deprecated("The `$$x` free variable syntax is deprecated; use syntax `?x` instead.", "0.2.0")
          val tn = TermName(bareName)
          holes ::= Left(tn) -> q"$tn" // holes in apply mode are interpreted as free variables
          q"$base.$$$$(${Symbol(bareName)})"
        }
        
      case t @ Ident(name: TypeName) if name.decodedName.toString.startsWith("$") => // escaped type unquote syntax
        val bareName = name.toString.tail
        if (isUnapply) {
          // Note: here we want a type unquote, not a type hole!
          
          val typedTypeRef = c.typecheck(Ident(TermName(bareName)))
          unquoteType(TypeName(bareName), typedTypeRef.tpe, c.untypecheck(typedTypeRef))
          
        } else {
          throw EmbeddingException(s"Free type variables are not supported: '$$$t'")
        }
        
    })
    if (remainingHoles nonEmpty) {
      val missing = remainingHoles map builder.holes map (h => h.name map ("$"+_) getOrElse s"$${${showCode(h.tree)}}")
      // ^ Not displaying the tree when possible, because in apply mode, typechecked trees can be pretty ugly...
      
      throw QuasiException(s"Illegal hole position${if (missing.size > 1) "s" else ""} for: "+missing.mkString(", "))
    }
    
    object Embedder extends QuasiEmbedder[c.type](c)
    val res = try Embedder.applyQQ(base, code, holes.reverse map (_._1), splicedHoles, hopvHoles, typeBounds.toMap, unquotedTypes, scrutinee, config)
    catch {
      case e: EmbeddingException if hasStuckSemi.isDefined => 
        c.warning(c.enclosingPosition, s"It seems you tried to annotate free variable `${hasStuckSemi.get}` with `:`, " +
          "which may have been interpreted as operator `?:` -- " +
          "use a space to remove this ambiguity.")
        throw e
    }
    
    debug("Generated:\n"+showCode(res))
    
    res: c.Tree
    //c.parse(showCode(res)): c.Tree
  }
  
  
  
  def implicitTypeImpl[Config: c.WeakTypeTag, T: c.WeakTypeTag] = wrapError {
    val T = weakTypeOf[T]
    
    debug("Implicit for "+T)
    
    val config = mkConfig(weakTypeOf[Config])
    
    val quasiBase = c.macroApplication match {
      case q"$qc.dbg.implicitType[$tp]" =>
        debug("Found implicitType base: "+qc)
        qc
      case q"$qc.implicitType[$tp]" =>
        debug("Found implicitType base: "+qc)
        qc
    }
    val myBaseTree = c.typecheck(q"$quasiBase.base")
    
    val codeTree = config.embed(c)(myBaseTree, myBaseTree.tpe, new BaseUser[c.type](c) {
      def apply(b: Base)(insert: (macroContext.Tree, Map[String, b.BoundVal]) => b.Rep): b.Rep = {
        object QTE extends QuasiTypeEmbedder[macroContext.type, b.type](macroContext, b, str => debug(str)) {
          val helper = QuasiMacros.this.Helpers
          val baseTree = myBaseTree
          //def freshName(hint: String): TermName = TermName(c.freshName(hint))
        }
        object ME extends QTE.Impl
        ME.liftType(T).asInstanceOf[b.Rep] // TODO proper way to do that!
      }
    })
    
    val res = q"$myBaseTree.`internal CodeType`[$T]($codeTree)"
    
    debug("Generated: "+res)
    //if (debug.debugOptionEnabled) debug("Of Type: "+c.typecheck(res).tpe) // Makes a StackOverflow when type evidence macro stuff happen
    
    //codeTree: c.Tree
    res
  }
  
  
  def subsImpl[T: c.WeakTypeTag, C: c.WeakTypeTag](s: c.Tree) = {
    import c.universe._
    
    val T = weakTypeOf[T]
    val C = weakTypeOf[C]
    
    val (base -> quoted, typ -> ctx) = c.macroApplication match {
      case q"$b.IntermediateCodeOps[$t,$c]($q).subs[$_,$_]($_)" => (b -> q, t.tpe -> c.tpe)
      case q"$b.IntermediateCodeOps[$t,$c]($q).dbg_subs[$_,$_]($_)" => (b -> q, t.tpe -> c.tpe)
    }
    
    val name -> term = s match {
      case q"scala.this.Predef.ArrowAssoc[$_](scala.Symbol.apply(${Literal(Constant(name: String))})).->[$_]($term)" =>
        name -> term
      case _ => c.abort(s.pos, "Illegal syntax for `subs`; Expected: `term0.subs 'name -> term1`")
    }
    
    //debug(name, term)
    
    val (bases, vars) = bases_variables(ctx)
    
    val replacedVarTyp = vars find (_._1.toString === name) getOrElse
      c.abort(c.enclosingPosition, s"This term does not have a free variable named '$name' to substitute.") _2;
    
    if (!(T <:< replacedVarTyp)) c.abort(term.pos, s"Cannot substitute free variable `$name: $replacedVarTyp` with term of type `$T`")
    
    val outputCtx = {
      val (cbases, cvars) = bases_variables(C)
      mkContext(cbases ::: bases, (vars filter (_._1.toString =/= name)) ::: cvars)
    }
    
    debug(s"Output context: $outputCtx")
    
    val sanitizedTerm = 
      if (debug.debugOptionEnabled) term else untypeTreeShape(c.untypecheck(term))
      //term
      //c.untypecheck(term)
    val res = q"$base.`internal Code`[$typ,$outputCtx]($base.substituteLazy($quoted.rep, Map($name -> (() => ($sanitizedTerm:$base.Code[_,_]).rep))))"
    
    debug("Generated: "+showCode(res))
    
    res
  }
  
  
  
  
  
}

class QuasiBlackboxMacros(val ctx: blackbox.Context) extends QuasiMacros(ctx.asInstanceOf[whitebox.Context])




















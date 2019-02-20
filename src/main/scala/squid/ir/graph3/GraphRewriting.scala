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

import squid.ir.graph.CallId
import squid.utils._
import squid.utils.CollectionUtils.MutSetHelper
import squid.utils.CollectionUtils.IteratorHelper
import squid.utils.meta.{RuntimeUniverseHelpers => ruh}

import scala.collection.mutable
import scala.collection.immutable.ListSet

/*

Note:
  Under an Arg, we really can't make any assumptions, because arbitrary context may have been restored!
      ...that is, unless we actually saw the corresponding Call on the way...
  The best we can do is push the arg down...

TODO:
  Use analysis before doing the rewrites, to prune possible paths to explore

Note:
  It used to be unsound to match something in the body of a lambda, if that entailed wrapping it in control-flow
  As of [this commit], the problem should be fixed, by making substituteVal smarter and storing 'abs' in MirrorVal

*/
trait GraphRewriting extends AST { graph: Graph =>
  
  /*
  override def extractVal(r: Rep): Option[Val] = r.boundTo |>? {
    case ConcreteNode(MirrorVal(v)) => v
  }
  */
  override def extractVal(r: Rep): Option[Val] = r.node |>? {
    case ConcreteNode(v:Val) => v
  }
  
  type XCtx = GXCtx
  def newXCtx: XCtx = GXCtx.empty
  
  type Condition = (Control,CallId)
  
  // TODO make assumed/assumedNot as single assumptions ListSet?
  protected case class GXCtx(assumed: Set[Condition], assumedNot: Set[Condition], curCtrl: Control, valMap: Map[Val,Val], traverseBranches: Bool = true) {
    assert(!(assumed intersects assumedNot), s"${assumed} >< ${assumedNot}")
  }
  protected object GXCtx { def empty = GXCtx(Set.empty, Set.empty, Id, Map.empty) }
  
  //override def mapDef(f: Def => Def)(r: Rep): r.type = ???
  //override protected def mapRep(rec: Rep => Rep)(d: Def) = ???
  /*
  protected val transformed = mutable.Set.empty[(Rep,ListSet[Condition->Bool],List[Rep])]
  def alreadyTransformedBy(xtor: Rep, ge: GraphExtract): Bool = transformed contains ((xtor, ge.assumptions, ge.traversedReps))
  def rememberTransformedBy(xtor: Rep, ge: GraphExtract): Unit = transformed += ((xtor, ge.assumptions, ge.traversedReps))
  */
  protected val transformed = mutable.Set.empty[(Rep,/*ListSet[Condition->Bool],*/CCtx,List[Rep])]
  def alreadyTransformedBy(xtor: Rep, ge: GraphExtract)(implicit cctx: CCtx): Bool = transformed contains ((xtor, /*ge.assumptions,*/cctx, ge.traversedReps))
  def rememberTransformedBy(xtor: Rep, ge: GraphExtract)(implicit cctx: CCtx): Unit = transformed += ((xtor, /*ge.assumptions,*/cctx, ge.traversedReps))
  
  override def spliceExtract(xtor: Rep, args: Args)(implicit ctx: XCtx) = ??? // TODO
  override def extract(xtor: Rep, xtee: Rep)(implicit ctx: XCtx) =
    extractGraph(xtor,xtee)(GXCtx.empty.copy(traverseBranches = false), CCtx.empty).headOption map (_.extr)
  
  override def rewriteRep(xtor: Rep, xtee: Rep, code: Extract => Option[Rep]): Option[Rep] = rewriteRepCtx(xtor, xtee, code)(CCtx.empty)
  def rewriteRepCtx(xtor: Rep, xtee: Rep, code: Extract => Option[Rep])(implicit cctx: CCtx): Option[Rep] = {
    //println(s"rewriteRep $xtee  >>  $xtor")
    //println(s"already: ${transformed.map(r => s"\n\t${r._1.bound}, ${r._2}, ${r._3.map(_.bound)}")}")
    
    val matches = extractGraph(xtor, xtee)(GXCtx.empty,cctx) flatMap
      (_ merge (GraphExtract fromExtract repExtract(SCRUTINEE_KEY -> xtee)))
    
    //if (matches.nonEmpty) println(matches.size,matches.filterNot(alreadyTransformedBy(xtor,_)).size)
    
    //if (matches.nonEmpty) println(s"Matches for ${xtor.bound}:"+
    //  matches.map(ge => "\n"+(if (alreadyTransformedBy(xtor,ge)) "√ " else "✗ ")+ge+s"\n\t${ge.traversedReps.map(_.simpleString)} ${
    //    ge.assumptions}").mkString)
    
    //matches.iterator.flatMap { ge =>
    matches.filterNot(alreadyTransformedBy(xtor,_)).iterator.flatMap { ge =>
      //println(s"...considering $xtor << ${ge.traversedReps.map(_.simpleString)} --> ${ge.extr}")
      //println(s"...  ${ge.argsToRebuild} ${ge.callsToAvoid}")
      code(ge.extr) |>? {
        case Some(x0) =>
          /*
          println(s"rewriteRep $xtee  >>  $xtor")
          //println(s"...transforming ${xtor.simpleString} -> $x0")
          //println(s"...transforming ${xtor.simpleString} -> ${x0.showGraph}")
          println(s"...transforming ${xtee.simpleString} -> ${x0.showGraph}")
          println(s"...  ${ge.assumptions}")
          println(s"...  ${xtor.simpleString} :: ${ge.traversedReps.map(_.bound)}")
          */
          rememberTransformedBy(xtor,ge)
          //println(f: ${showEdges}")
          if (ge.assumptions.isEmpty) x0 else {
            val oldXtee = xtee.node.mkRep // alternatively, could do the rebind here?
            ge.assumptions.toList.reverse.foldRight(x0) {
              case (ctrl,cid)->true -> x => Branch(ctrl,cid,x,oldXtee).mkRep
              case (ctrl,cid)->false -> x => Branch(ctrl,cid,oldXtee,x).mkRep
            }
          }
      }
    }.headOption
    
  }
  
  // Q: do we need something like the old 'callsToAvoid'?
  protected case class GraphExtract(extr: Extract, traversedReps: List[Rep], assumptions: ListSet[Condition->Bool]) {
    def merge(that: GraphExtract): Option[GraphExtract] =
      //if ((argsToRebuild intersects that.callsToAvoid) || (that.argsToRebuild intersects callsToAvoid)) Stream.Empty
      //else 
      
      //graph.merge(extr,that.extr).map(e =>
      //  GraphExtract(e, traversedReps ++ that.traversedReps, assumptions ++ that.assumptions))
      graph.merge(extr,that.extr).flatMap(e =>
        GraphExtract(e, traversedReps ++ that.traversedReps, assumptions ++ that.assumptions) optionUnless
          assumptions.exists{case(c,b) => that.assumptions.contains((c,!b))}
      )
    def assuming(a: Condition) = copy(assumptions = assumptions + (a->true))
    def assumingNot(a: Condition) = copy(assumptions = assumptions + (a->false))
    def matching (r: Rep) = r.node match {
      case _: Box | _: Branch => this
      case _ => copy(traversedReps = r :: traversedReps)
    }
    override def toString = s"{${assumptions.mkString(",")}}\\\t${extr._1.getOrElse(SCRUTINEE_KEY,"")} ${
      (extr._1-SCRUTINEE_KEY).map(r => "\n\t "+r._1+" -> "+r._2).mkString}"
  }
  protected object GraphExtract {
    val empty: GraphExtract = GraphExtract(EmptyExtract, Nil, ListSet.empty)
    def fromExtract(e: Extract): GraphExtract = empty copy (extr = e)
  }
  
  // FIXME: make this traverse var-to-var bindings??! does it not already?
  protected def extractGraph(xtor: Rep, xtee: Rep)(implicit ctx: XCtx, cctx: CCtx): List[GraphExtract] = debug(s"Extract ${xtor} << $xtee") thenReturn nestDbg {
    import GraphExtract.fromExtract
    
    xtor -> xtee |> { // FIXME not matching Rep(Call...) ?!
        
      case Rep(ConcreteNode(Hole(name))) -> _ => for {
        typE <- xtor.typ.extract(xtee.typ, Covariant).toList
        r1 = xtee
        
        //() = println(s">>>  $r2  =/=  ${try removeArgs(ctx.assumedNotCalled)(r1) catch { case e => e}}")
        
        //r2 = ctx.curCtrl.foldLeft(r1) {
        //  case r -> (k -> cid) => Box(cid,r,k).mkRep
        //}
        r2 = Box.rep(ctx.curCtrl,r1)
        
        e <- merge(typE, repExtract(name -> r2))
      } yield GraphExtract fromExtract e
        /*
      //case _ -> Rep(Call(cid, res)) =>
      //case _ -> Rep(Arg(cid, res)) =>
      case _ -> Rep(Box(cid, res, k)) =>
        //println("BOX ",cid, res, k)
        extractGraph(xtor, res)(ctx.copy(curCtrl = (k,cid) :: ctx.curCtrl),cctx.withOp_?(k,cid).getOrElse(???)) // TODO upd cctx
        
      case _ -> Rep(Branch(cond, thn, els)) =>
        //println("BRANCH ",cond, thn, els, cctx)
        val newCond = Condition(ctx.curCtrl ++ cond.ops, cond.cid)
        hasCond_?(cond) match {
          case Some(true) => extractGraph(xtor, thn)(ctx.copy(assumed = ctx.assumed + newCond),cctx).map(_ assuming newCond)
          case Some(false) => extractGraph(xtor, els)(ctx.copy(assumedNot = ctx.assumedNot + newCond),cctx).map(_ assumingNot newCond)
          case None =>
            
            lastWords("I think this is never used; but could be useful if we want to rewrite incomplete graph fragments?")
            
            if (newCond.isAlwaysTrue) extractGraph(xtor, thn)
            else if (newCond.isAlwaysFalse) extractGraph(xtor, els)
              // ^ Q: sound?
            else (if (newCond in ctx.assumedNot) Nil else extractGraph(xtor, thn)(ctx.copy(assumed = ctx.assumed + newCond),cctx).map(_ assuming newCond)) ++
              (if (newCond in ctx.assumed) Nil else extractGraph(xtor, els)(ctx.copy(assumedNot = ctx.assumedNot + newCond),cctx).map(_ assumingNot newCond))
            }
        */
      case Rep(ConcreteNode(dxtor)) -> Rep(ConcreteNode(dxtee)) => dxtor -> dxtee match {
          
        case (_, Ascribe(v,tp)) => extractGraph(xtor,v)
          
        case (Ascribe(v,tp), _) =>
          for { a <- tp.extract(xtee.typ, Covariant).toList
                b <- extractGraph(v, xtee)
                m <- fromExtract(a) merge b } yield m
          
        case (h:HOPHole, _) => ??? // TODO
          
        ////case (_, MirrorVal(_)) => die // TODO?
        //case (_, MirrorVal(v)) => GraphExtract.empty optionIf (dxtor === v) toList
          
        case (_, Hole(_)) => die
          
        case VirtConst(vxtor) -> Constant(_) =>  // Note: if this does not match, we may still match an explicit usage of the const function...
          extractGraph(vxtor, xtee)
            //.map(ge => ge.copy(traversedReps = ge.traversedReps.tail)) // just to avoid duplicating the traversed rep in traversedReps
          
        case (Constant(v1), Constant(v2)) =>
          mergeOpt(extractType(xtor.typ, xtee.typ, Covariant), if (v1 == v2) Some(EmptyExtract) else None).map(fromExtract).toList
          
        case (a:ConstantLike) -> (b:ConstantLike) if a.value === b.value => GraphExtract.empty :: Nil
          
        //case (Hole(name), _) => // moved
          
        case (v1: BoundVal, v2: BoundVal) =>  // TODO implement other schemes for matching variables... cf. extractImpl
          // Q: check same type?
          if (
             v1 == v2 // Q: really legit?
          || ctx.valMap.get(v1).contains(v2)
          ) EmptyExtract |> fromExtract |> Nil.:: else Nil
          
        case (a1: Abs, a2: Abs) =>
          require(a1.param.isExtractedBinder, s"alternative not implemented yet")
          for {
            pt <- a1.ptyp.extract(a2.ptyp, Contravariant).toList //map fromExtract
            /// *
            
            (hExtr,h) = a2.param.toHole(a1.param)
            //(hExtr,h) = ((Map(a1.param.name -> new Rep(lambdaBound.get(a2.param))),Map(),Map()):Extract) -> Hole(a2.param.name)(a2.param.typ, None, None)
            // ^ Essentially the same, but explicitly makes a Rep to the occurrence; this should now be handled by rep which is called by readVal in toHole
            
            //m <- mergeGraph(pt, hExtr |> fromExtract)
            m <- merge(pt, hExtr).toList
            b <- extractGraph(a1.body, a2.body)(ctx.copy(valMap = ctx.valMap + (a1.param -> a2.param)),cctx)
            m <- m |> fromExtract merge b
            // * /
            /* // Old way of making sure a pass node is inserted; we now do it with smarter substituteVal and storing 'abs' in MirrorVal
            a2p -> a2b = if (ctx.curOps.isEmpty) a2.param -> a2.body else {
              /* This is to temporarily sove the unsoundness with extracting lambda bodies while wrapping them in
                 control-flow nodes; it only solves the case where the lambda's body is matched as is (with no further
                 inner patterns) – otherwise we'd need to _always_ do this, even when `ctx.curOps.isEmpty`.
                 This is kind of wasteful and will quickly pollute the graph; ideally
                 we should only do it if necessary, and revert it otherwise (but how?) */
              val v = a2.param.copy(name = a2.param.name+"'"+freshVarCount)()
              //println(s"NEW ${a2.param} -> $v IN ${a2.body.showFullGraph}")
              val occ = rep(new MirrorVal(v)) // we have to introduce a new lambda-bound-like variable
              lambdaBound.put(v, occ.bound)
              val bod = substituteVal(a2.body,a2.param,occ)
              //println(s"BOD ${bod.showFullGraph}")
              v -> bod
            }
            (hExtr,h) = a2p.toHole(a1.param)
            m <- merge(pt, hExtr).toList
            b <- extractGraph(a1.body, a2b)(ctx.copy(valMap = ctx.valMap + (a1.param -> a2p)))
            m <- m |> fromExtract merge b
            */
          } yield m
          
        case (StaticModule(fullName1), StaticModule(fullName2)) if fullName1 == fullName2 =>
          fromExtract(EmptyExtract) :: Nil
          
        case Module(pref0, name0, tp0) -> Module(pref1, name1, tp1) =>
          extractGraph(pref0,pref1).flatMap(_ optionIf name0 == name1) ++ extractType(tp0,tp1,Invariant).map(fromExtract).toList
          
        // TODO properly thread assumptions
        case (MethodApp(self1,mtd1,targs1,args1,tp1), MethodApp(self2,mtd2,targs2,args2,tp2))
          if mtd1 === mtd2 || { debug(s"Symbol: ${mtd1.fullName} =/= ${mtd2.fullName}"); false }
        =>
          assert(args1.size == args2.size, s"Inconsistent number of argument lists for method $mtd1: $args1 and $args2")
          assert(targs1.size == targs2.size, s"Inconsistent number of type arguments for method $mtd1: $targs1 and $targs2")
          for {
            s <- extractGraph(self1,self2)
            t <- mergeAll( (targs1 zip targs2) map { case (a,b) => a extract (b, Covariant) } ).toStream
            a <- mergeAllGraph( (args1 zip args2) map { case (as,bs) => extractGraphArgList(as, bs) } )
            rt = GraphExtract fromExtract EmptyExtract
            m0 <- s merge fromExtract(t)
            m1 <- m0 merge a
            m2 <- m1 merge rt
          } yield m2
          
        case (NewObject(tp1), NewObject(tp2)) => tp1 extract (tp2, Covariant) map fromExtract toList
          
        case _ => Nil
      }
      case Rep(Box(_,_)|Branch(_,_,_,_)) -> _ => die
      case _ => Nil
    } map (_ matching xtee)
    
  }
  
  protected def mergeAllGraph(as: TraversableOnce[List[GraphExtract]]): List[GraphExtract] = {
    if (as isEmpty) return GraphExtract.empty :: Nil
    val ite = as.toIterator
    var res = ite.next()
    while(ite.hasNext && res.nonEmpty) res = for { a <- res; b <- ite.next(); m <- a merge b } yield m
    res
  }
  protected def extractGraphArgList(self: ArgList, other: ArgList)(implicit ctx: GXCtx, cctx: CCtx): List[GraphExtract] = {
    def extractRelaxed(slf: Args, oth: Args): List[GraphExtract] = {
      import slf._
      if (reps.size != oth.reps.size) return Nil
      val args = (reps zip oth.reps) map { case (a,b) => extractGraph(a, b) }
      ((GraphExtract.empty :: Nil) /: args) {
        case (acc, a) => for (acc <- acc; a <- a; m <- acc merge a) yield m }
    }
    import self._
    (self, other) match {
      case (a0: Args, a1: Args) =>
        require(reps.size == other.reps.size)
        extractRelaxed(a0,a1)
      case (ArgsVarargs(a0, va0), ArgsVarargs(a1, va1)) => for {
        a <- extractGraphArgList(a0, a1)
        va <- extractRelaxed(va0,va1)
        m <- a merge va
      } yield m
      case (ArgsVarargSpliced(a0, va0), ArgsVarargSpliced(a1, va1)) => for {
        a <- extractGraphArgList(a0, a1)
        va <- extractGraph(va0, va1)
        m <- a merge va
      } yield m
      case (ArgsVarargSpliced(a0, va0), ArgsVarargs(a1, vas1)) => for { // case dsl"List($xs*)" can extract dsl"List(1,2,3)"
        a <- extractGraphArgList(a0, a1)
        va <- spliceExtractGraph(va0, vas1)
        m <- a merge va
      } yield m
      case _ => Nil
    }
  }
  def spliceExtractGraph(xtor: Rep, args: Args)(implicit ctx: GXCtx): List[GraphExtract] = GraphExtract.fromExtract(xtor match {
    case RepDef(SplicedHole(name)) => mkExtract()()(name -> args.reps)
    case RepDef(h @ Hole(name)) => // If we extract ($xs*) using ($xs:_*), we have to build a Seq in the object language and return it
      mkExtract(name -> mkSeq(args.reps))()()
    case _ => throw IRException(s"Trying to splice-extract with invalid extractor $xtor")
  }) :: Nil
  
  def simplifyGraph(rep: Rep): Unit = {
    /*
    val traversed = mutable.Set.empty[Rep]
    def rec(rep: Rep)(implicit cctx: CCtx): Unit = {
      def again = { traversed -= rep; rec(rep) }
      traversed.setAndIfUnset(rep, rep.boundTo match {
        case Branch(cond,thn,els) =>
          // FIXME code dup?
          if (cond.isAlwaysTrue) rebind(rep.bound, thn.boundTo) thenReturn again
          else if (cond.isAlwaysFalse) rebind(rep.bound, els.boundTo) thenReturn again
          else { rec(thn); rec(els) }
        case Box(cid,b @ Rep(Branch(Condition(ops,c),thn,els)),kind) =>
          //rebind(rep.bound, Box(cid,b,kind))
          rebind(rep.bound, Branch(Condition((kind,cid)::ops,c), Box(cid,thn,kind).mkRep, Box(cid,els,kind).mkRep))
          again
        case Box(cid,res,kind) => rec(res)
        case ConcreteNode(d) => d.children.foreach(rec)
      })
    }
    rec(rep)(CCtx.empty)
    */
    rep // FIXME
  }
  
  def rewriteSteps(tr: SimpleRuleBasedTransformer{val base: graph.type})(r: Rep): Option[Rep] = {
    
    //println(s"Before simpl: ${rep.showFullGraph}")
    simplifyGraph(r)
    //println(s"After simpl: ${rep.showFullGraph}")
    
    def tryThis(r: Rep)(implicit cctx: CCtx): Option[Rep] = {
      val oldBound = r.bound
      tr.rules.iterator.flatMap(rule => rewriteRepCtx(rule._1,r,rule._2) also_? {
        case Some(res) =>
          if (r.bound =/= oldBound) println(s"!!! ${r.bound} =/= ${oldBound}")
          println(s" ${r}  =>  $res")
          //assert(!res.boundTo.isInstanceOf[ConcreteNode] || !res.boundTo.asInstanceOf[ConcreteNode].dfn.isInstanceOf[Abs])
          assert(!r.node.isInstanceOf[ConcreteNode] || !r.node.asInstanceOf[ConcreteNode].dfn.isInstanceOf[Abs]) // not sure this is useful/correct(?)
          
          //rebind(r.bound, res.node)
          // ^ Basically duplicates the def; it's often fine as it will be a fresh Rep wrapping a Def created by the rewrite rule;
          //   but in principle it could really be any existing Rep too!
          //   Also, it breaks the unique lambda invariant.
          
          /*
          assert(edges.containsKey(res.bound))
          //rebind(r.bound, ConcreteNode(res.bound))
          // ^ kind of works, but then we get things that rewrite forever because definitions change names...
          //   maybe we could adapt the `alreadyTransformedBy` mechanism and make that work?
          
          // In this final alternative, we set to steal the Def (which is nice to avoid renaming) but make 'res' point
          // back at us so there is no duplication; also we have to take care of the lambda backpointer
          res.node |>? {
            case ConcreteNode(abs:Abs) =>
              val occ = Option(lambdaBound.get(abs.param)).getOrElse(???) // TODO B/E
              val mir = boundTo_!(occ).asInstanceOf[ConcreteNode].dfn.asInstanceOf[MirrorVal]
              assert(mir.abs === res)
              mir.abs = r
          }
          rebind(r.bound, res.node)
          rebind(res.bound, ConcreteNode(r.bound))
          */
          res.node |>? {
            case ConcreteNode(abs:Abs) =>
              val occ = Option(lambdaVariableBindings.get(abs.param)).getOrElse(???) // TODO B/E
              
              // FIXME port missing logic here!
              
              //val mir = boundTo_!(occ).asInstanceOf[ConcreteNode].dfn.asInstanceOf[MirrorVal]
              //assert(mir.abs === res)
              //mir.abs = r
          }
          //r.node = ConcreteNode(res.bound) // this would duplicate the node!
          r.node = res.node
          res.node = ConcreteNode(r.bound)
          
      //}).collectFirst{case Some(r)=>r}
      }).headOption
      
    }
    
    def rec(r: Rep,tried:Bool=false)(implicit cctx: CCtx): Option[Rep] = //println(s"Rec $r $cctx") thenReturn
    //r.boundTo match {
    r.node match {
      //case Box(cid,res,k) => tryThis(r) orElse rec(res)(cctx.withOp_?(k->cid).getOrElse(???)) // FIXME: probably useless (and wasteful)
      case Box(ctrl,res) => (if (tried) None else tryThis(r)) orElse rec(res,true)(withCtrl_?(ctrl).getOrElse(???))
      case Branch(ctrl,cid,thn,els) => if (hasCid(ctrl,cid)) rec(thn) else rec(els)
      case cn@ConcreteNode(d) => tryThis(r) orElse d.children.flatMap(rec(_)).headOption
    }
    rec(r)(CCtx.empty)
  }
  
  
  // TODO:
  //override protected def unapplyConst(rep: Rep, typ: TypeRep): Option[Any] = ???
  
}
package ppl.dsl.forge
package templates
package shallow

import java.io.{BufferedWriter, FileWriter, PrintWriter}
import scala.tools.nsc.io._
import scala.collection.mutable.ArrayBuffer
import scala.virtualization.lms.common._
import core._
import Utilities._
import shared.BaseGenDataStructures

trait ShallowGenOps extends ForgeCodeGenBase with BaseGenDataStructures {
  val IR: ForgeApplicationRunner with ForgeExp with ForgeOpsExp
  import IR._

  /**
   * Utility methods
   */
  def baseOpsCls(grp: Rep[DSLGroup]) = {
    // GenOverloadHack should be mixed in by package traits only
    "Base"
  }

  /**
   * Quoting for formatted code-gen
   */

  def inline(o: Rep[DSLOp], str: Exp[String], quoter: Exp[Any] => String = quote) = {
    var b = quoter(str)
    for (i <- 0 until o.args.length) {
      val name = o.args.apply(i).name
      b = b.replaceAllLiterally(quoter(quotedArg(name)), name)
      // allow named args to be referred to by position as well
      b = b.replaceAllLiterally(quoter(quotedArg(i)), name)
    }
    for (i <- 0 until o.implicitArgs.length) {
      val name = o.implicitArgs.apply(i).name
      // implicit args can only be quoted by name so there is no ambiguity
      b = b.replaceAllLiterally(quoter(quotedArg(name)), name)
    }
    for (i <- 0 until o.tpePars.length) {
      b = b.replaceAllLiterally(quoter(quotedTpe(i,o)), o.tpePars.apply(i).name)
    }
    b
  }

  override def makeTpeParsWithBounds(args: List[Rep[TypePar]]): String = {
    // def ctxBounds(a: Rep[TypePar]) = a.ctxBounds.map(_.name).filter(_ != "Manifest")
    def ctxBounds(a: Rep[TypePar]) = a.ctxBounds.map(_.name)
    if (args.length < 1) return ""
    val args2 = args.map { a => quote(a) + (if (!ctxBounds(a).isEmpty) ":" + ctxBounds(a).mkString(":") else "") }
    "[" + args2.mkString(",") + "]"
  }

  def replaceWildcards(s: String) = {
    var o = s
    o = s.replaceAll(qu, "\"")

    // splice in the quoted symbol. we use a wildcard instead of an expression tree here
    // because string interpolation does not have a corresponding IR node.
    while (o.contains(symMarker)) {
      val st = o.indexOf(symMarker)
      val end = o.indexOf(symMarker, st+1)
      val symNum: Int = o.slice(st+symMarker.length, end).toInt
      val sym = globalDefs.find(_.lhs.apply(0).id == symNum).get.lhs.apply(0)
      o = o.slice(0,st) + quoteLiteral(sym) + o.slice(end+symMarker.length,o.length)
    }
    o
  }

  override def quote(x: Exp[Any]) : String = x match {
    case Def(QuoteBlockResult(func,args,ret,captured)) if (isThunk(func.tpe)) => func.name
    case Def(QuoteBlockResult(func,args,ret,captured)) => func.name + "(" + replaceWildcards(captured.mkString(",")) + ")"
    case Const(s: String) if quoteLiterally => replaceWildcards(s) // don't add quotes
    case _ => super.quote(x)
  }

  /**
   * For dc_alloc. By convention, dc_alloc's return tpe par must be its last tpe par, if it has one.
   */
  def instAllocReturnTpe(o: Rep[DSLOp], colTpePar: Rep[DSLType], elemTpePar: Rep[DSLType]): List[Rep[DSLType]] = {
    // dc_alloc is context sensitive: if the first argument is a tpe parameter, we assume a type signature of [R,CR]. otherwise, we assume a signature of [_,R]
    // note that dc_alloc always takes exactly 2 arguments (a collection and a size). this is still a bit hokey.
    if (o.tpePars.length > 0) {
      val colTpe = o.args.apply(0).tpe
      if (isTpePar(colTpe)) List(elemTpePar,colTpePar) else o.tpePars.dropRight(1).map(p => if (p == colTpe.tpePars.apply(0)) colTpePar.tpePars.apply(0) else p) :+ elemTpePar
    }
    else Nil
  }

  /**
   * Overloading resolution
   */

  // if after converting Ts and Vars to Reps there are duplicates, remove them.
  def unique(ops: List[Rep[DSLOp]]) = uniqueMap(ops)._1

  def uniqueMap(ops: List[Rep[DSLOp]]) = {
    // we maintain a separate ArrayBuffer along with the map to retain order
    val filtered = scala.collection.mutable.ArrayBuffer[Rep[DSLOp]]()
    val canonicalMap = scala.collection.mutable.HashMap[String,Rep[DSLOp]]()
    // add to filtered only if canonical version doesn't already exist
    for (o <- ops) {
      val t = canonicalize(o)
      if (!canonicalMap.contains(t)) {
        filtered += o
        canonicalMap(t) = o
      }
    }
    (filtered.toList,canonicalMap)
  }

  def canonicalize(o: Rep[DSLOp]) = o.grp.name + o.name + makeOpArgsWithType(o) + makeOpImplicitArgs(o)

  // precomputed for performance
  lazy val allOps = OpsGrp.values.toList.flatMap(g => g.ops)
  lazy val allOpsCanonicalMap = uniqueMap(allOps)._2
  def canonical(o: Rep[DSLOp]): Rep[DSLOp] = allOpsCanonicalMap.getOrElse(canonicalize(o), err("no canonical version of " + o.name + " found"))

  // overload name clash resolution using implicit hack
  // we need overloads for both front-end signatures (e.g., "+", universal) as well as abstract methods (e.g. vector_plus, grp), but the actual overload implicit used may differ in each case.
  // problem: the local numbering of a signature can overlap with the same Overloaded# as one of the unique targets, causing an ambiguity
  // def nameClash(o1: Rep[DSLOp], o2: Rep[DSLOp]) = o1.name == o2.name && o1.args.length == o2.args.length && (o1.args.zip(o2.args).forall(t => getHkTpe(t._1.tpe).name == getHkTpe(t._2.tpe).name || (t._1.tpe.stage == future && t._2.tpe.stage == future)))
  def nameClash(o1: Rep[DSLOp], o2: Rep[DSLOp]) = o1.style == o2.style && o1.name == o2.name // forces a global numbering

  def nameClashesGrp(o: Rep[DSLOp]) = opsGrpOf(o).ops.filter(o2 => o.grp.name == o2.grp.name && nameClash(o,o2))

  def nameClashesUniversal(o: Rep[DSLOp]) = allOps.filter(o2 => nameClash(o,o2))

  def nameClashId(o: Rep[DSLOp], clasher: Rep[DSLOp] => List[Rep[DSLOp]] = nameClashesUniversal) = {
    val clashes = clasher(o)
    if (clashes.length > 1) (clashes.indexOf(o)+1).toString else ""
  }

  // refers to the back-end method signature
  // front-end overloads are added in an ad-hoc way right now, by always calling implicitArgsWithOverload
  def needOverload(o: Rep[DSLOp]) = {
    /*!Config.fastCompile &&*/ (!Labels.contains(o) && nameClashesGrp(o).length > 1)
  }

  // useCanonical should be false if we're referring to the front-end signature (e.g. '+') but true if we're
  // referring to the back-end signature (e.g. 'vector_plus')
  def implicitArgsWithOverload(o: Rep[DSLOp], useCanonical: Boolean = false) = {
    // SHALLOW
    // val o1 = if (useCanonical) canonical(o) else o
    // val i = nameClashId(o1)
    // if (i != "") {
    //   // redirect overloads can clash with regular overloads since they don't get separate abstract methods
    //   val overloadName = if (isRedirect(o)) "ROverload" else "Overload"
    //   o.implicitArgs :+ anyToImplicitArg(ephemeralTpe(overloadName + i, stage = now), o.implicitArgs.length)
    // }
    // else {
      o.implicitArgs diff (arg("__pos",MSourceContext))
    // }
  }


  /**
   * Op argument formatting
   */

  def simpleArgName(t: Rep[DSLArg]): String = t.name

  def makeArgs(args: List[Rep[DSLArg]], makeArgName: (Rep[DSLArg] => String) = simpleArgName, addParen: Boolean = true) = {
    if (args.length == 0 && !addParen) {
      ""
    }
    else {
      "(" + args.map(makeArgName).mkString(",") + ")"
    }
  }

  override def argify(a: Exp[DSLArg], typify: Exp[DSLType] => String = repify): String = a match {
    case Def(Arg(name, tpe@Def(FTpe(args,ret,freq)), Some(d))) => name + ": " + typify(tpe) + " = " + escape(d)
    case Def(Arg(name, tpe, Some(d))) => name + ": " + typify(tpe) + " = " + escape(d)
    case Def(Arg(name, tpe, None)) => name + ": " + typify(tpe)
  }

  def makeArgsWithType(args: List[Rep[DSLArg]], typify: Rep[DSLType] => String = repify, addParen: Boolean = true) = makeArgs(args, t => argify(t, typify), addParen)

  // def makeArgsWithNowType(args: List[Rep[DSLArg]], addParen: Boolean = true) = makeArgsWithType(args, repifySome, addParen)
  def makeArgsWithNowType(args: List[Rep[DSLArg]], addParen: Boolean = true) = makeArgsWithType(args, typifySome, addParen)

  def makeOpArgs(o: Rep[DSLOp], addParen: Boolean = true) = makeArgs(o.args, addParen = addParen)

  def makeOpFutureArgs(o: Rep[DSLOp], makeArgName: (Rep[DSLArg] => String)) = makeArgs(o.args, t => { val arg = makeArgName(t); if (t.tpe.stage == now && !isTpeInst(t.tpe)) "unit("+arg+")" else arg })

  def makeOpArgsWithType(o: Rep[DSLOp], typify: Rep[DSLType] => String = typify, addParen: Boolean = true) = makeArgsWithType(o.args, typify, addParen)

  def makeOpArgsWithNowType(o: Rep[DSLOp], addParen: Boolean = true) = makeArgsWithNowType(o.args, addParen)

  def makeFullArgs(o: Rep[DSLOp], makeArgs: Rep[DSLOp] => String) = {
    // we always pass implicit arguments explicitly (in practice, less issues arise this way)
    val implicitArgs = if (needOverload(o)) makeOpImplicitArgsWithOverload(o, useCanonical = true) else makeOpImplicitArgs(o)
    makeTpePars(o.tpePars) + makeArgs(o) + implicitArgs
  }


  /**
   * Op implicit argument formatting
   */

  // untyped implicit args
  def makeImplicitCtxBounds(tpePars: List[Rep[TypePar]]) = {
    tpePars.flatMap(a => a.ctxBounds.map(b => "implicitly["+b.name+"["+quote(a)+"]]")).mkString(",")
  }

  def makeOpImplicitCtxBounds(o: Rep[DSLOp]) = makeImplicitCtxBounds(o.tpePars)

  def makeImplicitArgs(implicitArgs: List[Rep[DSLArg]], ctxBoundsStr: String = "") = {
    // ctxBounds must come before regular implicits
    val implArgs = implicitArgs diff (arg("__pos",MSourceContext))
    val ctxBounds2 = if (ctxBoundsStr == "") "" else ctxBoundsStr+","
    if (implArgs.length > 0) "(" + ctxBounds2 + implArgs.map(quote).mkString(",") + ")"
    else ""
  }

  def makeOpImplicitArgs(o: Rep[DSLOp]) = makeImplicitArgs(o.implicitArgs, makeOpImplicitCtxBounds(o)) // explicitly passing implicits requires passing ctxBounds, too

  def makeOpImplicitArgsWithOverload(o: Rep[DSLOp], asVals: Boolean = false, useCanonical: Boolean = false) = makeImplicitArgs(implicitArgsWithOverload(o, useCanonical), makeOpImplicitCtxBounds(o))

  // typed implicit args with context bounds (only needed for instance methods)
  // 'without' is used to subtract bounds that are already in scope
  def implicitCtxBoundsWithType(tpePars: List[Rep[TypePar]], without: List[Rep[TypePar]] = Nil) = {
    val withoutBounds = without.flatMap(a => a.ctxBounds)
    tpePars.flatMap(a => a.ctxBounds.diff(withoutBounds).map(b => ephemeralTpe(b.name+"["+quote(a)+"]", stage = now))).distinct
  }

  def makeImplicitArgsWithCtxBoundsWithType(implicitArgs: List[Rep[DSLArg]], tpePars: List[Rep[TypePar]], without: List[Rep[TypePar]] = Nil, asVals: Boolean = false) = {
    val addArgs = implicitCtxBoundsWithType(tpePars, without)
    val l = implicitArgs.length
    makeImplicitArgsWithType(implicitArgs ++ addArgs.zip(l until l+addArgs.length).map(anyToImplicitArg), asVals)
  }

  // typed implicit args without context bounds
  def makeImplicitArgsWithType(implicitArgs: List[Rep[DSLArg]], asVals: Boolean = false) = {
    val implArgs = implicitArgs diff (arg("__pos",MSourceContext))
    val prefix = if (asVals == true) "val " else ""
    if (implArgs.length > 0) "(implicit " + implArgs.map(t => prefix + argify(t,repifySome)).mkString(",") + ")"
    else ""
  }

  def makeOpImplicitArgsWithType(o: Rep[DSLOp], asVals: Boolean = false) = makeImplicitArgsWithType(o.implicitArgs, asVals)

  def makeOpImplicitArgsWithOverloadWithType(o: Rep[DSLOp], asVals: Boolean = false, useCanonical: Boolean = false) = makeImplicitArgsWithType(implicitArgsWithOverload(o, useCanonical), asVals)


  /**
   * Op method names
   */

  val specialCharacters = scala.collection.immutable.Map("+" -> "pl", "-" -> "sub", "/" -> "div", "*" -> "mul", "=" -> "eq", "<" -> "lt", ">" -> "gt", "&" -> "and", "|" -> "or", "!" -> "bang", ":" -> "cln")
  def sanitize(x: String) = {
    var out = x
    specialCharacters.keys.foreach { k => if (x.contains(k)) out = out.replace(k, specialCharacters(k)) }
    out
  }

  def makeDefWithOverride(o: Rep[DSLOp]) = {
    if (overrideList.contains(o.name)) "override def"
    else "def"
  }

  def makeOpMethodName(o: Rep[DSLOp]) = {
    Labels.getOrElse(o, {
      // adding the nameClashId is another way to avoid chaining the Overload implicit, but the weird method names that result are confusing
      val i = /*if (Config.fastCompile) nameClashId(canonical(o), nameClashesGrp) else*/ ""
      o.style match {
        case `staticMethod` => o.grp.name.toLowerCase + "_object_" + sanitize(o.name).toLowerCase + i
        case `compilerMethod` =>
          if (o.name != sanitize(o.name)) err("compiler op name has special characters that require reformatting: " + o.name)
          o.name // should be callable directly from impl code
        case _ => o.grp.name.toLowerCase + "_" + sanitize(o.name).toLowerCase + i
      }
    })
  }

  def makeOpMethodNameWithArgs(o: Rep[DSLOp]) = makeOpMethodName(o) + makeFullArgs(o, o => makeOpArgs(o))

  def makeOpMethodNameWithFutureArgs(o: Rep[DSLOp], makeArgName: Rep[DSLArg] => String = simpleArgName) = {
    if (Impls(o).isInstanceOf[Redirect]) {
      var call = "{ " + inline(o, Impls(o).asInstanceOf[Redirect].func, quoteLiteral) + " }"
      for (i <- 0 until o.args.length) {
        call = call.replaceAllLiterally(o.args.apply(i).name, makeArgName(o.args.apply(i)))
      }
      call
    }
    else {
      makeOpMethodName(o) + makeFullArgs(o, k => makeOpFutureArgs(k,makeArgName))
    }
  }

  def makeOpMethodSignature(o: Rep[DSLOp], withReturnTpe: Option[Boolean] = None) = {
    val addRet = withReturnTpe.getOrElse(Config.fastCompile)
    val ret = if (addRet || isRedirect(o)) ": " + typifySome(o.retTpe) else ""
    val implicitArgs = if (needOverload(o)) makeOpImplicitArgsWithOverloadWithType(o, useCanonical = true) else makeOpImplicitArgsWithType(o)
    // if (Config.fastCompile) {
    //   "def " + makeOpMethodName(o) + makeTpeParsWithBounds(o.tpePars) + makeOpArgsWithType(o) + makeOpImplicitArgsWithType(o) + ret
    // }
    // else {
    // SHALLOW
    "protected def " + makeOpMethodName(o) + makeTpeParsWithBounds(o.tpePars) + makeOpArgsWithType(o) + implicitArgs + ret
    // }
  }

  def makeSyntaxMethod(o: Rep[DSLOp], prefix: String = "def ", withReturnTpe: Option[Boolean] = None) = {
    // adding the return type increases verbosity in the generated code, so we omit it by default
    // val addRet = withReturnTpe.getOrElse(Config.fastCompile)
    // val ret = if (addRet || isRedirect(o)) ": " + repifySome(o.retTpe) else ""
    val ret = ": " + typifySome(o.retTpe)
    val curriedArgs = o.curriedArgs.map(a => makeArgsWithNowType(a)).mkString("")
    // prefix + o.name + makeTpeParsWithBounds(o.tpePars) + makeArgsWithNowType(o.firstArgs, o.effect != pure) + curriedArgs + makeOpImplicitArgsWithOverloadWithType(o) + ret + " = " + makeOpMethodNameWithFutureArgs(o)
    prefix + o.name + makeTpeParsWithBounds(o.tpePars) + makeArgsWithNowType(o.firstArgs, o.effect != pure) + curriedArgs + makeOpImplicitArgsWithOverloadWithType(o) + ret + " = "
  }

  def makeOpImplMethodName(o: Rep[DSLOp]) = makeOpMethodName(o) + "_impl" + nameClashId(o)

  def makeOpImplMethodNameWithArgs(o: Rep[DSLOp], postfix: String = "") = makeOpImplMethodName(o) + postfix + makeTpePars(o.tpePars) + makeOpArgs(o) + makeOpImplicitArgs(o)

  def makeOpImplMethodSignature(o: Rep[DSLOp], postfix: String = "", returnTpe: Option[String] = None) = {
    "def " + makeOpImplMethodName(o) + postfix + makeTpeParsWithBounds(o.tpePars) + makeOpArgsWithType(o) + makeOpImplicitArgsWithType(o) + ": " + (returnTpe getOrElse repifySome(o.retTpe))
  }


  /**
   * Op sanity checking
   *
   * These are mostly repetitive right now, but we could specialize the error checking more (or generalize it to be more concise).
   */

  def validTpePar(o: Rep[DSLOp], tpePar: Rep[DSLType]) = tpePar match {
    case Def(TpePar(name,_,_)) => o.tpePars.exists(_.name == name)
    case _ => true
  }

  def check(o: Rep[DSLOp]) {
    if (!Impls.contains(o)) err("op " + o.name + " has no impl")
    Impls(o) match {
      case Allocates(tpe,init) =>
        if (!DataStructs.contains(tpe)) err("op " + o.name + " allocates tpe " + tpe.name + " with no corresponding data definition")
        val data = DataStructs(tpe)
        if (init.length != data.fields.length)
          err("allocator " + o.name + " has a different number of fields than the data definition for " + tpe.name)
      case Getter(structArgIndex,field) =>
        if (structArgIndex > o.args.length) err("arg index " + structArgIndex + " does not exist for op " + o.name)
        val struct = getHkTpe(o.args.apply(structArgIndex).tpe)
        val data = DataStructs.get(struct)
        if (data.isEmpty) err("no struct definitions found for arg index " + structArgIndex + " in op " + o.name)
        if (!data.get.fields.map(_.name).contains(field)) err("struct arg " + structArgIndex + " does not contain field " + field + " in op " + o.name)
      case Setter(structArgIndex,field,value) =>
        if (structArgIndex > o.args.length) err("arg index " + structArgIndex + " does not exist for op " + o.name)
        val struct = getHkTpe(o.args.apply(structArgIndex).tpe)
        val data = DataStructs.get(struct)
        if (data.isEmpty) err("no struct definitions found for arg index " + structArgIndex + " in op " + o.name)
        if (!data.get.fields.map(_.name).contains(field)) err("struct arg " + structArgIndex + " does not contain field " + field + " in op " + o.name)
      case map:Map =>
        val col = getHkTpe(o.args.apply(map.argIndex).tpe)
        if (ForgeCollections.get(col).isEmpty) err("map argument " + col.name + " is not a ParallelCollection")
        if (map.tpePars.productIterator.exists(a => !validTpePar(o,a.asInstanceOf[Rep[DSLType]]))) err("map op with undefined type par: " + o.name)
        if (map.argIndex < 0 || map.argIndex > o.args.length) err("map op with illegal arg parameter: " + o.name)
      case zip:Zip =>
        val colA = getHkTpe(o.args.apply(zip.argIndices._1).tpe)
        val colB = getHkTpe(o.args.apply(zip.argIndices._2).tpe)
        if (ForgeCollections.get(colA).isEmpty) err("zip argument " + colA.name + " is not a ParallelCollection")
        if (ForgeCollections.get(colB).isEmpty) err("zip argument " + colB.name + " is not a ParallelCollection")
        if (zip.tpePars.productIterator.exists(a => !validTpePar(o,a.asInstanceOf[Rep[DSLType]]))) err("zipWith op with undefined type parg: " + o.name)
        if (zip.argIndices.productIterator.asInstanceOf[Iterator[Int]].exists(a => a < 0 || a > o.args.length)) err("zipWith op with illegal arg parameter: " + o.name)
      case reduce:Reduce =>
        val col = getHkTpe(o.args.apply(reduce.argIndex).tpe)
        if (ForgeCollections.get(col).isEmpty) err("reduce argument " + col.name + " is not a ParallelCollection")
        if (!validTpePar(o,reduce.tpePar)) err("reduce op with undefined type par: " + o.name)
        if (reduce.argIndex < 0 || reduce.argIndex > o.args.length) err("reduce op with illegal arg parameter: " + o.name)
        // if (reduce.zero.retTpe != reduce.tpePar) err("reduce op with illegal zero parameter: " + o.name)
      case mapreduce:MapReduce =>
        val col = getHkTpe(o.args.apply(mapreduce.argIndex).tpe)
        if (ForgeCollections.get(col).isEmpty) err("mapreduce argument " + col.name + " is not a ParallelCollection")
        if (mapreduce.tpePars.productIterator.exists(a => !validTpePar(o,a.asInstanceOf[Rep[DSLType]]))) err("mapreduce op with undefined type par: " + o.name)
        if (mapreduce.argIndex < 0 || mapreduce.argIndex > o.args.length) err("mapreduce op with illegal arg parameter: " + o.name)
      case filter:Filter =>
        val col = getHkTpe(o.args.apply(filter.argIndex).tpe)
        if (ForgeCollections.get(col).isEmpty || !ForgeCollections.get(getHkTpe(o.retTpe)).forall(_.isInstanceOf[ParallelCollectionBuffer])) err("filter return type " + col.name + " is not a ParallelCollectionBuffer")
        if (filter.tpePars.productIterator.exists(a => !validTpePar(o,a.asInstanceOf[Rep[DSLType]]))) err("filter op with undefined type par: " + o.name)
        if (filter.argIndex < 0 || filter.argIndex > o.args.length) err("filter op with illegal arg parameter: " + o.name)
      case hfr:HashFilterReduce =>
        val col = getHkTpe(o.args.apply(hfr.argIndex).tpe)
        if (ForgeCollections.get(col).isEmpty || !ForgeCollections.get(getHkTpe(o.retTpe)).forall(_.isInstanceOf[ParallelCollectionBuffer])) err("hashFilterReduce return type " + col.name + " is not a ParallelCollectionBuffer")
        if (hfr.tpePars.productIterator.exists(a => !validTpePar(o,a.asInstanceOf[Rep[DSLType]]))) err("hashFilterReduce op with undefined type par: " + o.name)
        if (hfr.argIndex < 0 || hfr.argIndex > o.args.length) err("hashFilterReduce op with illegal arg parameter: " + o.name)
      case foreach:Foreach =>
        val col = getHkTpe(o.args.apply(foreach.argIndex).tpe)
        if (ForgeCollections.get(col).isEmpty) err("foreach argument " + col.name + " is not a ParallelCollection")
        if (!validTpePar(o,foreach.tpePar)) err("foreach op with undefined type par: " + o.name)
        if (foreach.argIndex < 0 || foreach.argIndex > o.args.length) err("foreach op with illegal arg parameter: " + o.name)
      case _ => // nothing to check
    }
  }

  def emitImpl(o: Rep[DSLOp], stream: PrintWriter) {
    val indent = 2
    // val ret = typifySome(o.retTpe)
    // emitWithIndent("{", stream, indent)
    def tpeParser(prod: Product): String = 
      makeTpePars(prod.productIterator.toList.asInstanceOf[List[Rep[DSLType]]].filter(isTpePar).:+(o.retTpe).asInstanceOf[List[Rep[TypePar]]])
    def emitFunc(func: Rep[String]) {
      inline(o, func, quoteLiteral).split(nl).toList match {
        case List(line) => stream.print(line)
        case lines => {
          stream.println("{")
          lines.foreach { line => emitWithIndent(line, stream, indent+4 )}
          // emitWithIndent("}", stream, indent+2)
          stream.print((" " * (indent + 2)) + "}" )
        }
      }
    }
    // stream.print("{ ")
    Impls(o) match {
      case single:SingleTask => {
        stream.print("Delite.single(")
        // inline(o, single.func, quoteLiteral).split(nl).foreach { line => emitWithIndent(line, stream, indent+4 )}
        emitFunc(single.func)
        // emitWithIndent(")", stream, indent+2)
        stream.println(")")
      }
      case composite:Composite => {
        stream.print("Delite.composite(")
        // inline(o, composite.func, quoteLiteral).split(nl).foreach { line => emitWithIndent(line, stream, indent+4 )}
        emitFunc(composite.func)
        // emitWithIndent(")", stream, indent+2)
        stream.println(")")
      }
      case map:Map => {
        val in = o.args.apply(map.argIndex).name
        val tpePars = tpeParser(map.tpePars)
        stream.print("Delite.map" + tpePars + "(" + in + ", (")
        // inline(o, map.func, quoteLiteral).split(nl).foreach { line => emitWithIndent(line, stream, indent+4 )}
        emitFunc(map.func)
        // emitWithIndent(")", stream, indent+2)
        stream.println("))")
      }
      case zip:Zip => {
        val inA = o.args.apply(zip.argIndices._1).name
        val inB = o.args.apply(zip.argIndices._2).name
        val tpePars = tpeParser(zip.tpePars)
        stream.print("Delite.zip" + tpePars + "(" + inA + ", " + inB + ", (")
        // inline(o, zip.func, quoteLiteral).split(nl).foreach { line => emitWithIndent(line, stream, indent+4 )}
        emitFunc(zip.func)
        // emitWithIndent(")", stream, indent+2)
        stream.println("))")
      }
      case reduce:Reduce => {
        val c = o.args.apply(reduce.argIndex).name
        val tpePars = "[" + quote(reduce.tpePar) + "]"
        stream.print("Delite.reduce" + tpePars + "(" + c + ", (")
        emitFunc(reduce.zero)
        stream.print("), (")
        emitFunc(reduce.func)
        stream.println("))")
      }
      case mapreduce:MapReduce => {
        val c = o.args.apply(mapreduce.argIndex).name
        val tpePars = tpeParser(mapreduce.tpePars)
        stream.print("Delite.mapReduce" + tpePars + "(" + c + ", (")
        emitFunc(mapreduce.map)
        stream.print("), (")
        emitFunc(mapreduce.zero)
        stream.print("), (")
        emitFunc(mapreduce.reduce)
        stream.println("))")
      }
      case filter:Filter => {
        val in = o.args.apply(filter.argIndex).name
        val tpePars = tpeParser(filter.tpePars)
        stream.print("Delite.filter" + tpePars + "(" + in + ", (")
        emitFunc(filter.cond)
        stream.print("), (")
        emitFunc(filter.func)
        stream.println("))")
      }
      case hfr:HashFilterReduce => {
        val in = o.args.apply(hfr.argIndex).name
        val tpePars = tpeParser(hfr.tpePars)
        stream.print("Delite.hashFilterReduce" + tpePars + "(" + in + ", (")
        List(hfr.cond, hfr.key, hfr.map, hfr.zero) foreach { func =>
          emitFunc(func)
          stream.print("), (")
        }
        emitFunc(hfr.reduce)
        stream.println("))")
      }
      case foreach:Foreach => {
        val c = o.args.apply(foreach.argIndex).name
        val tpePars = "[" + quote(foreach.tpePar) + "]"
        stream.print("Delite.foreach" + tpePars + "(" + c + ", (")
        emitFunc(foreach.func)
        stream.println("))")
      }
      case redirect:Redirect => {
        stream.print("/*redirect*/")
        emitFunc(redirect.func)
      }
      case codegen:CodeGen =>
        stream.print("/*codegen*/")
        val rule = codegen.decls.getOrElse($cala, err("could not find Scala codegen rule for op: " + o.name))
        // inline(o, rule.decl, quoteLiteral).split(nl).foreach { line => emitWithIndent(line, stream, indent) }
        emitFunc(rule.decl)
      case Getter(structArgIndex,field) =>
        stream.print("/*getter*/")
        // emitOverloadShadows(o, stream, indent)
        emitWithIndent(inline(o, quotedArg(o.args.apply(structArgIndex).name)) + "." + field, stream, indent)
      case Setter(structArgIndex,field,value) =>
        stream.print("/*setter*/")
        // emitOverloadShadows(o, stream, indent)
        emitWithIndent(inline(o, quotedArg(o.args.apply(structArgIndex).name)) + "." + field + " = " + inline(o,value), stream, indent)
        // emitWithIndent(inline(o, quotedArg(o.args.apply(structArgIndex).name)) + "." + field + " = ", stream, indent)
        // emitFunc(value)
      case Allocates(tpe,init) =>
        stream.print("/*allocates*/")
        // emitOverloadShadows(o, stream, indent)
        val initialVals = init.map(i => inline(o,i, quoteLiteral)).mkString(",")
        emitWithIndent("new " + quote(tpe) + "(" + initialVals + ")", stream, indent)
      case _ => stream.println("???")
    }
    // emitWithIndent("}", stream, indent)
    stream.println()
  }


  /**
   * Front-end codegen
   */

  def checkOps(opsGrp: DSLOps) {
    for (o <- unique(opsGrp.ops)) check(o)
  }

  // certain ops (e.g. "apply" cannot be expressed with infix notation right now), so we use implicits as a workaround
  def noInfix(o: Rep[DSLOp]) = {
    if (Config.fastCompile) {
      // default implicit mode (appears empirically slightly faster than infix)
      (!mustInfixList.contains(o.name)) && o.args.length > 0
    }
    else {
      // default infix mode (slightly easier to understand what's happening, also fails to apply less than implicits)
      // blacklist or curried args or function args (in the latter two cases, infix doesn't always resolve correctly)
      noInfixList.contains(o.name) || o.curriedArgs.length > 0 || hasFuncArgs(o) || o.args.size > 0
    }
  }

  def emitOpSyntax(opsGrp: DSLOps, stream: PrintWriter) {
    emitBlockComment("Operations", stream)
    stream.println()

    // implicits go in a base class for lower priority
    // val implicitOps = opsGrp.ops.filter(e=>e.style==implicitMethod)
    // if (!implicitOps.isEmpty) {
    //   if (unique(implicitOps).length != implicitOps.length) err("non-unique implicit op variants (e.g. Var, T args) are not yet supported")
    //   stream.println("trait " + opsGrp.name + "Base extends " + baseOpsCls(opsGrp.grp) + " {")
    //   stream.println("  this: " + dsl + " => ")
    //   stream.println()
    //   for (o <- implicitOps) {
    //     stream.println("  implicit " + makeSyntaxMethod(o, withReturnTpe = Some(true)))
    //   }
    //   stream.println()
    //   // we separate these just for generated code readability
    //   for (o <- implicitOps if !Impls(o).isInstanceOf[Redirect]) {
    //     stream.println("  " + makeOpMethodSignature(o, withReturnTpe = Some(true)))
    //   }
    //   stream.println("}")
    //   stream.println()
    // }

    // static ops
    val staticOps = opsGrp.ops.filter(e=>e.style==staticMethod || e.style==directMethod || e.style == compilerMethod)
    val objects = staticOps.groupBy(_.grp.name)
    for ((name, ops) <- objects) {
      stream.println("object " + name + " {")
      for (o <- ops) {
        // stream.println("  " + makeSyntaxMethod(o))
        if (o.style == compilerMethod)
          // stream.print("  " + makeOpMethodSignature(o, withReturnTpe = Some(true)) + " = " )
          stream.print("  " + makeSyntaxMethod(o, "protected def "))
        else
          stream.print("  " + makeSyntaxMethod(o))
        emitImpl(o, stream)
      }
      stream.println("}")
      stream.println()
    }

    // infix ops
    val allInfixOps = opsGrp.ops.filter(e=>e.style==infixMethod)
    
    val pimpOps = allInfixOps filter (_.args.length > 0)
    // val pimpOps = allInfixOps
    if (pimpOps.nonEmpty) {
      // set up a pimp-my-library style promotion
      // val ops = pimpOps.filterNot(o => getHkTpe(o.args.apply(0).tpe).name == "Var" ||
      //                                  (o.args.apply(0).tpe.stage == now && pimpOps.exists(o2 => o.args.apply(0).tpe.name == o2.args.apply(0).tpe.name && o2.args.apply(0).tpe.stage == future)))
      val ops = pimpOps filterNot { op =>
        op.args exists { arg =>
          getHkTpe(arg.tpe).name == "Var"
        }
      }
      val tpes = ops.map(_.args.apply(0).tpe).distinct
      def isTpePure(t: Rep[DSLType]): Boolean = t match {
        case Def(Tpe(_, _, _)) => true
        case _ => false
      }
      def getTpeName(t: Rep[DSLType]): String = t match {
        case Def(Tpe(n, _, _)) => n
        case Def(TpeInst(t1,_)) => getTpeName(t1)
        case _ => quote(t)
      }

      def getTpePars(tpe: Rep[DSLType]): List[Rep[TypePar]] = tpe match {
        case Def(TpeInst(_,args)) => args.filter(isTpePar).asInstanceOf[List[Rep[TypePar]]]
        case Def(TpePar(_,_,_)) => List(tpe.asInstanceOf[Rep[TypePar]])
        case _ => tpe.tpePars
      }
      // first line filters out specialized types
      val fTpes = tpes.filter(t => { isTpePure(t) /*&& !getTpePars(t).exists(a => isTpePar(a) && List("Int", "Float", "Double").contains(asTpePar(a).name) ) */} || {
        isTpeInst(t) && (t match {case Def(TpeInst(_, args)) => args.forall(a => isTpePar(a) && asTpePar(a).name != "_")})
        })
      // for (tpe <- tpes) {
      fTpes foreach { tpe =>
        val tpePars = tpe match {
          case Def(TpeInst(_,args)) => args.filter(isTpePar).asInstanceOf[List[Rep[TypePar]]]
          case Def(TpePar(_,_,_)) => List(tpe.asInstanceOf[Rep[TypePar]])
          case _ => tpe.tpePars
        }
        val tpeArgs = tpe match {
          case Def(TpeInst(hk,args)) => args.filterNot(isTpePar)
          case _ => Nil
        }

        // val opsClsName = opsGrp.grp.name + tpeArgs.map(_.name).mkString("")
        val opsClsName = tpe.name + makeTpeParsWithBounds(tpe.tpePars)
        val helpClsName = opsGrp.grp.name

        stream.println()
        val fields = DataStructs.get(tpe)
        val fieldsString = fields map (makeFieldArgs) getOrElse ""
        stream.println("class " + opsClsName + "(" + fieldsString + ") { self => ")
        stream.println(fields map makeFieldsWithInitArgs getOrElse "")
        stream.println("  import " + helpClsName + "._")

        def emitOp(o: Rep[DSLOp], prefix: String = "def") {
          val otherArgs = makeArgsWithNowType(o.firstArgs.drop(1))
          val curriedArgs = o.curriedArgs.map(a => makeArgsWithNowType(a)).mkString("")
          val hkTpePars = if (isTpePar(tpe)) tpePars else getHkTpe(tpe).tpePars
          val otherTpePars = o.tpePars.filterNot(p => hkTpePars.map(_.name).contains(p.name))
          val ret = ": " + typifySome(o.retTpe)
          stream.print("  " + prefix + " " + o.name + makeTpeParsWithBounds(otherTpePars) + otherArgs + curriedArgs
            + (makeImplicitArgsWithCtxBoundsWithType(implicitArgsWithOverload(o) diff (arg("__pos",MSourceContext)), o.tpePars diff otherTpePars, without = hkTpePars)) + ret + " = ")
          emitImpl(o, stream)
        }

        for (o <- ops if getTpeName(o.args.apply(0).tpe) == getTpeName(tpe)) {
          emitOp(o)
        }
        stream.println("}")
      }
      stream.println()
    }

  }
}
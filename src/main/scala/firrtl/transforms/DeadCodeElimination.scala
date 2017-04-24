
package firrtl.transforms

import firrtl._
import firrtl.ir._
import firrtl.passes._
import firrtl.annotations._
import firrtl.graph._
import firrtl.analyses.InstanceGraph
import firrtl.Mappers._
import firrtl.WrappedExpression._
import firrtl.Utils.{throwInternalError, toWrappedExpression, kind}
import firrtl.MemoizedHash._
import wiring.WiringUtils.getChildrenMap

import collection.mutable
import java.io.{File, FileWriter}

class DeadCodeElimination extends Transform {
  def inputForm = LowForm
  def outputForm = LowForm

  /** Based on LogicNode ins CheckCombLoops, currently kind of faking it */
  private type LogicNode = MemoizedHash[WrappedExpression]
  private object LogicNode {
    def apply(moduleName: String, expr: Expression): LogicNode =
      WrappedExpression(Utils.mergeRef(WRef(moduleName), expr))
    def apply(moduleName: String, name: String): LogicNode = apply(moduleName, WRef(name))
    def apply(component: ComponentName): LogicNode = {
      // Currently only leaf nodes are supported TODO implement
      val loweredName = LowerTypes.loweredName(component.name.split('.'))
      apply(component.module.name, WRef(loweredName))
    }
  }

  /** Expression used to represent outputs in the circuit (# is illegal in names) */
  private val circuitSink = LogicNode("#Top", "#Sink")

  /** Extract all References and SubFields from a possibly nested Expression */
  def extractRefs(expr: Expression): Seq[Expression] = {
    val refs = mutable.ArrayBuffer.empty[Expression]
    def rec(e: Expression): Expression = {
      e match {
        case ref @ (_: WRef | _: WSubField) => refs += ref
        case nested @ (_: Mux | _: DoPrim | _: ValidIf) => nested map rec
        case ignore @ (_: Literal) => // Do nothing
        case unexpected => throwInternalError
      }
      e
    }
    rec(expr)
    refs
  }

  /** Construct the dependency graph within this module */
  private def setupDepGraph(depGraph: MutableDiGraph[LogicNode],
                            instMap: collection.Map[String, String])
                           (mod: Module): Unit = {
    // Gets all dependencies and constructs LogicNodes from them
    def getDeps(expr: Expression): Seq[LogicNode] =
      extractRefs(expr).map { e =>
        if (kind(e) == InstanceKind) {
          val (inst, tail) = Utils.splitRef(e)
          LogicNode(instMap(inst.name), tail)
        } else {
          LogicNode(mod.name, e)
        }
      }

    def onStmt(stmt: Statement): Unit = stmt match {
      case DefRegister(_, name, _, clock, reset, init) =>
        val node = LogicNode(mod.name, name)
        depGraph.addVertex(node)
        Seq(clock, reset, init).flatMap(getDeps(_)).foreach(ref => depGraph.addEdge(node, ref))
      case DefNode(_, name, value) =>
        val node = LogicNode(mod.name, name)
        depGraph.addVertex(node)
        getDeps(value).foreach(ref => depGraph.addEdge(node, ref))
      case DefWire(_, name, _) =>
        depGraph.addVertex(LogicNode(mod.name, name))
      case mem: DefMemory =>
        // Treat DefMems as a node with outputs depending on the node and node depending on inputs
				// From perpsective of the module or instance, MALE expressions are inputs, FEMALE are outputs
        val memRef = WRef(mem.name, MemPortUtils.memType(mem), ExpKind, FEMALE)
				val exprs = Utils.create_exps(memRef).groupBy(Utils.gender(_))
				val sources = exprs.getOrElse(MALE, List.empty).flatMap(getDeps(_))
				val sinks = exprs.getOrElse(FEMALE, List.empty).flatMap(getDeps(_))
        val memNode = getDeps(memRef) match { case Seq(node) => node }
        depGraph.addVertex(memNode)
        sinks.foreach(sink => depGraph.addEdge(sink, memNode))
        sources.foreach(source => depGraph.addEdge(memNode, source))
      case Attach(_, exprs) => // Add edge between each expression
        exprs.flatMap(getDeps(_)).toSet.subsets(2).map(_.toList).foreach {
          case Seq(a, b) =>
            depGraph.addEdge(a, b)
            depGraph.addEdge(b, a)
        }
      case Connect(_, loc, expr) =>
        // This match enforces the low Firrtl requirement of expanded connections
        val node = getDeps(loc) match { case Seq(elt) => elt }
        getDeps(expr).foreach(ref => depGraph.addEdge(node, ref))
      //// Simulation constructs are treated as top-level outputs
      case Stop(_,_, clk, en) =>
      //  Seq(clk, en).flatMap(getDeps(_)).foreach(ref => depGraph.addEdge(circuitSink, ref))
      case Print(_, _, args, clk, en) =>
      //  (args :+ clk :+ en).flatMap(getDeps(_)).foreach(ref => depGraph.addEdge(circuitSink, ref))
      case Block(stmts) => stmts.foreach(onStmt(_))
      case ignore @ (_: IsInvalid | _: WDefInstance | EmptyStmt) => // do nothing
      case other => throw new Exception(s"Unexpected Statement $other")
    }

    // Add all ports as vertices
    mod.ports.foreach { case Port(_, name, _, _: GroundType) =>
      depGraph.addVertex(LogicNode(mod.name, name))
    }
    onStmt(mod.body)
  }

  // TODO Make immutable?
  private def createDependencyGraph(instMaps: collection.Map[String, collection.Map[String, String]],
                                    c: Circuit): MutableDiGraph[LogicNode] = {
    val depGraph = new MutableDiGraph[LogicNode]
    c.modules.foreach {
      case mod: Module => setupDepGraph(depGraph, instMaps(mod.name))(mod)
      case ext: ExtModule =>
        // Connect all inputs to all outputs
        val node = LogicNode(ext.name, ext.name)
        val ports = ext.ports.groupBy(_.direction)
        depGraph.addVertex(node)
        ports.get(Output).foreach(_.foreach(output => depGraph.addEdge(LogicNode(ext.name, output.name), node)))
        ports.get(Input).foreach(_.foreach(input => depGraph.addEdge(node, LogicNode(ext.name, input.name))))
    }
    // Connect circuitSink to top-level outputs (and Analog inputs)
    val topModule = c.modules.find(_.name == c.main).get
    val topOutputs = topModule.ports.foreach {
      case Port(_, name, Output, _) => depGraph.addEdge(circuitSink, LogicNode(c.main, name))
      case Port(_, name, _, _: AnalogType) => depGraph.addEdge(circuitSink, LogicNode(c.main, name))
      case _ =>
    }

    depGraph
  }

  private def deleteDeadCode(instMap: collection.Map[String, String],
                             deadNodes: Set[LogicNode],
                             moduleMap: collection.Map[String, DefModule])
                            (mod: Module): Option[Module] = {
    // Gets all dependencies and constructs LogicNodes from them
    // TODO this is a duplicate from setupDepGraph, remove by improving how we lookup
    def getDeps(expr: Expression): Seq[LogicNode] =
      extractRefs(expr).map { e =>
        if (kind(e) == InstanceKind) {
          val (inst, tail) = Utils.splitRef(e)
          LogicNode(instMap(inst.name), tail)
        } else {
          LogicNode(mod.name, e)
        }
      }

    // TODO Delete unused writers from DefMemory???
    def onStmt(stmt: Statement): Statement = stmt match {
      case inst: WDefInstance =>
        moduleMap.get(inst.module) match {
          case Some(instMod) => inst.copy(tpe = Utils.module_type(instMod))
          case None => EmptyStmt
        }
      case decl: IsDeclaration =>
        val node = LogicNode(mod.name, decl.name)
        if (deadNodes.contains(node)) EmptyStmt else decl
      case con: Connect =>
        val node = getDeps(con.loc) match { case Seq(elt) => elt }
        if (deadNodes.contains(node)) EmptyStmt else con
      case Attach(info, exprs) => // If any exprs are dead then all are
        val dead = exprs.flatMap(getDeps(_)).forall(deadNodes.contains(_))
        if (dead) EmptyStmt else Attach(info, exprs)
      // Temporarily remove sim code
      case (_: Print | _: Stop) => EmptyStmt
      case other => other map onStmt
    }

    val portsx = mod.ports.filterNot(p => deadNodes.contains(LogicNode(mod.name, p.name)))
    if (portsx.isEmpty) None else Some(mod.copy(ports = portsx, body = onStmt(mod.body)))
  }

  def run(c: Circuit, dontTouches: Seq[LogicNode]): Circuit = {
    val moduleMap = c.modules.map(m => m.name -> m).toMap
    val iGraph = new InstanceGraph(c)
    val moduleDeps = iGraph.graph.edges.map { case (k,v) =>
      k.module -> v.map(i => i.name -> i.module).toMap
    }
    val topoSortedModules = iGraph.graph.transformNodes(_.module).linearize.reverse.map(moduleMap(_))

    val depGraph = {
      val dGraph = createDependencyGraph(moduleDeps, c)
      for (dontTouch <- dontTouches) {
        dGraph.getVertices.find(_ == dontTouch) match {
          case Some(node) => dGraph.addEdge(circuitSink, node)
          case None =>
            val (root, tail) = Utils.splitRef(dontTouch.e1)
            DontTouchAnnotation.errorNotFound(root.serialize, tail.serialize)
        }
      }
      DiGraph(dGraph)
    }

    val liveNodes = depGraph.reachableFrom(circuitSink) + circuitSink
    val deadNodes = depGraph.getVertices -- liveNodes

    // As we delete deadCode, we will delete ports from Modules and somtimes complete modules
    // themselves. We iterate over the modules in a topological order from leaves to the top. The
    // current status of the modulesxMap is used to either delete instances or update their types
    val modulesxMap = mutable.HashMap.empty[String, DefModule]
    topoSortedModules.foreach {
      case mod: Module =>
        deleteDeadCode(moduleDeps(mod.name), deadNodes, modulesxMap)(mod).foreach { m =>
          modulesxMap += m.name -> m
        }
      case ext: ExtModule =>
        modulesxMap += ext.name -> ext
    }

    // Preserve original module order
    c.copy(modules = c.modules.flatMap(m => modulesxMap.get(m.name)))
  }

  def execute(state: CircuitState): CircuitState = {
    //val anno = DontTouchAnnotation(ComponentName("x", ModuleName("Top", CircuitName("Top"))))
    //val annotations = Option(AnnotationMap(Seq(anno)))
    //println(AnnotationUtils.toYaml(anno))
		val dontTouches: Seq[LogicNode] = state.annotations match {
		//val dontTouches: Seq[LogicNode] = annotations match {
      case Some(aMap) =>
        aMap.annotations.collect { case DontTouchAnnotation(component) => LogicNode(component) }
      case None => Seq.empty
    }

    println("Running DeadCodeElimination")
    println(dontTouches.map(_.e1.serialize))

    state.copy(circuit = run(state.circuit, dontTouches))
  }
}
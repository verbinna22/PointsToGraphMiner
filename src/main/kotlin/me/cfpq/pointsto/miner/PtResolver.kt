package me.cfpq.pointsto.miner

import mu.KotlinLogging
import org.jacodb.api.jvm.JcMethod
import org.jacodb.api.jvm.cfg.JcArgument
import org.jacodb.api.jvm.cfg.JcArrayAccess
import org.jacodb.api.jvm.cfg.JcAssignInst
import org.jacodb.api.jvm.cfg.JcCallExpr
import org.jacodb.api.jvm.cfg.JcCallInst
import org.jacodb.api.jvm.cfg.JcCastExpr
import org.jacodb.api.jvm.cfg.JcExpr
import org.jacodb.api.jvm.cfg.JcFieldRef
import org.jacodb.api.jvm.cfg.JcInst
import org.jacodb.api.jvm.cfg.JcInstanceCallExpr
import org.jacodb.api.jvm.cfg.JcLocalVar
import org.jacodb.api.jvm.cfg.JcNewArrayExpr
import org.jacodb.api.jvm.cfg.JcNewExpr
import org.jacodb.api.jvm.cfg.JcRef
import org.jacodb.api.jvm.cfg.JcReturnInst
import org.jacodb.api.jvm.cfg.JcThis

private val logger = KotlinLogging.logger {}
internal var contextIdGenerator = ConcurrentIdGenerator()

fun resolveJcInst(method: JcMethod, inst: JcInst, edges: MutableList<PtEdge>) = runCatching {
    when (inst) {
        is JcAssignInst -> {
            val lhs = resolveJcExprToPtVertex(method, inst.lineNumber, inst.lhv, edges, handSide = HandSide.LEFT)
            val rhs = resolveJcExprToPtVertex(method, inst.lineNumber, inst.rhv, edges, handSide = HandSide.RIGHT)
            if (lhs.isNotEmpty() && rhs.isNotEmpty()) {
                if (rhs[0] is PtReturnWithContext) {
                    for (retMethod in rhs) {
                        if (retMethod is PtReturnWithContext) {
                            edges.add(
                                PtAssignWithContextEdge(
                                    lhs = lhs[0],
                                    rhs = PtReturn(retMethod.method),
                                    contextId = -retMethod.contextId
                                )
                            )
                        } else {
                            throw IllegalArgumentException("must be return with context")
                        }
                    }
                } else {
                    edges.add(PtAssignEdge(lhs = lhs[0], rhs = rhs[0]))
                }
            }
        }

        is JcCallInst -> {
            // remove Benchmark.use() fake call
            if (inst.callExpr.method.method.name != "use" || inst.callExpr.method.method.enclosingClass.name != "benchmark.internal.Benchmark") {
                resolveJcExprToPtVertex(
                    method,
                    inst.lineNumber,
                    inst.callExpr,
                    edges,
                    handSide = HandSide.RIGHT
                )
            }
        }

        is JcReturnInst -> inst.returnValue?.let { returnValue ->
            val lhs = PtReturn(method)
            val rhss = resolveJcExprToPtVertex(method, inst.lineNumber, returnValue, edges, HandSide.RIGHT)
            for (rhs in rhss) {
                edges.add(PtAssignEdge(lhs = lhs, rhs = rhs))
            }
        }
    }
    null
}.getOrElse { e ->
    logger.warn("Failed to resolve JC inst: $inst: ${e.message}")
    null
}

private enum class HandSide {
    LEFT, RIGHT
}

private fun resolveJcExprToPtVertex(
    method: JcMethod,
    lineNumber: Int,
    expr: JcExpr,
    edges: MutableList<PtEdge>,
    handSide: HandSide,
): List<PtVertex> = when (expr) {
    is JcCastExpr -> resolveJcExprToPtVertex(method, lineNumber, expr.operand, edges, handSide)
    is JcArgument -> listOf(PtArg(method, expr.index))
    is JcLocalVar -> listOf(PtLocalVar(method, lineNumber, expr.name, expr.type))
    is JcThis -> listOf(PtThis(method))
    is JcRef -> { // in new jacodb version we change it to Ref
        val (instance, field) = when (expr) {
            is JcFieldRef -> expr.instance?.let {
                resolveJcExprToPtVertex(
                    method,
                    lineNumber,
                    it,
                    edges,
                    handSide
                )
            } to PtSimpleField(expr.field.field)

            is JcArrayAccess -> resolveJcExprToPtVertex(
                method,
                lineNumber,
                expr.array,
                edges,
                handSide
            ) to PtArrayElementField

            else -> error("Unexpected expression type ${expr::class.java}")
        }
        listOf(PtTempVertex(expr.type, lineNumber).also { tempVertex ->
            edges.add(
                when (handSide) {
                    HandSide.RIGHT -> PtLoadEdge(tempVertex, instance?.get(0), field)
                    HandSide.LEFT -> PtStoreEdge(instance?.get(0), field, tempVertex)
                }
            )
        })
    }

    is JcCallExpr -> {
        require(handSide == HandSide.RIGHT)
        val contextId = contextIdGenerator.generateId()
        val allMethods = sequence {
            yield(expr.method.method)
            yieldAll(expr.method.method.overriddenMethods)
        }
        expr.args.forEachIndexed { i, arg ->
            val rhss = resolveJcExprToPtVertex(method, lineNumber, arg, edges, handSide)
            allMethods.forEach { method ->
                val lhs = PtArg(method, i)
                for (rhs in rhss) {
                    edges.add(PtAssignWithContextEdge(lhs = lhs, rhs = rhs, contextId = contextId))
                }
            }
        }
        if (expr is JcInstanceCallExpr) {
            val rhss = resolveJcExprToPtVertex(method, lineNumber, expr.instance, edges, handSide)
            allMethods.forEach { method ->
                val lhs = PtThis(method)
                for (rhs in rhss) {
                    edges.add(PtAssignWithContextEdge(lhs = lhs, rhs = rhs, contextId = contextId))
                }
            }
        }
        allMethods.map { PtReturnWithContext(it, contextId) }.toList()
    }

    else -> {
        require(handSide == HandSide.RIGHT)
        if (expr is JcNewExpr || expr is JcNewArrayExpr) {
            listOf(PtTempVertex(expr.type, lineNumber).also { tempVertex ->
                edges.add(PtAllocEdge(lhs = tempVertex, rhs = PtAllocVertex(expr, lineNumber, method, expr.type)))
            })
        } else {
            listOf()
        }
    }
}

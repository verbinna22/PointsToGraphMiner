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
import org.jacodb.api.jvm.cfg.JcRawComplexValue
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
            if (lhs != null && rhs != null) {
                if (rhs is PtReturnWithContext) {
                    edges.add(PtAssignWithContextEdge(lhs = lhs, rhs = PtReturn(rhs.method), contextId = -rhs.contextId))
                } else {
                    edges.add(PtAssignEdge(lhs = lhs, rhs = rhs))
                }
            }
        }

        is JcCallInst -> {
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
            val rhs = resolveJcExprToPtVertex(method, inst.lineNumber, returnValue, edges, HandSide.RIGHT)
            if (rhs != null) {
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
): PtVertex? = when (expr) {
    is JcCastExpr -> resolveJcExprToPtVertex(method, lineNumber, expr.operand, edges, handSide)
    is JcArgument -> PtArg(method, expr.index)
    is JcLocalVar -> PtLocalVar(method, lineNumber, expr.name, expr.type)
    is JcThis -> PtThis(method)
    is JcRef -> { // TODO
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
        PtTempVertex(expr.type, lineNumber).also { tempVertex ->
            edges.add(
                when (handSide) {
                    HandSide.RIGHT -> PtLoadEdge(tempVertex, instance, field)
                    HandSide.LEFT -> PtStoreEdge(instance, field, tempVertex)
                }
            )
        }
    }

    is JcCallExpr -> {
        require(handSide == HandSide.RIGHT)
        val contextId = contextIdGenerator.generateId()
        expr.args.forEachIndexed { i, arg ->
            val rhs = resolveJcExprToPtVertex(method, lineNumber, arg, edges, handSide)
            val lhs = PtArg(expr.method.method, i)
            if (rhs != null) {
                edges.add(PtAssignWithContextEdge(lhs = lhs, rhs = rhs, contextId = contextId))
            }
        }
        if (expr is JcInstanceCallExpr) {
            val lhs = PtThis(expr.method.method)
            val rhs = resolveJcExprToPtVertex(method, lineNumber, expr.instance, edges, handSide)
            if (rhs != null) {
                edges.add(PtAssignWithContextEdge(lhs = lhs, rhs = rhs, contextId = contextId))
            }
        }
        PtReturnWithContext(expr.method.method, contextId)
    }

    else -> {
        require(handSide == HandSide.RIGHT)
        if (expr is JcNewExpr || expr is JcNewArrayExpr) {
            PtTempVertex(expr.type, lineNumber).also { tempVertex ->
                edges.add(PtAllocEdge(lhs = tempVertex, rhs = PtAllocVertex(expr, lineNumber, method, expr.type)))
            }
        } else {
            null
        }
    }
}

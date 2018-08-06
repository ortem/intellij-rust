/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.ControlFlowGraph
import org.rust.lang.core.DataFlowContext
import org.rust.lang.core.DataFlowOperator
import org.rust.lang.core.KillFrom
import org.rust.lang.core.KillFrom.Execution
import org.rust.lang.core.KillFrom.ScopeEnd
import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.RsStructKind
import org.rust.lang.core.psi.ext.kind
import org.rust.lang.core.psi.ext.namedFields
import org.rust.lang.core.types.borrowck.LoanPathElement.Interior
import org.rust.lang.core.types.borrowck.LoanPathKind.*
import org.rust.lang.core.types.infer.FieldIndex
import org.rust.lang.core.types.infer.InteriorKind
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.ty.TyUnknown
import org.rust.openapiext.testAssert

class MoveData(
    val paths: MutableList<MovePath> = mutableListOf(),

    /** Cache of loan path to move path index, for easy lookup. */
    val pathMap: MutableMap<LoanPath, MovePathIndex> = mutableMapOf(),

    /** Each move or uninitialized variable gets an entry here. */
    val moves: MutableList<Move> = mutableListOf(),

    /**
     * Assignments to a variable, like `x = foo`. These are assigned
     * bits for dataflow, since we must track them to ensure that
     * immutable variables are assigned at most once along each path.
     */
    val varAssignments: MutableList<Assignment> = mutableListOf(),

    /**
     * Assignments to a path, like `x.f = foo`. These are not
     * assigned dataflow bits, but we track them because they still
     * kill move bits.
     */
    val pathAssignments: MutableList<Assignment> = mutableListOf(),

    /** Assignments to a variable or path, like `x = foo`, but not `x += foo`. */
    val assigneeElements: MutableSet<RsElement> = mutableSetOf()
) {

    fun isEmpty(): Boolean =
        moves.isEmpty() && pathAssignments.isEmpty() && varAssignments.isEmpty()

    fun isVariablePath(pathIndex: MovePathIndex): Boolean =
        paths[pathIndex].parent == null

    fun killMoves(pathIndex: MovePathIndex, killElement: RsElement, killKind: KillFrom, dfcxMoves: MoveDataFlow) {
        val loanPath = paths[pathIndex].loanPath
        if (loanPath.isPrecise) {
            // TODO
        }
    }

    /**
     * Adds the gen/kills for the various moves and assignments into the provided data flow contexts.
     * Moves are generated by moves and killed by assignments and scoping.
     * Assignments are generated by assignment to variables and killed by scoping
     */
    fun addGenKills(bccx: BorrowCheckContext, dfcxMoves: MoveDataFlow, dfcxAssign: AssignDataFlow) {
        moves.forEachIndexed { i, move ->
            dfcxMoves.addGen(move.element, i)
        }

        varAssignments.forEachIndexed { i, assignment ->
            dfcxAssign.addGen(assignment.element, i)
            killMoves(assignment.path, assignment.element, Execution, dfcxMoves)
        }

        pathAssignments.forEach { assignment ->
            killMoves(assignment.path, assignment.element, Execution, dfcxMoves)
        }

        paths.forEach { path ->
            val kind = path.loanPath.kind
            if (kind is Var || kind is Upvar || kind is Downcast) {
                val killScope = path.loanPath.killScope(bccx)
                val pathIndex = pathMap[path.loanPath] ?: return
                killMoves(pathIndex, killScope.element, ScopeEnd, dfcxMoves)
            }
        }

        varAssignments.forEachIndexed { i, assignment ->
            val lp = paths[assignment.path].loanPath
            if (lp.kind is Var || lp.kind is Upvar || lp.kind is Downcast) {
                val killScope = lp.killScope(bccx)
                dfcxAssign.addKill(ScopeEnd, killScope.element, i)
            }
        }
    }

    /**
     * Returns the existing move path index for [loanPath], if any, and otherwise adds a new index for [loanPath]
     * and any of its base paths that do not yet have an index.
     */
    fun movePath(loanPath: LoanPath): MovePathIndex {
        pathMap[loanPath]?.let { return it }

        val kind = loanPath.kind
        when (kind) {
            is Var, is Upvar -> paths.add(MovePath(loanPath))

            is Downcast, is Extend -> {
                val base = (kind as? Downcast)?.loanPath ?: (kind as? Extend)?.loanPath!!
                val parentIndex = movePath(base)
                val nextSibling = paths[parentIndex].firstChild

                paths[parentIndex].firstChild = paths.size

                paths.add(MovePath(loanPath, parentIndex, null, null, nextSibling))
            }
        }

        val index = paths.size
        testAssert { index == paths.size - 1 }
        pathMap[loanPath] = index
        return index
    }

    private fun processUnionFields(loanPath: LoanPath, lpKind: LoanPathKind.Extend, action: (LoanPath) -> Unit) {
        val base = lpKind.loanPath
        val baseType = base.ty as? TyAdt ?: return
        val lpElement = lpKind.lpElement as? Interior ?: return
        val item = baseType.item as? RsStructItem ?: return
        if (!item.isUnion) return

        val interiorKind = lpElement.kind
        val variant = lpElement.element
        val mutCat = lpKind.mutCategory

        // Moving/assigning one union field automatically moves/assigns all its fields
        item.namedFields.forEachIndexed { i, field ->
            val fieldInteriorKind = InteriorKind.InteriorField(FieldIndex(i, field.name))
            val fieldType = if (fieldInteriorKind == interiorKind) loanPath.ty else TyUnknown
            if (fieldInteriorKind != interiorKind) {
                val siblingLpKind = Extend(base, mutCat, Interior(variant, fieldInteriorKind))
                val siblingLp = LoanPath(siblingLpKind, fieldType)
                action(siblingLp)
            }
        }
    }

    /** Adds a new move entry for a move of [loanPath] that occurs at location [element] with kind [kind] */
    fun addMove(loanPath: LoanPath, element: RsElement, kind: MoveKind) {
        fun addMoveHelper(loanPath: LoanPath) {
            val pathIndex = movePath(loanPath)
            val nextMove = paths[pathIndex].firstMove
            paths[pathIndex].firstMove = moves.size
            moves.add(Move(pathIndex, element, kind, nextMove))
        }

        var lp = loanPath
        var lpKind = lp.kind
        while (lpKind is Extend) {
            val base = lpKind.loanPath
            processUnionFields(loanPath, lpKind) { addMoveHelper(it) }
            lp = base
            lpKind = lp.kind
        }

        addMoveHelper(loanPath)
    }

    fun addAssignment(loanPath: LoanPath, assign: RsElement, assignee: RsElement, mode: MutateMode) {
        fun addAssignmentHelper(loanPath: LoanPath) {
            val pathIndex = movePath(loanPath)

            if (mode == MutateMode.Init || mode == MutateMode.JustWrite) {
                assigneeElements.add(assignee)
            }

            val assignment = Assignment(pathIndex, assign, assignee)

            if (isVariablePath(pathIndex)) {
                varAssignments.add(assignment)
            } else {
                pathAssignments.add(assignment)
            }
        }

        val lpKind = loanPath.kind
        if (lpKind is Extend) {
            processUnionFields(loanPath, lpKind) { addAssignmentHelper(it) }
        } else {
            addAssignmentHelper(loanPath)
        }
    }
}

class FlowedMoveData(moveData: MoveData, bccx: BorrowCheckContext, cfg: ControlFlowGraph, body: RsBlock) {
    val moveData: MoveData
    val dataFlowMoves: MoveDataFlow
    val dataFlowAssign: AssignDataFlow

    init {
        val dfcxMoves = DataFlowContext("flowed_move_data_moves", body, cfg, MoveDataFlowOperator, moveData.moves.size)
        val dfcxAssign = DataFlowContext("flowed_move_data_assigns", body, cfg, AssignDataFlowOperator, moveData.varAssignments.size)
        moveData.addGenKills(bccx, dfcxMoves, dfcxAssign)
        dfcxMoves.addKillsFromFlowExits()
        dfcxAssign.addKillsFromFlowExits()
        dfcxMoves.propagate()
        dfcxAssign.propagate()

        this.moveData = moveData
        this.dataFlowMoves = dfcxMoves
        this.dataFlowAssign = dfcxAssign
    }
}

class Move(
    val path: MovePathIndex,
    val element: RsElement,
    val kind: MoveKind,
    /** Next node in linked list of moves from `path` */
    val nextMove: MoveIndex?
)

class MovePath(
    val loanPath: LoanPath,
    var parent: MovePathIndex? = null,
    var firstMove: MoveIndex? = null,
    var firstChild: MovePathIndex? = null,
    var nextSibling: MovePathIndex? = null
)

typealias MoveIndex = Int
typealias MovePathIndex = Int

enum class MoveKind {
    Declared,   // When declared, variables start out "moved".
    MoveExpr,   // Expression or binding that moves a variable
    MovePat,    // By-move binding
    Captured    // Closure creation that moves a value
}

object MoveDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred     // moves from both preds are in scope
    override val initialValue: Boolean get() = false                // no loans in scope by default
}

object AssignDataFlowOperator : DataFlowOperator {
    override fun join(succ: Int, pred: Int): Int = succ or pred     // moves from both preds are in scope
    override val initialValue: Boolean get() = false                // no assignments in scope by default
}

typealias MoveDataFlow = DataFlowContext<MoveDataFlowOperator>
typealias AssignDataFlow = DataFlowContext<AssignDataFlowOperator>

class Assignment(
    // path being assigned
    val path: MovePathIndex,

    // where assignment occurs
    val element: RsElement,

    // element for place expression on lhs of assignment
    val assignee: RsElement
)

val LoanPath.isPrecise: Boolean
    get() = when (kind) {
        is Var, is Upvar -> true
        is Extend -> if (kind.lpElement is Interior) false else kind.loanPath.isPrecise
        is Downcast -> kind.loanPath.isPrecise
    }

val RsStructItem.isUnion: Boolean get() = kind == RsStructKind.UNION
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
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.types.borrowck.LoanPathElement.Interior
import org.rust.lang.core.types.borrowck.LoanPathKind.*

class MoveData(
    val paths: MutableList<MovePath>,

    /** Cache of loan path to move path index, for easy lookup. */
    val pathMap: MutableMap<LoanPath, MovePathIndex>,

    /** Each move or uninitialized variable gets an entry here. */
    val moves: MutableList<Move>,

    /**
     * Assignments to a variable, like `x = foo`. These are assigned
     * bits for dataflow, since we must track them to ensure that
     * immutable variables are assigned at most once along each path.
     */
    val varAssignments: MutableList<Assignment>,

    /**
     * Assignments to a path, like `x.f = foo`. These are not
     * assigned dataflow bits, but we track them because they still
     * kill move bits.
     */
    val pathAssignments: MutableList<Assignment>,

    /** Assignments to a variable or path, like `x = foo`, but not `x += foo`. */
    val assigneeElements: MutableSet<RsElement>
) {
    fun pathLoanPath(index: MovePathIndex): LoanPath =
        paths[index.index].loanPath.copy()

    fun pathParent(index: MovePathIndex): MovePathIndex? =
        paths[index.index].parent

    fun pathFirstMove(index: MovePathIndex): MoveIndex? =
        paths[index.index].firstMove

    fun isEmpty(): Boolean =
        moves.isEmpty() && pathAssignments.isEmpty() && varAssignments.isEmpty()

    fun killMoves(path: MovePathIndex, killElement: RsElement, killKind: KillFrom, dfcxMoves: MoveDataFlow) {
        val loanPath = this.pathLoanPath(path)
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
            val lp = pathLoanPath(assignment.path)
            if (lp.kind is Var || lp.kind is Upvar || lp.kind is Downcast) {
                val killScope = lp.killScope(bccx)
                dfcxAssign.addKill(ScopeEnd, killScope.element, i)
            }
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
    val parent: MovePathIndex?,
    val firstMove: MoveIndex?,
    val firstChild: MovePathIndex?,
    val nextSibling: MovePathIndex?
)

data class MoveIndex(val index: Int)
data class MovePathIndex(val index: Int)

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

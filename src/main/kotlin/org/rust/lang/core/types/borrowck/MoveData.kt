/*
 * Use of this source code is governed by the MIT license that can be
 * found in the LICENSE file.
 */

package org.rust.lang.core.types.borrowck

import org.rust.lang.core.DataFlowContext
import org.rust.lang.core.DataFlowOperator
import org.rust.lang.core.KillFrom
import org.rust.lang.core.KillFrom.Execution
import org.rust.lang.core.KillFrom.ScopeEnd
import org.rust.lang.core.cfg.ControlFlowGraph
import org.rust.lang.core.psi.RsBlock
import org.rust.lang.core.psi.RsStructItem
import org.rust.lang.core.psi.ext.RsElement
import org.rust.lang.core.psi.ext.RsStructKind
import org.rust.lang.core.psi.ext.kind
import org.rust.lang.core.psi.ext.namedFields
import org.rust.lang.core.types.borrowck.LoanPathElement.Interior
import org.rust.lang.core.types.borrowck.LoanPathKind.*
import org.rust.lang.core.types.infer.InteriorKind
import org.rust.lang.core.types.ty.TyAdt
import org.rust.lang.core.types.ty.TyUnknown
import org.rust.openapiext.testAssert

class MoveData(
    val paths: MutableList<MovePath> = mutableListOf(),

    /** Cache of loan path to move path, for easy lookup. */
    val pathMap: MutableMap<LoanPath, MovePath> = mutableMapOf(),

    /** Each move or uninitialized variable gets an entry here. */
    val moves: MutableList<Move> = mutableListOf(),

    /**
     * Assignments to a variable, like `x = foo`. These are assigned bits for dataflow since we must track them
     * to ensure that immutable variables are assigned at most once along each path.
     */
    val varAssignments: MutableList<Assignment> = mutableListOf(),

    /**
     * Assignments to a path, like `x.f = foo`. These are not assigned dataflow bits,
     * but we track them because they still kill move bits.
     */
    val pathAssignments: MutableList<Assignment> = mutableListOf(),

    /** Assignments to a variable or path, like `x = foo`, but not `x += foo`. */
    val assigneeElements: MutableSet<RsElement> = mutableSetOf()
) {
    fun isEmpty(): Boolean =
        moves.isEmpty() && pathAssignments.isEmpty() && varAssignments.isEmpty()

    fun isVariablePath(path: MovePath): Boolean =
        path.parent == null

    fun eachExtendingPath(movePath: MovePath, action: (MovePath) -> Boolean): Boolean {
        if (!action(movePath)) return false
        var path = movePath.firstChild
        while (path != null) {
            if (!eachExtendingPath(path, action)) return false
            path = path.nextSibling
        }
        return true
    }

    fun eachApplicableMove(movePath: MovePath, action: (Move) -> Boolean): Boolean {
        var result = true
        eachExtendingPath(movePath) { path ->
            var move: Move? = path.firstMove
            while (move != null) {
                if (!action(move)) {
                    result = false
                    break
                }
                move = move.nextMove
            }
            result
        }
        return result
    }

    fun killMoves(path: MovePath, killElement: RsElement, killKind: KillFrom, dfcxMoves: MoveDataFlow) {
        if (!path.loanPath.isPrecise) return

        eachApplicableMove(path) { move ->
            dfcxMoves.addKill(killKind, killElement, move.index)
            true
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

        // Kill all moves related to a variable `x` when it goes out of scope
        paths.forEach { path ->
            val kind = path.loanPath.kind
            if (kind is Var || kind is Upvar || kind is Downcast) {
                val killScope = path.loanPath.killScope(bccx)
                val movePath = pathMap[path.loanPath] ?: return
                killMoves(movePath, killScope.element, ScopeEnd, dfcxMoves)
            }
        }

        // Kill all assignments when the variable goes out of scope
        varAssignments.forEachIndexed { i, assignment ->
            val lp = assignment.path.loanPath
            if (lp.kind is Var || lp.kind is Upvar || lp.kind is Downcast) {
                val killScope = lp.killScope(bccx)
                dfcxAssign.addKill(ScopeEnd, killScope.element, i)
            }
        }
    }

    /**
     * Returns the existing move path for [loanPath], if any, and otherwise adds a new move path for [loanPath]
     * and any of its base paths that do not yet have an index.
     */
    fun movePathOf(loanPath: LoanPath): MovePath {
        pathMap[loanPath]?.let { return it }

        val kind = loanPath.kind
        val oldSize = when (kind) {
            is Var, is Upvar -> {
                val index = paths.size
                paths.add(MovePath(loanPath))
                index
            }

            is Downcast, is Extend -> {
                val base = (kind as? Downcast)?.loanPath ?: (kind as? Extend)?.loanPath!!
                val parentPath = movePathOf(base)
                val index = paths.size

                val newMovePath = MovePath(loanPath, parentPath, null, null, parentPath.firstChild)
                parentPath.firstChild = newMovePath
                paths.add(newMovePath)
                index
            }
        }

        testAssert { oldSize == paths.size - 1 }
        pathMap[loanPath] = paths.last()
        return paths.last()
    }

    private fun processUnionFields(loanPath: LoanPath, lpKind: LoanPathKind.Extend, action: (LoanPath) -> Unit) {
        val base = lpKind.loanPath
        val baseType = base.ty as? TyAdt ?: return
        val lpElement = lpKind.lpElement as? Interior ?: return
        val union = (baseType.item as? RsStructItem)?.takeIf { it.isUnion } ?: return

        val interiorKind = lpElement.kind
        val variant = lpElement.element
        val mutCat = lpKind.mutCategory

        // Moving/assigning one union field automatically moves/assigns all its fields
        union.namedFields.forEachIndexed { i, field ->
            val fieldInteriorKind = InteriorKind.InteriorField(field.name)
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
            val path = movePathOf(loanPath)
            val nextMove = path.firstMove
            val newMove = Move(path, element, kind, moves.size, nextMove)
            path.firstMove = newMove
            moves.add(newMove)
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
            val movePath = movePathOf(loanPath)

            if (mode == MutateMode.Init || mode == MutateMode.JustWrite) {
                assigneeElements.add(assignee)
            }

            val assignment = Assignment(movePath, assign, assignee)

            if (isVariablePath(movePath)) {
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

    fun existingBasePaths(loanPath: LoanPath): List<MovePath> {
        val result = mutableListOf<MovePath>()
        addExistingBasePaths(loanPath, result)
        return result
    }

    /// Adds any existing move path indices for `loanPath` and any base paths of `loanPath` to `result`,
    /// but doesn't add new move paths
    fun addExistingBasePaths(loanPath: LoanPath, result: MutableList<MovePath>) {
        val movePath = pathMap[loanPath]
        if (movePath != null) {
            eachBasePath(movePath) { result.add(it) }
        } else {
            val kind = loanPath.kind
            when (kind) {
                is Downcast -> addExistingBasePaths(kind.loanPath, result)
                is Extend -> addExistingBasePaths(kind.loanPath, result)
            }
        }
    }

    fun eachBasePath(movePath: MovePath, f: (MovePath) -> Boolean): Boolean {
        var path = movePath
        while (true) {
            if (!f(path)) return false
            path = path.parent ?: return true
        }
    }
}

class FlowedMoveData(moveData: MoveData, bccx: BorrowCheckContext, cfg: ControlFlowGraph, body: RsBlock) {
    val moveData: MoveData
    val dfcxMoves: MoveDataFlow
    val dfcxAssign: AssignDataFlow

    init {
        val dfcxMoves = DataFlowContext(
            body,
            cfg,
            MoveDataFlowOperator,
            moveData.moves.size
        )
        val dfcxAssign = DataFlowContext(
            body,
            cfg,
            AssignDataFlowOperator,
            moveData.varAssignments.size
        )
        moveData.addGenKills(bccx, dfcxMoves, dfcxAssign)
        dfcxMoves.addKillsFromFlowExits()
        dfcxAssign.addKillsFromFlowExits()
        dfcxMoves.propagate()
        dfcxAssign.propagate()

        this.moveData = moveData
        this.dfcxMoves = dfcxMoves
        this.dfcxAssign = dfcxAssign
    }

    fun moveKindOfPath(element: RsElement, loanPath: LoanPath): MoveKind? {
        val movePath = moveData.pathMap[loanPath] ?: return null

        var result: MoveKind? = null
        dfcxMoves.eachGenBit(element) { moveIndex ->
            val move = moveData.moves[moveIndex]
            if (move.path == movePath) {
                result = move.kind
                false
            } else {
                true
            }
        }
        return result
    }

    fun eachMoveOf(element: RsElement, loanPath: LoanPath, f: (Move, LoanPath) -> Boolean): Boolean {
        // Bad scenarios:
        // 1. Move of `a.b.c`, use of `a.b.c`
        // 2. Move of `a.b.c`, use of `a.b.c.d`
        // 3. Move of `a.b.c`, use of `a` or `a.b`
        //
        // OK scenario:
        // 4. move of `a.b.c`, use of `a.b.d`

        val baseNodes = moveData.existingBasePaths(loanPath).takeIf { it.isNotEmpty() } ?: return true

        val movePath = moveData.pathMap[loanPath]

        var result = true
        return dfcxMoves.eachBitOnEntry(element) { index ->
            val move = moveData.moves[index]
            val movedPath = move.path
            if (baseNodes.any { it == movedPath }) {
                // Scenario 1 or 2: `loanPath` or some base path of `loanPath` was moved.
                if (!f(move, movedPath.loanPath)) {
                    result = false
                }
            } else if (movePath != null) {
                val cont = moveData.eachBasePath(movedPath) {
                    // Scenario 3: some extension of `loanPath` was moved
                    if (it == movePath) f(move, movedPath.loanPath) else true
                }
                if (!cont) result = false
            }
            result
        }
    }

    /** Iterates through every assignment to [loanPath] that may have occurred on entry to [element]. */
    fun eachAssignmentOf(element: RsElement, loanPath: LoanPath, f: (Assignment) -> Boolean): Boolean {
        val movePath = moveData.pathMap[loanPath] ?: return true

        return dfcxAssign.eachBitOnEntry(element) { index ->
            val assignment = moveData.varAssignments[index]
            assignment.path != movePath || f(assignment)
        }
    }
}

class Move(
    val path: MovePath,
    val element: RsElement,
    val kind: MoveKind,
    val index: Int,
    /** Next node in linked list of moves from `path` */
    val nextMove: Move?
)

data class MovePath(
    val loanPath: LoanPath,
    var parent: MovePath? = null,
    var firstMove: Move? = null,
    var firstChild: MovePath? = null,
    var nextSibling: MovePath? = null
)

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
    val path: MovePath,

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

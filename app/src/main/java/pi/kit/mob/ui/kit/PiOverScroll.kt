package pi.kit.mob.ui.kit

import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.utils.overScrollVertical

/**
 * A page's elastic end, stated once for the whole app.
 *
 * A page whose content fits the window used to be a page that did not move at all: a
 * directory with three files swallowed the drag that a directory with thirty answered
 * with the damped give every other scroll surface in the app has. The cause is in Compose
 * rather than in the page. `Scrollable` reaches its overscroll effect only through
 * `ScrollingLogic.getShouldDispatchOverscroll` (`Scrollable.kt`; read on the foundation
 * 1.12.1 that `compose-bom` 2026.09.00 resolves), which is gated on
 *
 *     canScrollForward || canScrollBackward || overscrollEffect.isInProgress
 *
 * A viewport with nothing to scroll to reports the first two as false, and nothing is in
 * progress on the frame a drag begins, so `applyToScroll` is never called: the scroller
 * consumes zero, the drag arrives at the next node up as unconsumed, and nothing answers
 * it. Three files and thirty files are the same page with a different number of rows; the
 * difference above is entirely that gate.
 *
 * [overScrollVertical] does not depend on it. It is a nested-scroll connection *above* the
 * scroller, so it is handed whatever the scroller could not consume — for a short page,
 * the whole drag — and it translates the content by a damped distance
 * (`SpringMath.obtainDampingDistance`, clamped to the scroller's own range) that springs
 * back when the finger lifts. The two do not double up: while the scroller *can* scroll,
 * its own effect consumes the remainder and this node is handed zero.
 *
 * ## Where it goes, and where it does not
 *
 * A page's own body, and a sheet body that stands in for one: those are the containers
 * where a drag means "this page, the whole of it". Anything that is a region *inside* a
 * page keeps no elastic edge of its own — a list bounded inside a card, a scroller inside
 * a dialog, a document body drawn inside another scrolling column, a `LazyRow`. Those
 * bounce with the page they are drawn on, and a second edge inside the first reads as a
 * bubble inside a bubble. ARCHITECTURE §9.1 is the list and the argument.
 *
 * ## Position in the chain is part of it
 *
 * On a `LazyColumn` this is added to the list's own `modifier` and that is the whole
 * change: the list's internal scrollable is a child, and a nested-scroll connection is
 * always the parent of the scrollers below it. On a `Column(Modifier.verticalScroll(…))`
 * it must come **before** the `verticalScroll` and never after: the connection finds its
 * scrollers by walking ancestors, and `DelegatableNode.visitAncestors` starts at
 * `node.parent` — a node placed after the scroller in the same chain is downstream of it
 * and is never dispatched to, so the page silently loses the bounce it just asked for.
 */
fun Modifier.piOverScroll(): Modifier = overScrollVertical()

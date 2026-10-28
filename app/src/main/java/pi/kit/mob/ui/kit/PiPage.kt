package pi.kit.mob.ui.kit

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back

/**
 * One page: a Miuix scaffold, a small top bar, and back.
 *
 * Every destination and every settings page is the same shape, so the shape is
 * written once. The page supplies its title, its top-bar actions and its body;
 * the scaffold supplies the surface colour, the status-bar inset, the popup host
 * that [Miuix]'s own dialogs draw into, and the corner clip a pushed page slides
 * under.
 *
 * [body] receives the scaffold's [PaddingValues] rather than the raw window: with
 * the bar in place those are the insets the content is actually under — the bar's
 * height at the top, the navigation strip at the bottom — and applying them is the
 * body's whole contract. A list scrolled inside the page therefore ends at the bar
 * rather than under it, and a composer typed into ends above the keyboard without
 * the page having to know the keyboard exists.
 *
 * [onBack] is null on a tab root, which is also what turns the system back gesture
 * off: a handler that does nothing is a page that swallows a gesture meant for the
 * activity.
 */
@Composable
fun PiPage(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {},
    floating: (@Composable () -> Unit)? = null,
    body: @Composable (PaddingValues) -> Unit,
) {
    if (onBack != null) {
        BackHandler(onBack = onBack)
    }

    // Pinned: the bar keeps its height while the body scrolls under it. A
    // collapsing bar is a second thing moving on a page whose subject is a list
    // of rows, and Miuix's small bar is the shape its own settings pages use.
    val scrollBehavior = MiuixScrollBehavior()

    Scaffold(
        modifier = modifier,
        topBar = {
            SmallTopAppBar(
                title = title,
                subtitle = subtitle.orEmpty(),
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = null,
                            )
                        }
                    }
                },
                actions = actions,
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize()) {
            body(padding)
            if (floating != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp, bottom = 16.dp),
                ) {
                    floating()
                }
            }
        }
    }
}

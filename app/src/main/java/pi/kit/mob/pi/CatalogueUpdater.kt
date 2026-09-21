package pi.kit.mob.pi

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Progress of the maintenance page's manual model-catalogue refresh. */
sealed interface CatalogueStatus {
    data object Idle : CatalogueStatus
    data object Running : CatalogueStatus

    /**
     * The refresh finished. [changed] is whether the catalogue the agent would resolve
     * from actually moved — a run that changed nothing is still a success, because the
     * model the user was looking for may simply not exist yet.
     */
    data class Done(val changed: Boolean) : CatalogueStatus

    data class Failed(val message: String) : CatalogueStatus
}

/**
 * The maintenance page's "refresh the model list" button.
 *
 * This is all that is left of the updater that also replaced pi itself on the device.
 * That half is gone on purpose: the runtime's packages are inputs of the image the APK
 * carries, and rewriting pi inside a live install turned out to be able to leave a tree
 * that cannot start at all. ARCHITECTURE §2 records it — a half-completed
 * `npm install -g`, an abandoned run, and then every agent start failing with
 *
 *     Failed to load extension "…/pi-safety-guard.ts": Cannot find module 'jiti'
 *
 * because 0.86.x loads the bundled TypeScript guard extension through `jiti`, which the
 * truncated install had removed.
 *
 * What remains is metadata: `pi update --models` downloads the providers' catalogues and
 * writes `models-store.json`. Its worst case is a stale or briefly wrong model *list*,
 * never an agent that will not run, so a button on a phone is the right shape for it.
 *
 * ## Why this is a class and not a call in the composable
 *
 * The refresh spawns a process and can take up to a minute ([PiProcessLauncher]'s own
 * outer bound on it), and the page it lives on is rebuilt on every navigation. This
 * object was held by `SettingsScreen` for exactly this reason when it also ran the pi
 * update, and the same reason applies: leaving the page mid-refresh must not throw the
 * run or its result away.
 *
 * It does not own the refresh itself — [PiAgentSession.requestCatalogueRefresh] does,
 * and that is deliberate. The launch path applies that function's own four-hour window
 * before calling it, so the button and the window cannot come to mean two different
 * things about the store on disk.
 */
class CatalogueUpdater(private val session: PiAgentSession) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _status = MutableStateFlow<CatalogueStatus>(CatalogueStatus.Idle)
    val status: StateFlow<CatalogueStatus> = _status.asStateFlow()

    /**
     * The version of the pi on disk, read once and again after a refresh.
     *
     * The page shows the installed version beside the button — the button cannot
     * change it any more, but the *row* is still the answer to "which pi am I running",
     * and a value read at composition time is what would go stale if a runtime reinstall
     * happened behind the page. Which is also why [refreshVersion] exists: it is called
     * when the user dismisses a result, i.e. when the page is about to be looked at
     * again.
     */
    private val _version = MutableStateFlow(PiInstallation.installedVersion(session.env))
    val version: StateFlow<String?> = _version.asStateFlow()

    private var job: Job? = null

    fun refreshVersion() {
        _version.value = PiInstallation.installedVersion(session.env)
    }

    /**
     * Starts a refresh. A second call while one is running is ignored rather than
     * queued: the run it would duplicate is already doing the work, and two `pi update
     * --models` processes writing one store would race.
     */
    fun start() {
        if (job?.isActive == true) return
        _status.value = CatalogueStatus.Running
        job = scope.launch {
            _status.value = try {
                CatalogueStatus.Done(changed = session.requestCatalogueRefresh())
            } catch (cancellation: CancellationException) {
                // The scope's own job, not a failure of the refresh: a throwable caught
                // and swallowed here would leave the coroutine machinery believing a
                // cancelled run completed.
                throw cancellation
            } catch (t: Throwable) {
                CatalogueStatus.Failed(t.message ?: t::class.java.simpleName)
            }
        }
    }

    fun dismiss() {
        if (job?.isActive == true) return
        _status.value = CatalogueStatus.Idle
    }
}

package cc.ptoe.messenger.presentation.platform

import androidx.compose.runtime.Composable
import kotlinx.coroutines.flow.StateFlow

/** Optional Android shell controller for retaining Core while schedules are enabled. */
interface BackgroundTaskPersistence {
    val enabled: StateFlow<Boolean>
    /** Returns false if Android could not start the foreground service. */
    fun setEnabled(enabled: Boolean): Boolean
}

object BackgroundTaskPersistenceRegistry {
    var controller: BackgroundTaskPersistence? = null
}

/** The shell holds a foreground-service lease for every active user-started task. */
object ForegroundTaskRegistry {
    var begin: (() -> Unit)? = null
    var end: (() -> Unit)? = null
}

/** Android supplies the setting; it is absent on platforms without a foreground service. */
@Composable
expect fun BackgroundTaskPersistenceRow(onError: (String) -> Unit)

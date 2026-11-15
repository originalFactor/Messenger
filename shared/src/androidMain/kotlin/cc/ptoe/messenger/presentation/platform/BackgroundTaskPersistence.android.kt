package cc.ptoe.messenger.presentation.platform

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import cc.ptoe.messenger.generated.resources.Res
import cc.ptoe.messenger.generated.resources.scheduled_background_description
import cc.ptoe.messenger.generated.resources.scheduled_background_failed
import cc.ptoe.messenger.generated.resources.scheduled_background_permission_denied
import cc.ptoe.messenger.generated.resources.scheduled_background_title
import cc.ptoe.messenger.presentation.ui.components.ExpressiveSwitchRow
import org.jetbrains.compose.resources.stringResource

@Composable
actual fun BackgroundTaskPersistenceRow(onError: (String) -> Unit) {
    val controller = BackgroundTaskPersistenceRegistry.controller ?: return
    val enabled by controller.enabled.collectAsState()
    val context = LocalContext.current
    val failed = stringResource(Res.string.scheduled_background_failed)
    val denied = stringResource(Res.string.scheduled_background_permission_denied)
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) onError(denied)
        else if (!controller.setEnabled(true)) onError(failed)
    }
    ExpressiveSwitchRow(
        title = stringResource(Res.string.scheduled_background_title),
        subtitle = stringResource(Res.string.scheduled_background_description),
        checked = enabled,
        onCheckedChange = { checked ->
            if (checked && Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else if (!controller.setEnabled(checked)) {
                onError(failed)
            }
        },
    )
}

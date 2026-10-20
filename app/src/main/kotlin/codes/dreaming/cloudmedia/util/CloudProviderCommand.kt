package codes.dreaming.cloudmedia.util

import android.os.Build
import codes.dreaming.cloudmedia.BuildConfig

// device_config gained override and clear_override in Android 15. Android 14 only has put and delete.
object CloudProviderCommand {
    private const val FLAG = "mediaprovider allowed_cloud_providers"
    private val hasOverride = Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM

    val enable = if (hasOverride) "device_config override $FLAG ${BuildConfig.APPLICATION_ID}"
        else "device_config put $FLAG ${BuildConfig.APPLICATION_ID}"

    val disable = if (hasOverride) "device_config clear_override $FLAG" else "device_config delete $FLAG"
}

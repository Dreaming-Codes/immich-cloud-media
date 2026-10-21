package codes.dreaming.cloudmedia.util

import android.os.Build
import codes.dreaming.cloudmedia.BuildConfig

// device_config gained override and clear_override in Android 15. Android 13 ships a MediaProvider that
// reads the allowlist from storage_native_boot as provider authorities, and Play system updates replace it
// with one that reads mediaprovider as package names. The Android 13 commands set both.
object CloudProviderCommand {
    private const val KEY = "allowed_cloud_providers"
    private const val PACKAGE = BuildConfig.APPLICATION_ID
    private const val AUTHORITY = "$PACKAGE.cloudmedia"

    // Android 13 reads storage_native_boot only when MediaProvider starts.
    val needsReboot = Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE

    val enable: List<String>
    val disable: List<String>

    init {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM -> {
                enable = listOf("device_config override mediaprovider $KEY $PACKAGE")
                disable = listOf("device_config clear_override mediaprovider $KEY")
            }
            Build.VERSION.SDK_INT == Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> {
                enable = listOf("device_config put mediaprovider $KEY $PACKAGE")
                disable = listOf("device_config delete mediaprovider $KEY")
            }
            else -> {
                enable = listOf(
                    "device_config put storage_native_boot $KEY $AUTHORITY",
                    "device_config put mediaprovider $KEY $PACKAGE",
                )
                disable = listOf(
                    "device_config delete storage_native_boot $KEY",
                    "device_config delete mediaprovider $KEY",
                )
            }
        }
    }
}

package codes.dreaming.cloudmedia.util

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import codes.dreaming.cloudmedia.BuildConfig
import codes.dreaming.cloudmedia.IShellService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import kotlin.coroutines.resume

object ShizukuHelper {

    fun isShizukuAvailable(): Boolean {
        return try {
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    fun isPermissionGranted(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    suspend fun enableProvider(): Result<String> = runShellCommand(
        "device_config override mediaprovider allowed_cloud_providers ${BuildConfig.APPLICATION_ID}"
    )

    suspend fun disableProvider(): Result<String> = runShellCommand(
        "device_config clear_override mediaprovider allowed_cloud_providers"
    )

    private suspend fun runShellCommand(command: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val service = bindShellService()
            try {
                val output = service.runCommand(command)
                Result.success(output)
            } finally {
                unbindShellService()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shell")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private var serviceConnection: ServiceConnection? = null

    private suspend fun bindShellService(): IShellService = suspendCancellableCoroutine { cont ->
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
                cont.resume(IShellService.Stub.asInterface(binder))
            }

            override fun onServiceDisconnected(name: ComponentName?) {}
        }
        serviceConnection = conn
        Shizuku.bindUserService(userServiceArgs, conn)
    }

    private fun unbindShellService() {
        serviceConnection?.let {
            try {
                Shizuku.unbindUserService(userServiceArgs, it, true)
            } catch (_: Exception) {
            }
            serviceConnection = null
        }
    }
}

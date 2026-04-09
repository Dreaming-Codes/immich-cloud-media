package codes.dreaming.cloudmedia.util

import codes.dreaming.cloudmedia.IShellService
import kotlin.system.exitProcess

class ShellService : IShellService.Stub() {

    override fun runCommand(command: String): String {
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
        val output = process.inputStream.bufferedReader().readText().trim()
        val error = process.errorStream.bufferedReader().readText().trim()
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            throw RuntimeException(error.ifEmpty { "Command failed with exit code $exitCode" })
        }
        return output
    }

    override fun destroy() {
        exitProcess(0)
    }
}

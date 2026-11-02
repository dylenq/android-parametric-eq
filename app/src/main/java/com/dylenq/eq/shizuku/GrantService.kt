package com.dylenq.eq.shizuku

import android.content.Context
import kotlin.system.exitProcess

/**
 * Shizuku user service. It runs as the shell user, so it only ever runs [GrantCommand]'s fixed argv
 * (no shell, no free-form command) and refuses any package but its own.
 */
class GrantService(context: Context) : IGrantService.Stub() {
    private val ownId = context.packageName

    override fun grantDump(pkg: String?, user: Int): String {
        val argv = GrantCommand.argv(pkg ?: "", ownId, user)
            ?: return GrantCommand.encode(1, "refused: not this app's package")
        return try {
            val p = ProcessBuilder(argv).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().use { it.readText() }
            GrantCommand.encode(p.waitFor(), out.take(4096))
        } catch (e: Exception) {
            GrantCommand.encode(1, e.javaClass.simpleName)
        }
    }

    override fun destroy() {
        exitProcess(0)
    }
}

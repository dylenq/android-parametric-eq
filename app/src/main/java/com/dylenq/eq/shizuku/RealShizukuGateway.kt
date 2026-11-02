package com.dylenq.eq.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku

/** The real thing over the Shizuku API. Not runnable on the emulator (no Shizuku server there). */
class RealShizukuGateway(private val context: Context) : ShizukuGateway {
    private val requestCode = 4242

    override fun state(): ShizukuState {
        val installed = try {
            context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
        if (!installed) return ShizukuState.NotInstalled
        if (!Shizuku.pingBinder()) return ShizukuState.NotRunning
        if (Shizuku.isPreV11()) return ShizukuState.NotRunning
        return if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) ShizukuState.Ready else ShizukuState.NoPermission
    }

    override suspend fun request(): Boolean {
        if (Shizuku.shouldShowRequestPermissionRationale()) return false
        val answer = CompletableDeferred<Boolean>()
        val listener = Shizuku.OnRequestPermissionResultListener { code, result ->
            if (code == requestCode) answer.complete(result == PackageManager.PERMISSION_GRANTED)
        }
        Shizuku.addRequestPermissionResultListener(listener)
        try {
            Shizuku.requestPermission(requestCode)
            return withTimeoutOrNull(60_000) { answer.await() } ?: false
        } finally {
            Shizuku.removeRequestPermissionResultListener(listener)
        }
    }

    override suspend fun grant(): GrantResult {
        val bound = CompletableDeferred<IGrantService?>()
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, service: IBinder) {
                bound.complete(IGrantService.Stub.asInterface(service))
            }

            override fun onServiceDisconnected(name: ComponentName) {
                bound.complete(null)
            }
        }
        val args = Shizuku.UserServiceArgs(ComponentName(context.packageName, GrantService::class.java.name))
            .processNameSuffix("grant")
            .daemon(false)
            .debuggable(false)
            .version(1)
        return try {
            Shizuku.bindUserService(args, connection)
            val service = withTimeoutOrNull(10_000) { bound.await() }
                ?: return GrantResult.Failed("Shizuku did not start the helper in time")
            try {
                val result = GrantCommand.decode(service.grantDump(context.packageName))
                runCatching { service.destroy() }
                result
            } catch (e: Exception) {
                GrantResult.Failed(e.javaClass.simpleName)
            }
        } catch (e: Exception) {
            GrantResult.Failed(e.javaClass.simpleName)
        } finally {
            runCatching { Shizuku.unbindUserService(args, connection, true) }
        }
    }

    companion object {
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    }
}

package com.dylenq.eq.shizuku

/** What the Shizuku app and server look like from here. */
enum class ShizukuState { NotInstalled, NotRunning, NoPermission, Ready }

/** Typed outcome of "Use Shizuku". Every path is a value, never a throw. */
sealed interface GrantResult {
    data object Granted : GrantResult
    /** The command ran and failed; [output] is its trimmed text. */
    data class Failed(val output: String) : GrantResult
    data object NotInstalled : GrantResult
    data object NotRunning : GrantResult
    /** The user said no to the Shizuku permission prompt. */
    data object Refused : GrantResult
}

/** Thin seam over the Shizuku API so the flow can be tested with a fake. */
interface ShizukuGateway {
    fun state(): ShizukuState

    /** Asks for the Shizuku permission; true when it was given. */
    suspend fun request(): Boolean

    /** Runs the DUMP grant through the user service. Only call when [state] is [ShizukuState.Ready]. */
    suspend fun grant(): GrantResult
}

/** Used when no real gateway is wired in (plain JVM and most tests). */
object NoShizukuGateway : ShizukuGateway {
    override fun state() = ShizukuState.NotInstalled
    override suspend fun request() = false
    override suspend fun grant(): GrantResult = GrantResult.NotInstalled
}

/** State in, result out: the order of checks behind the "Use Shizuku" button. */
object ShizukuGrantFlow {
    suspend fun run(gateway: ShizukuGateway): GrantResult = when (gateway.state()) {
        ShizukuState.NotInstalled -> GrantResult.NotInstalled
        ShizukuState.NotRunning -> GrantResult.NotRunning
        ShizukuState.NoPermission -> if (gateway.request()) afterRequest(gateway) else GrantResult.Refused
        ShizukuState.Ready -> gateway.grant()
    }

    // The server can vanish while the prompt is open.
    private suspend fun afterRequest(gateway: ShizukuGateway): GrantResult =
        if (gateway.state() == ShizukuState.Ready) gateway.grant() else GrantResult.NotRunning
}

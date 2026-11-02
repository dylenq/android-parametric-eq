package com.dylenq.eq.shizuku

/**
 * The one command EQ ever runs through Shizuku. Built from fixed parts; the package must be this app's own id,
 * so nothing else can be passed through.
 */
object GrantCommand {
    const val APP_ID = "com.dylenq.eq"
    const val PERMISSION = "android.permission.DUMP"

    /** The same command as one line, for `adb shell` and the proof script. */
    const val SHELL_LINE = "/system/bin/cmd package grant --user 0 $APP_ID $PERMISSION"

    private val PACKAGE = Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")

    /** The Android user a uid belongs to. The helper runs as the shell, so the app passes its own. */
    fun userOf(uid: Int): Int = uid / 100000

    /** The argv for [pkg] on [user], or null when [pkg] is not [ownId], is not a plain package name, or [user] is negative. */
    fun argv(pkg: String, ownId: String = APP_ID, user: Int = 0): List<String>? {
        if (pkg.isEmpty() || pkg.length > 255 || !PACKAGE.matches(pkg) || pkg != ownId || user < 0) return null
        return listOf("/system/bin/cmd", "package", "grant", "--user", user.toString(), pkg, PERMISSION)
    }

    /** Service reply: exit code on the first line, output after it. */
    fun encode(exit: Int, output: String): String = "$exit\n$output"

    fun decode(reply: String?): GrantResult {
        if (reply == null) return GrantResult.Failed("no answer from the Shizuku service")
        val exit = reply.substringBefore('\n').trim().toIntOrNull()
            ?: return GrantResult.Failed(reply.trim())
        return if (exit == 0) GrantResult.Granted else GrantResult.Failed(reply.substringAfter('\n', "").trim())
    }
}

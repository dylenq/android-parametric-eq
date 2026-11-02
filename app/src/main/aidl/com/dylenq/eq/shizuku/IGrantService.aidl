package com.dylenq.eq.shizuku;

/**
 * Runs in Shizuku's shell-level process. The only thing it can do is grant DUMP to this app.
 * Result text: the exit code on the first line, then the command's output.
 */
interface IGrantService {
    String grantDump(String pkg, int user) = 1;

    /** Shizuku's documented destroy transaction (16777114). Ends the process. */
    void destroy() = 16777114;
}

package cc.ptoe.messenger.runtime;

import cc.ptoe.messenger.runtime.IShellCallback;

interface IShellService {
    /**
     * Extracts/validates the pinned Termux bootstrap if needed.
     * Completes via callback.onFinished(requestId, exitCode, output, false):
     * exitCode 0 with output = workspace path; exitCode -1 with output = error.
     */
    oneway void ensureRuntime(int requestId, IShellCallback callback);

    /**
     * Executes one command. Output streams via callback.onOutput chunks;
     * completes via callback.onFinished(requestId, exitCode, fullOutput, timedOut).
     */
    oneway void submit(int requestId, String command, String workingDir, long timeoutMs, IShellCallback callback);

    /** Cancels a pending/running request and terminates its process. */
    oneway void cancel(int requestId);
}

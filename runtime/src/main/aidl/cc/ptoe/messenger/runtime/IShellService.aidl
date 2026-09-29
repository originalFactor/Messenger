package cc.ptoe.messenger.runtime;

import cc.ptoe.messenger.runtime.IShellCallback;
import cc.ptoe.messenger.runtime.ToolResult;

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

    // Workspace-confined file operations (synchronous; the workspace lives in
    // this app's own data directory, so the main app reaches it only here).

    cc.ptoe.messenger.runtime.ToolResult workspaceGlob(String pattern, int maxResults);
    cc.ptoe.messenger.runtime.ToolResult workspaceGrep(String pattern, String path, String fileGlob, boolean caseSensitive, int maxResults);
    cc.ptoe.messenger.runtime.ToolResult workspaceRead(String path, int startLine, int maxLines);
    cc.ptoe.messenger.runtime.ToolResult workspaceEdit(String path, String oldText, String newText, boolean replaceAll);
    cc.ptoe.messenger.runtime.ToolResult workspaceCreate(String path, String content, boolean overwrite);
}

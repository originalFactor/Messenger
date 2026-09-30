package cc.ptoe.messenger.runtime;

import cc.ptoe.messenger.runtime.IShellCallback;
import cc.ptoe.messenger.runtime.ToolResult;

interface IShellService {
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

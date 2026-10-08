package cc.ptoe.messenger.runtime;

import cc.ptoe.messenger.runtime.IMcpCallback;
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

    // Workspace file operations (synchronous). `root` is the owning project's
    // workspace directory: null/empty falls back to this app's own workspace.
    // A non-empty path must be an existing directory the OS lets this app's UID
    // reach — the companion's own UID is the sandbox, so anything else is
    // rejected with an error result instead of silently reading the default.

    cc.ptoe.messenger.runtime.ToolResult workspaceGlob(String root, String pattern, int maxResults);
    cc.ptoe.messenger.runtime.ToolResult workspaceGrep(String root, String pattern, String path, String fileGlob, boolean caseSensitive, boolean fixedString, int maxResults);
    cc.ptoe.messenger.runtime.ToolResult workspaceRead(String root, String path, int startLine, int maxLines);
    cc.ptoe.messenger.runtime.ToolResult workspaceEdit(String root, String path, String oldText, String newText, boolean replaceAll);
    cc.ptoe.messenger.runtime.ToolResult workspaceCreate(String root, String path, String content, boolean overwrite);

    // MCP command server process management
    boolean startMcpProcess(int sessionId, String command, String envJson, IMcpCallback callback);
    boolean sendMcpInput(int sessionId, String line);
    void stopMcpProcess(int sessionId);
}

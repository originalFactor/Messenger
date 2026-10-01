package cc.ptoe.messenger.runtime;

interface IMcpCallback {
    void onOutput(int requestId, String line);
    void onError(int requestId, String error);
    void onClosed(int requestId, int exitCode);
}

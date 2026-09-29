package cc.ptoe.messenger.runtime;

oneway interface IShellCallback {
    void onOutput(int requestId, String chunk);
    void onFinished(int requestId, int exitCode, String output, boolean timedOut);
}

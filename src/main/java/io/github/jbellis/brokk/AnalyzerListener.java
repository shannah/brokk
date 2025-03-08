package io.github.jbellis.brokk;

/**
 * Receives notifications from the analyzer about significant events or messages
 * that used to be sent to IConsoleIO. This lets us avoid a direct UI or I/O
 * dependency in AnalyzerWrapper.
 */
public interface AnalyzerListener
{
    /**
     * Called when the Analyzer is requested but it is not yet complete.
     */
    void onAnalyzerBlocked();

    /**
     * Called with any general messages that used to go to the UI console.
     */
    void onAnalyzerFirstBuild(String msg);
}

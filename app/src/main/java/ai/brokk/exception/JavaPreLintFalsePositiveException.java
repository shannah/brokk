package ai.brokk.exception;

/**
 * Marker exception used to report Java pre-lint false positives (cases where the lightweight
 * parse-only lint flags issues but the full project build succeeds).
 *
 * Instances of this exception are not thrown to control flow; instead, they are created
 * and passed to ExceptionReporter.tryReportException(...) so the event is uploaded for
 * monitoring/triage. The message should contain a concise summary of affected files and
 * sample diagnostics.
 */
public final class JavaPreLintFalsePositiveException extends RuntimeException {
    public JavaPreLintFalsePositiveException(String message) {
        super(message);
    }
}

package ai.brokk.gui.notifications;

import ai.brokk.IConsoleIO;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Objects;

/**
 * Immutable model for a notification.
 */
public final class NotificationEntry {
    public final IConsoleIO.NotificationRole role;
    public final String message;
    public final long timestamp;

    public NotificationEntry(IConsoleIO.NotificationRole role, String message, long timestamp) {
        this.role = Objects.requireNonNull(role);
        this.message = Objects.requireNonNull(message);
        this.timestamp = timestamp;
    }

    public NotificationEntry(IConsoleIO.NotificationRole role, String message) {
        this(role, message, System.currentTimeMillis());
    }

    public String formattedTimestamp() {
        // Human-friendly, local timezone
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                .withZone(ZoneId.systemDefault())
                .format(Instant.ofEpochMilli(timestamp));
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof NotificationEntry)) return false;
        NotificationEntry that = (NotificationEntry) o;
        return timestamp == that.timestamp && role == that.role && message.equals(that.message);
    }

    @Override
    public int hashCode() {
        return Objects.hash(role, message, timestamp);
    }

    @Override
    public String toString() {
        return "NotificationEntry{" + "role=" + role + ", ts=" + timestamp + ", message=" + message + '}';
    }

    public static Comparator<NotificationEntry> NEWEST_FIRST =
            Comparator.comparingLong((NotificationEntry e) -> e.timestamp).reversed();
}

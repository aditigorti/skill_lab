import java.time.LocalDate;
import java.time.LocalDateTime;

/** A request that was turned down, kept for the denied-requests report. */
final class DeniedRequest {

    static final String WAITING = "Waiting";
    static final String EXPIRED = "Expired";
    static final String WITHDRAWN = "Withdrawn";
    static final String GRANTED_PREFIX = "Granted ";

    final String id;
    final LocalDateTime requestedAt;
    final String equipmentId;
    final String group;
    final LocalDate date;
    final int start;
    final int end;
    final DenyReason reason;
    final String explanation;
    /** What happened after the denial (waiting list outcome). Empty if nothing. */
    String afterwards;

    DeniedRequest(String id, LocalDateTime requestedAt, String equipmentId, String group, LocalDate date,
                  int start, int end, DenyReason reason, String explanation, String afterwards) {
        this.id = id;
        this.requestedAt = requestedAt;
        this.equipmentId = equipmentId;
        this.group = group;
        this.date = date;
        this.start = start;
        this.end = end;
        this.reason = reason;
        this.explanation = explanation;
        this.afterwards = afterwards;
    }

    String afterwardsText() {
        if (afterwards == null || afterwards.isEmpty()) {
            return "";
        }
        if (WAITING.equals(afterwards)) {
            return "Joined the waiting list and is still waiting.";
        }
        if (EXPIRED.equals(afterwards)) {
            return "Joined the waiting list, but the slot never became free.";
        }
        if (WITHDRAWN.equals(afterwards)) {
            return "Joined the waiting list, then left it.";
        }
        if (afterwards.startsWith(GRANTED_PREFIX)) {
            return "Got the slot later from the waiting list (booking "
                    + afterwards.substring(GRANTED_PREFIX.length()) + ").";
        }
        return afterwards;
    }
}

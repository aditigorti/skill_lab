import java.time.LocalDateTime;

/** One line in the permanent history: something that happened, when, and to what. */
final class HistoryEntry {

    static final String BOOKED = "BOOKED";
    static final String CANCELLED = "CANCELLED";
    static final String DENIED = "DENIED";
    static final String WAITLISTED = "WAITLISTED";
    static final String GRANTED = "GRANTED";
    static final String SKIPPED = "SKIPPED";
    static final String EXPIRED = "EXPIRED";
    static final String WITHDRAWN = "WITHDRAWN";
    static final String RULE = "RULE";
    static final String EQUIPMENT = "EQUIPMENT";
    static final String GROUP = "GROUP";
    static final String RESET = "RESET";

    final LocalDateTime time;
    final String action;
    /** Booking, waiting-list or denied-request ID this line is about, if any. */
    final String ref;
    final String equipmentId;
    final String group;
    final String detail;

    HistoryEntry(LocalDateTime time, String action, String ref, String equipmentId, String group, String detail) {
        this.time = time;
        this.action = action;
        this.ref = ref;
        this.equipmentId = equipmentId;
        this.group = group;
        this.detail = detail;
    }

    String label() {
        if (BOOKED.equals(action)) return "Booked";
        if (CANCELLED.equals(action)) return "Cancelled";
        if (DENIED.equals(action)) return "Denied";
        if (WAITLISTED.equals(action)) return "Joined waiting list";
        if (GRANTED.equals(action)) return "Granted (waiting list)";
        if (SKIPPED.equals(action)) return "Passed over (rule)";
        if (EXPIRED.equals(action)) return "Waiting list expired";
        if (WITHDRAWN.equals(action)) return "Left waiting list";
        if (RULE.equals(action)) return "Rule changed";
        if (EQUIPMENT.equals(action)) return "Equipment added";
        if (GROUP.equals(action)) return "Group added";
        if (RESET.equals(action)) return "Data reset";
        return action;
    }
}

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A group waiting for a slot that another group holds. If that booking is
 * cancelled, the scheduler hands the slot over automatically.
 */
final class WaitlistEntry {

    final String id;
    final String equipmentId;
    final String group;
    final LocalDate date;
    final int start;
    final int end;
    final LocalDateTime requestedAt;
    /** The denied request this entry came from, so the denied report can say what happened next. */
    final String deniedId;

    WaitlistEntry(String id, String equipmentId, String group, LocalDate date, int start, int end,
                  LocalDateTime requestedAt, String deniedId) {
        this.id = id;
        this.equipmentId = equipmentId;
        this.group = group;
        this.date = date;
        this.start = start;
        this.end = end;
        this.requestedAt = requestedAt;
        this.deniedId = deniedId;
    }

    LocalDateTime startTime() {
        return date.atTime(start, 0);
    }
}

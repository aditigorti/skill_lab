import java.time.LocalDate;
import java.time.LocalDateTime;

/** One reservation: one item, one team, one day, whole hours. Never deleted; cancelling only marks it. */
class Booking {
    final String id, equipment, team;
    final LocalDate date;
    final int start, end; // whole hours: start 10 and end 12 means 10:00-12:00
    boolean cancelled;

    Booking(String id, String equipment, String team, LocalDate date, int start, int end, boolean cancelled) {
        this.id = id;
        this.equipment = equipment;
        this.team = team;
        this.date = date;
        this.start = start;
        this.end = end;
        this.cancelled = cancelled;
    }

    int hours() {
        return end - start;
    }

    /** True if this booking shares at least one hour with s-e. Touching (10-12 and 12-14) is not a clash. */
    boolean overlaps(int s, int e) {
        return s < end && start < e;
    }

    LocalDateTime startTime() {
        return date.atTime(start, 0);
    }

    @Override
    public String toString() {
        return String.format("%s  %-7s %-11s %s %s%s", id, equipment, team, date, Scheduler.slot(start, end),
                cancelled ? "  (cancelled)" : "");
    }
}

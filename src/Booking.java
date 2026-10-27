import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * A reservation of one item by one group, in whole hours, on one day.
 * Bookings are never deleted: cancelling only changes the status, so the
 * full history is always kept.
 */
final class Booking {

    enum Status { ACTIVE, CANCELLED }

    /** Booked straight away through menu 1. */
    static final String DIRECT = "Direct";

    /** Handed over automatically from the waiting list after a cancellation. */
    static final String FROM_WAITLIST = "Waiting list";

    final String id;
    final String equipmentId;
    final String group;
    final LocalDate date;
    /** Start hour, e.g. 10 means 10:00. */
    final int start;
    /** End hour, e.g. 12 means the booking runs until 12:00. */
    final int end;
    final LocalDateTime createdAt;
    final String source;
    Status status;
    LocalDateTime cancelledAt;

    Booking(String id, String equipmentId, String group, LocalDate date, int start, int end,
            Status status, LocalDateTime createdAt, LocalDateTime cancelledAt, String source) {
        this.id = id;
        this.equipmentId = equipmentId;
        this.group = group;
        this.date = date;
        this.start = start;
        this.end = end;
        this.status = status;
        this.createdAt = createdAt;
        this.cancelledAt = cancelledAt;
        this.source = source;
    }

    boolean isActive() {
        return status == Status.ACTIVE;
    }

    int hours() {
        return end - start;
    }

    /** True if this booking shares at least one hour with start..end. */
    boolean overlaps(int s, int e) {
        return s < end && start < e;
    }

    LocalDateTime startTime() {
        return date.atTime(start, 0);
    }

    LocalDateTime endTime() {
        return end >= 24 ? date.plusDays(1).atStartOfDay() : date.atTime(end, 0);
    }

    String statusLabel(LocalDateTime now) {
        if (!isActive()) {
            return "Cancelled";
        }
        if (!endTime().isAfter(now)) {
            return "Completed";
        }
        if (!startTime().isAfter(now)) {
            return "In use now";
        }
        return "Upcoming";
    }

    /** e.g. "CAM-01 for GroupA, Wed 28 Oct 10:00-12:00" */
    String describe() {
        return equipmentId + " for " + group + ", " + TimeUtil.shortDay(date) + " " + TimeUtil.slot(start, end);
    }
}

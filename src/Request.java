import java.time.LocalDate;

/**
 * A refused booking request. If the only problem was a clash, the team may wait for that exact slot:
 * the status is then "waiting" until the slot is handed over ("granted R012") or its time passes ("expired").
 */
class Request {
    final String id, equipment, team, reason;
    final LocalDate date;
    final int start, end;
    String status; // "refused", "waiting", "granted <booking ID>" or "expired"

    Request(String id, String equipment, String team, LocalDate date, int start, int end, String reason, String status) {
        this.id = id;
        this.equipment = equipment;
        this.team = team;
        this.date = date;
        this.start = start;
        this.end = end;
        this.reason = reason;
        this.status = status;
    }

    boolean isWaiting() {
        return status.equals("waiting");
    }

    @Override
    public String toString() {
        return String.format("%s  %-7s %-11s %s %s  %-19s %s", id, equipment, team, date, Scheduler.slot(start, end),
                reason, status);
    }
}

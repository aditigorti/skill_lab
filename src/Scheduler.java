import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

/** All the data and every rule. The menu asks and prints; the scheduler decides. Nothing here prints. */
class Scheduler {

    // The fairness rules.
    static final int OPEN = 8, CLOSE = 20; // lab hours 08:00-20:00
    static final int MAX_IN_A_ROW = 3;     // longest unbroken stretch one team may hold one item
    static final int MAX_PER_DAY = 4;      // most hours one team may hold one item on one day

    // The reasons a request can be refused.
    static final String OUTSIDE_HOURS = "outside lab hours", PAST = "time has passed", CLASH = "clash",
            OWN_OVERLAP = "already theirs", IN_A_ROW = "back-to-back limit", DAILY = "daily limit";

    final Map<String, String> equipment = new LinkedHashMap<>(); // ID -> type, e.g. CAM-01 -> Camera
    final List<String> teams = new ArrayList<>();      // in priority order: first = first in line for a freed slot
    final List<Booking> bookings = new ArrayList<>();  // every booking ever made, cancelled ones too
    final List<Request> requests = new ArrayList<>();  // every refused request, waiting ones too
    final List<String> history = new ArrayList<>();    // one line per event, oldest first

    /** The current time. Tests and demos can fix it with -Dscheduler.now=2026-10-27T09:00 */
    static LocalDateTime now() {
        String fixed = System.getProperty("scheduler.now");
        return fixed != null ? LocalDateTime.parse(fixed) : LocalDateTime.now().withSecond(0).withNano(0);
    }

    // ----- Checking a request against the rules -----

    /** Why a request would be refused: one reason and one message per problem. Empty means it can be booked. */
    static class Check {
        final List<String> reasons = new ArrayList<>(), messages = new ArrayList<>();

        void add(String reason, String message) {
            reasons.add(reason);
            messages.add(message);
        }

        boolean ok() {
            return reasons.isEmpty();
        }

        /** Waiting only helps if every problem is another team holding the slot; a broken rule never goes away. */
        boolean canWait() {
            return !ok() && Collections.frequency(reasons, CLASH) == reasons.size();
        }
    }

    /** Checks a request without changing anything: lab hours, past, clashes, back-to-back limit, daily limit. */
    Check check(String item, String team, LocalDate date, int start, int end) {
        Check c = new Check();
        if (start < OPEN || end > CLOSE) {
            c.add(OUTSIDE_HOURS, "The lab is open " + slot(OPEN, CLOSE) + ".");
            return c;
        }
        if (date.atTime(start, 0).isBefore(now())) {
            c.add(PAST, "That time has already passed.");
            return c;
        }
        List<Booking> own = new ArrayList<>(); // this team's bookings of this item on this day
        for (Booking b : activeBookings(item, date)) {
            if (b.team.equals(team)) {
                own.add(b);
                if (b.overlaps(start, end)) {
                    c.add(OWN_OVERLAP, team + " already has " + item + " " + slot(b.start, b.end) + " (" + b.id + ").");
                }
            } else if (b.overlaps(start, end)) {
                c.add(CLASH, item + " is already booked by " + b.team + " " + slot(b.start, b.end) + " (" + b.id + ").");
            }
        }
        if (c.reasons.contains(OWN_OVERLAP)) {
            return c;
        }
        // Back-to-back limit: join the request with the team's own bookings that touch it, and measure the stretch.
        int from = start, to = end;
        boolean grew = true;
        while (grew) {
            grew = false;
            for (Booking b : own) {
                if (b.end == from) { from = b.start; grew = true; }
                if (b.start == to) { to = b.end; grew = true; }
            }
        }
        if (to - from > MAX_IN_A_ROW) {
            c.add(IN_A_ROW, team + " would hold " + item + " for " + (to - from) + " hours in a row ("
                    + slot(from, to) + "); the limit is " + MAX_IN_A_ROW + ".");
        }
        // Daily limit: every hour this team holds this item that day, joined or not.
        int used = 0;
        for (Booking b : own) {
            used += b.hours();
        }
        if (used + (end - start) > MAX_PER_DAY) {
            c.add(DAILY, team + " would have " + (used + end - start) + " hours on " + item
                    + " that day; the limit is " + MAX_PER_DAY + ".");
        }
        return c;
    }

    // ----- Booking, refusing, waiting and cancelling -----

    /** What happened to a request: a booking, or a refused request with the check that explains why. */
    static class Outcome {
        Booking booking;
        Request refused;
        Check check;
    }

    /** Tries to book. An impossible request throws an error and records nothing; a rule break is refused and recorded. */
    Outcome request(String item, String team, LocalDate date, int start, int end) {
        requireValid(item, team, date, start, end);
        Outcome o = new Outcome();
        o.check = check(item, team, date, start, end);
        if (o.check.ok()) {
            o.booking = addBooking(item, team, date, start, end, "Booked");
        } else {
            o.refused = new Request(nextId("D", requests.size()), item, team, date, start, end,
                    o.check.reasons.get(0), "refused");
            requests.add(o.refused);
            log("Refused", o.refused.id, item + " " + team + " " + date + " " + slot(start, end) + ": "
                    + String.join(" ", o.check.messages));
        }
        return o;
    }

    /** Stops requests that make no sense at all. These are mistakes, not rule breaks, so nothing is recorded. */
    private void requireValid(String item, String team, LocalDate date, int start, int end) {
        if (!equipment.containsKey(item)) throw new IllegalArgumentException("There is no equipment " + item + ".");
        if (!teams.contains(team)) throw new IllegalArgumentException("There is no team " + team + ".");
        if (date == null) throw new IllegalArgumentException("A booking needs a date.");
        if (start < 0 || end > 24 || end <= start) {
            throw new IllegalArgumentException("Hours must be 0 to 24, with the end after the start.");
        }
    }

    private Booking addBooking(String item, String team, LocalDate date, int start, int end, String action) {
        Booking b = new Booking(nextId("R", bookings.size()), item, team, date, start, end, false);
        bookings.add(b);
        log(action, b.id, item + " " + team + " " + date + " " + slot(start, end));
        return b;
    }

    /** The team waits for the exact slot it was refused. Allowed once, and only when the only problem is a clash. */
    void joinWaitingList(Request r) {
        if (!r.status.equals("refused") || !check(r.equipment, r.team, r.date, r.start, r.end).canWait()) {
            throw new IllegalStateException(r.id + " cannot wait: only a request refused for a clash can wait, once.");
        }
        r.status = "waiting";
        log("Waiting", r.id, r.team + " waits for " + r.equipment + " " + r.date + " " + slot(r.start, r.end));
    }

    /** Cancels a booking that has not started, then hands the freed time on. Returns what happened, to show. */
    List<String> cancel(Booking b) {
        if (b.cancelled) throw new IllegalStateException(b.id + " is already cancelled.");
        if (!b.startTime().isAfter(now())) throw new IllegalStateException(b.id + " has already started.");
        b.cancelled = true;
        log("Cancelled", b.id, b.equipment + " " + b.team + " " + b.date + " " + slot(b.start, b.end));
        return fillFromWaitingList(b);
    }

    /** Gives freed time to waiting teams, highest in the priority line first. Someone who would now break a rule is
     *  passed over openly and keeps their place. Repeats, because one long slot can serve two shorter waits. */
    List<String> fillFromWaitingList(Booking freed) {
        expireOldWaits();
        List<String> notes = new ArrayList<>();
        while (true) {
            Request best = null;
            for (Request w : requests) {
                if (!w.isWaiting() || !w.equipment.equals(freed.equipment) || !w.date.equals(freed.date)) continue;
                Check c = check(w.equipment, w.team, w.date, w.start, w.end);
                if (c.ok()) {
                    if (best == null || placeInLine(w.team) < placeInLine(best.team)) best = w;
                } else if (!c.reasons.contains(CLASH)) {
                    String note = w.team + " was passed over (" + w.id + "): " + String.join(" ", c.messages);
                    if (!notes.contains(note)) {
                        notes.add(note);
                        log("Passed over", w.id, note);
                    }
                }
            }
            if (best == null) return notes;
            Booking nb = addBooking(best.equipment, best.team, best.date, best.start, best.end, "Granted (waited)");
            best.status = "granted " + nb.id;
            moveToBackOfQueue(best.team);
            notes.add("Handed to " + best.team + " from the waiting list as " + nb.id + ".");
        }
    }

    /** The winner goes to the back of the line; everyone behind it moves up one place and keeps their order. */
    void moveToBackOfQueue(String team) {
        if (teams.remove(team)) teams.add(team);
    }

    /** 0 means first in line for the next freed slot. */
    int placeInLine(String team) {
        return teams.indexOf(team);
    }

    /** Waits whose start time has passed can never be granted, so they are closed. Returns how many. */
    int expireOldWaits() {
        int count = 0;
        for (Request w : requests) {
            if (w.isWaiting() && !w.date.atTime(w.start, 0).isAfter(now())) {
                w.status = "expired";
                log("Expired", w.id, "the time passed while " + w.team + " was waiting");
                count++;
            }
        }
        return count;
    }

    // ----- Suggestions -----

    /** Start hours on this day when this team could book this item for this many hours. */
    List<Integer> freeStarts(String item, String team, LocalDate date, int hours) {
        List<Integer> starts = new ArrayList<>();
        for (int h = OPEN; h + hours <= CLOSE; h++) {
            if (check(item, team, date, h, h + hours).ok()) starts.add(h);
        }
        return starts;
    }

    /** Other items of the same type that this team could book at exactly this time. */
    List<String> sameTypeAlternatives(String item, String team, LocalDate date, int start, int end) {
        List<String> found = new ArrayList<>();
        for (String other : equipment.keySet()) {
            boolean sameType = equipment.get(other).equals(equipment.get(item));
            if (!other.equals(item) && sameType && check(other, team, date, start, end).ok()) found.add(other);
        }
        return found;
    }

    // ----- Looking things up, the usage report, the history -----

    /** Bookings of one item on one day that are not cancelled, earliest first. */
    List<Booking> activeBookings(String item, LocalDate date) {
        List<Booking> list = new ArrayList<>();
        for (Booking b : bookings) {
            if (!b.cancelled && b.equipment.equals(item) && b.date.equals(date)) list.add(b);
        }
        list.sort(Comparator.comparingInt(b -> b.start));
        return list;
    }

    /** A team's bookings that have not started yet, soonest first. These are the ones it can cancel. */
    List<Booking> upcoming(String team) {
        List<Booking> list = new ArrayList<>();
        for (Booking b : bookings) {
            if (!b.cancelled && b.team.equals(team) && b.startTime().isAfter(now())) list.add(b);
        }
        list.sort(Comparator.comparing(Booking::startTime));
        return list;
    }

    Booking findBooking(String id) {
        for (Booking b : bookings) {
            if (b.id.equalsIgnoreCase(id.trim())) return b;
        }
        return null;
    }

    /** Booked hours between two dates, per item (perTeam false) or per team (perTeam true). Cancelled ones don't count. */
    Map<String, Integer> usage(LocalDate from, LocalDate to, boolean perTeam) {
        Map<String, Integer> hours = new LinkedHashMap<>();
        Collection<String> keys = perTeam ? teams : equipment.keySet();
        for (String key : keys) hours.put(key, 0);
        for (Booking b : bookings) {
            if (b.cancelled || b.date.isBefore(from) || b.date.isAfter(to)) continue;
            String key = perTeam ? b.team : b.equipment;
            hours.put(key, hours.getOrDefault(key, 0) + b.hours());
        }
        return hours;
    }

    void log(String action, String ref, String detail) {
        history.add(now().toString().replace('T', ' ') + " | " + action + " | " + ref + " | " + detail);
    }

    static String slot(int start, int end) {
        return String.format("%02d:00-%02d:00", start, end);
    }

    /** IDs count up: R001, R002... Nothing is ever deleted, so the list size gives the next number. */
    private static String nextId(String prefix, int countSoFar) {
        return String.format("%s%03d", prefix, countSoFar + 1);
    }
}

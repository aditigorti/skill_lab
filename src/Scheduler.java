import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The heart of the program. It holds all equipment, groups and bookings, applies
 * the fairness rules, runs the waiting list and keeps the history.
 *
 * It never reads from or prints to the console (ConsoleUI does that), so every
 * rule lives in exactly one place.
 */
final class Scheduler {

    /** Pass this as the group when checking times for "any group". */
    static final String ANY_GROUP = "";

    final Map<String, Equipment> equipment = new LinkedHashMap<String, Equipment>();
    final List<String> groups = new ArrayList<String>();
    /**
     * The priority queue for cancelled slots: group names in order, first name = first in line.
     * It is a plain list used as a queue (not Java's PriorityQueue class). When a group wins a
     * cancelled slot it moves to the end; everyone else keeps their order.
     */
    final List<String> priorityQueue = new ArrayList<String>();
    final List<Booking> bookings = new ArrayList<Booking>();
    final List<WaitlistEntry> waitlist = new ArrayList<WaitlistEntry>();
    final List<DeniedRequest> denied = new ArrayList<DeniedRequest>();
    final List<HistoryEntry> history = new ArrayList<HistoryEntry>();
    final Policy policy = new Policy();
    int nextBookingNo = 1;
    int nextWaitNo = 1;
    int nextDeniedNo = 1;
    /** Stamp of the save this copy matches; used to notice changes made by another window. */
    String savedStamp = "";
    /**
     * True after a reload failed: the files on disk are newer than this copy, so saving now could
     * overwrite good data with old data. Storage.save refuses while this is true.
     */
    boolean saveBlocked = false;

    void clearAll() {
        savedStamp = "";
        saveBlocked = false;
        equipment.clear();
        groups.clear();
        priorityQueue.clear();
        bookings.clear();
        waitlist.clear();
        denied.clear();
        history.clear();
        policy.reset();
        nextBookingNo = 1;
        nextWaitNo = 1;
        nextDeniedNo = 1;
    }

    /** Makes this scheduler an exact copy of another one. Used only after a load has fully succeeded. */
    void replaceWith(Scheduler other) {
        equipment.clear();
        equipment.putAll(other.equipment);
        groups.clear();
        groups.addAll(other.groups);
        priorityQueue.clear();
        priorityQueue.addAll(other.priorityQueue);
        bookings.clear();
        bookings.addAll(other.bookings);
        waitlist.clear();
        waitlist.addAll(other.waitlist);
        denied.clear();
        denied.addAll(other.denied);
        history.clear();
        history.addAll(other.history);
        policy.copyFrom(other.policy);
        nextBookingNo = other.nextBookingNo;
        nextWaitNo = other.nextWaitNo;
        nextDeniedNo = other.nextDeniedNo;
        savedStamp = other.savedStamp;
        saveBlocked = false;
    }

    // ===================================================================
    // Looking things up
    // ===================================================================

    Equipment findEquipment(String id) {
        return id == null ? null : equipment.get(id.trim().toUpperCase(Locale.ROOT));
    }

    /** Returns the group's name as stored (ignoring capitals), or null if there is no such group. */
    String findGroup(String name) {
        if (name == null) {
            return null;
        }
        for (String g : groups) {
            if (g.equalsIgnoreCase(name.trim())) {
                return g;
            }
        }
        return null;
    }

    Booking findBooking(String id) {
        for (Booking b : bookings) {
            if (b.id.equalsIgnoreCase(id.trim())) {
                return b;
            }
        }
        return null;
    }

    WaitlistEntry findWaitlistEntry(String id) {
        for (WaitlistEntry w : waitlist) {
            if (w.id.equalsIgnoreCase(id.trim())) {
                return w;
            }
        }
        return null;
    }

    DeniedRequest findDenied(String id) {
        for (DeniedRequest d : denied) {
            if (d.id.equalsIgnoreCase(id.trim())) {
                return d;
            }
        }
        return null;
    }

    /** Active (not cancelled) bookings for one item on one day, earliest first. */
    List<Booking> activeBookings(String equipmentId, LocalDate date) {
        List<Booking> list = new ArrayList<Booking>();
        for (Booking b : bookings) {
            if (b.isActive() && b.equipmentId.equals(equipmentId) && b.date.equals(date)) {
                list.add(b);
            }
        }
        sortByTime(list);
        return list;
    }

    /** Active bookings that have not finished yet. Pass null for every group. */
    List<Booking> currentAndUpcoming(String group) {
        LocalDateTime now = TimeUtil.now();
        List<Booking> list = new ArrayList<Booking>();
        for (Booking b : bookings) {
            if (b.isActive() && b.endTime().isAfter(now) && (group == null || b.group.equals(group))) {
                list.add(b);
            }
        }
        sortByTime(list);
        return list;
    }

    /** Active bookings of one group that have not started yet, so can still be cancelled. */
    List<Booking> cancellableBookings(String group) {
        LocalDateTime now = TimeUtil.now();
        List<Booking> list = new ArrayList<Booking>();
        for (Booking b : bookings) {
            if (b.isActive() && b.group.equals(group) && b.startTime().isAfter(now)) {
                list.add(b);
            }
        }
        sortByTime(list);
        return list;
    }

    static void sortByTime(List<Booking> list) {
        list.sort((a, b) -> {
            int c = a.date.compareTo(b.date);
            if (c == 0) c = Integer.compare(a.start, b.start);
            if (c == 0) c = a.equipmentId.compareTo(b.equipmentId);
            return c;
        });
    }

    // ===================================================================
    // Checking a request against the rules
    // ===================================================================

    /** The result of checking one request: empty means it can be booked. */
    static final class Check {
        final List<DenyReason> reasons = new ArrayList<DenyReason>();
        final List<String> messages = new ArrayList<String>();

        void add(DenyReason reason, String message) {
            reasons.add(reason);
            messages.add(message);
        }

        boolean ok() {
            return reasons.isEmpty();
        }

        /** Only a clash with another group is worth waiting for; breaking a rule never goes away by waiting. */
        boolean canWaitlist() {
            if (reasons.isEmpty()) {
                return false;
            }
            for (DenyReason r : reasons) {
                if (r != DenyReason.CLASH) {
                    return false;
                }
            }
            return true;
        }

        DenyReason mainReason() {
            return reasons.get(0);
        }

        String explanation() {
            return String.join(" ", messages);
        }
    }

    /**
     * Checks a request without changing anything. The order is:
     * 1. inside lab hours, 2. not in the past, 3. no clash, 4. back-to-back limit, 5. daily limit.
     */
    Check check(String equipmentId, String group, LocalDate date, int start, int end) {
        Check c = new Check();
        Policy p = policy;

        if (start < p.openHour || end > p.closeHour) {
            c.add(DenyReason.OUTSIDE_LAB_HOURS, "The lab is open " + TimeUtil.slot(p.openHour, p.closeHour)
                    + ", and " + TimeUtil.slot(start, end) + " falls outside those hours.");
            return c;
        }

        LocalDateTime now = TimeUtil.now();
        if (date.atTime(start, 0).isBefore(now)) {
            c.add(DenyReason.IN_THE_PAST, TimeUtil.shortDay(date) + " " + TimeUtil.hour(start)
                    + " has already passed (it is now " + TimeUtil.stamp(now) + ").");
            return c;
        }

        // Clashes: another group's booking that shares any hour with this request.
        List<Booking> own = new ArrayList<Booking>();
        boolean ownOverlap = false;
        for (Booking b : activeBookings(equipmentId, date)) {
            boolean overlaps = b.overlaps(start, end);
            if (b.group.equals(group)) {
                own.add(b);
                if (overlaps) {
                    ownOverlap = true;
                    c.add(DenyReason.OWN_OVERLAP, group + " already has " + equipmentId + " from "
                            + TimeUtil.hour(b.start) + " to " + TimeUtil.hour(b.end) + " (" + b.id
                            + "), which overlaps this request.");
                }
            } else if (overlaps) {
                c.add(DenyReason.CLASH, equipmentId + " is already booked by " + b.group + " from "
                        + TimeUtil.hour(b.start) + " to " + TimeUtil.hour(b.end) + " (" + b.id + ").");
            }
        }
        if (ownOverlap) {
            return c;
        }

        int wanted = end - start;

        // Back-to-back limit: join this request with the group's own bookings that touch it,
        // and measure the whole unbroken stretch.
        if (p.maxHoursInARow > 0) {
            int from = start;
            int to = end;
            boolean grew = true;
            while (grew) {
                grew = false;
                for (Booking b : own) {
                    if (b.end == from) {
                        from = b.start;
                        grew = true;
                    }
                    if (b.start == to) {
                        to = b.end;
                        grew = true;
                    }
                }
            }
            int stretch = to - from;
            if (stretch > p.maxHoursInARow) {
                if (from == start && to == end) {
                    c.add(DenyReason.BACK_TO_BACK_LIMIT, "One booking can be at most "
                            + TimeUtil.hours(p.maxHoursInARow) + " long; this one is " + TimeUtil.hours(wanted) + ".");
                } else {
                    c.add(DenyReason.BACK_TO_BACK_LIMIT, "With " + group + "'s booking right next to it, "
                            + group + " would hold " + equipmentId + " for " + TimeUtil.hours(stretch)
                            + " in a row (" + TimeUtil.slot(from, to) + "). The limit is "
                            + TimeUtil.hours(p.maxHoursInARow) + " in a row.");
                }
            }
        }

        // Daily limit: total hours this group holds this item on this day.
        if (p.maxHoursPerDay > 0) {
            int used = 0;
            for (Booking b : own) {
                used += b.hours();
            }
            if (used + wanted > p.maxHoursPerDay) {
                if (used == 0) {
                    c.add(DenyReason.DAILY_LIMIT, "A group can have at most " + TimeUtil.hours(p.maxHoursPerDay)
                            + " on one item per day; this request is " + TimeUtil.hours(wanted) + ".");
                } else {
                    c.add(DenyReason.DAILY_LIMIT, group + " already has " + TimeUtil.hours(used) + " on "
                            + equipmentId + " that day; this would make " + TimeUtil.hours(used + wanted)
                            + " (daily limit " + TimeUtil.hours(p.maxHoursPerDay) + ").");
                }
            }
        }
        return c;
    }

    // ===================================================================
    // Booking, denying and cancelling
    // ===================================================================

    /** What happened to a request: exactly one of booking or denial is set. */
    static final class Outcome {
        Booking booking;
        DeniedRequest denial;
        Check check;
    }

    /**
     * Tries to book. Either creates the booking or records the denial.
     * Throws IllegalArgumentException (and records nothing) if the request is impossible.
     */
    Outcome request(String equipmentId, String group, LocalDate date, int start, int end) {
        requireValidRequest(equipmentId, group, date, start, end);
        Outcome o = new Outcome();
        o.check = check(equipmentId, group, date, start, end);
        LocalDateTime now = TimeUtil.now();
        if (o.check.ok()) {
            o.booking = addBooking(equipmentId, group, date, start, end, now);
        } else {
            o.denial = addDenial(equipmentId, group, date, start, end, now,
                    o.check.mainReason(), o.check.explanation());
        }
        return o;
    }

    /**
     * Checks that a request makes sense at all: an equipment ID and group name exactly as stored,
     * a date, whole hours from 0 to 24, and an end after the start. These are not fairness
     * questions (those are refused and recorded by check()); a request that fails here is a
     * mistake in the calling code, so it is stopped with an error before anything is recorded.
     */
    private void requireValidRequest(String equipmentId, String group, LocalDate date, int start, int end) {
        if (!equipment.containsKey(equipmentId)) {
            throw new IllegalArgumentException("There is no equipment with ID '" + equipmentId + "'.");
        }
        if (!groups.contains(group)) {
            throw new IllegalArgumentException("There is no group called '" + group + "'.");
        }
        if (date == null) {
            throw new IllegalArgumentException("A booking needs a date.");
        }
        if (start < 0 || start > 23 || end < 1 || end > 24) {
            throw new IllegalArgumentException("Hours must be whole hours from 0 to 24, not " + start + " to " + end + ".");
        }
        if (end <= start) {
            throw new IllegalArgumentException("The end hour (" + end + ") must be after the start hour (" + start + ").");
        }
    }

    Booking addBooking(String equipmentId, String group, LocalDate date, int start, int end, LocalDateTime at) {
        Booking b = new Booking(nextId("R", nextBookingNo++), equipmentId, group, date, start, end,
                Booking.Status.ACTIVE, at, null, Booking.DIRECT);
        bookings.add(b);
        log(at, HistoryEntry.BOOKED, b.id, equipmentId, group, slotText(date, start, end));
        return b;
    }

    DeniedRequest addDenial(String equipmentId, String group, LocalDate date, int start, int end,
                            LocalDateTime at, DenyReason reason, String explanation) {
        DeniedRequest d = new DeniedRequest(nextId("D", nextDeniedNo++), at, equipmentId, group, date,
                start, end, reason, explanation, "");
        denied.add(d);
        log(at, HistoryEntry.DENIED, d.id, equipmentId, group, slotText(date, start, end) + ", " + reason.shortLabel);
        return d;
    }

    WaitlistEntry joinWaitlist(DeniedRequest d) {
        return joinWaitlist(d, TimeUtil.now());
    }

    WaitlistEntry joinWaitlist(DeniedRequest d, LocalDateTime at) {
        WaitlistEntry w = new WaitlistEntry(nextId("W", nextWaitNo++), d.equipmentId, d.group, d.date,
                d.start, d.end, at, d.id);
        waitlist.add(w);
        d.afterwards = DeniedRequest.WAITING;
        log(at, HistoryEntry.WAITLISTED, w.id, d.equipmentId, d.group,
                slotText(d.date, d.start, d.end) + " (after " + d.id + ")");
        return w;
    }

    /** What happened to freed time: who got it, and who was passed over and why. */
    static final class Handover {
        final List<Booking> granted = new ArrayList<Booking>();
        /** Plain-English notes about waiting groups that could not take the time because of a rule. */
        final List<String> skipped = new ArrayList<String>();
    }

    /**
     * Cancels a booking that has not started yet, then offers the freed time to
     * the waiting list. Throws IllegalStateException if it is already cancelled or has started.
     */
    Handover cancel(Booking b) {
        LocalDateTime now = TimeUtil.now();
        if (!b.isActive()) {
            throw new IllegalStateException(b.id + " is already cancelled.");
        }
        if (!b.startTime().isAfter(now)) {
            throw new IllegalStateException(b.id + " has already started or finished, so it cannot be cancelled.");
        }
        markCancelled(b, now);
        return fillFromWaitlist(b, now);
    }

    void markCancelled(Booking b, LocalDateTime at) {
        b.status = Booking.Status.CANCELLED;
        b.cancelledAt = at;
        log(at, HistoryEntry.CANCELLED, b.id, b.equipmentId, b.group, slotText(b.date, b.start, b.end));
    }

    /**
     * Gives freed time to waiting groups. Repeats until nobody else fits, because
     * one long cancellation can satisfy two shorter waiting requests.
     */
    Handover fillFromWaitlist(Booking freed, LocalDateTime at) {
        expireWaitlist();
        Handover result = new Handover();
        List<String> alreadyReported = new ArrayList<String>();
        while (true) {
            WaitlistEntry best = null;
            for (WaitlistEntry w : waitlist) {
                if (!w.equipmentId.equals(freed.equipmentId) || !w.date.equals(freed.date)) {
                    continue;
                }
                Check c = check(w.equipmentId, w.group, w.date, w.start, w.end);
                if (!c.ok()) {
                    // The time is free for them, but a fairness rule blocks it: say so, openly.
                    if (!c.reasons.contains(DenyReason.CLASH) && !alreadyReported.contains(w.id)) {
                        alreadyReported.add(w.id);
                        result.skipped.add(w.group + " was waiting (" + w.id + ") but was passed over: "
                                + c.explanation());
                        log(at, HistoryEntry.SKIPPED, w.id, w.equipmentId, w.group, slotText(w.date, w.start, w.end)
                                + " (" + c.mainReason().shortLabel + ", freed by " + freed.id + ")");
                    }
                    continue;
                }
                if (best == null || isAheadOf(w, best)) {
                    best = w;
                }
            }
            if (best == null) {
                return result;
            }
            result.granted.add(grant(best, freed.id, at));
        }
    }

    /** Turns a waiting-list entry into a real booking and moves that group to the back of the queue. */
    Booking grant(WaitlistEntry w, String freedBookingId, LocalDateTime at) {
        Booking nb = new Booking(nextId("R", nextBookingNo++), w.equipmentId, w.group, w.date, w.start, w.end,
                Booking.Status.ACTIVE, at, null, Booking.FROM_WAITLIST);
        bookings.add(nb);
        waitlist.remove(w);
        DeniedRequest d = findDenied(w.deniedId);
        if (d != null) {
            d.afterwards = DeniedRequest.GRANTED_PREFIX + nb.id;
        }
        String how = policy.rotatingPriority ? "rotating priority" : "first come, first served";
        log(at, HistoryEntry.GRANTED, nb.id, w.equipmentId, w.group, slotText(w.date, w.start, w.end)
                + " (from " + w.id + ", freed by " + freedBookingId + ", " + how + ")");
        if (policy.rotatingPriority) {
            moveToBackOfQueue(w.group);
        }
        return nb;
    }

    /** Takes the group out of the queue and puts it at the end. Everyone else keeps their order. */
    void moveToBackOfQueue(String group) {
        if (priorityQueue.remove(group)) {
            priorityQueue.add(group);
        }
    }

    /**
     * Rebuilds the queue from a saved order: keeps only real groups, drops repeats, and adds
     * any group the saved order missed at the end.
     */
    void restorePriorityQueue(List<String> savedOrder) {
        priorityQueue.clear();
        for (String name : savedOrder) {
            String g = findGroup(name);
            if (g != null && !priorityQueue.contains(g)) {
                priorityQueue.add(g);
            }
        }
        for (String g : groups) {
            if (!priorityQueue.contains(g)) {
                priorityQueue.add(g);
            }
        }
    }

    // ===================================================================
    // Rotating priority and the waiting list
    // ===================================================================

    /** True if a should get a freed slot before b. */
    boolean isAheadOf(WaitlistEntry a, WaitlistEntry b) {
        if (policy.rotatingPriority) {
            int pa = placeInLine(a.group);
            int pb = placeInLine(b.group);
            if (pa != pb) {
                return pa < pb;
            }
        }
        return a.requestedAt.isBefore(b.requestedAt);
    }

    /** 0 means first in line for the next freed slot. */
    int placeInLine(String group) {
        int place = priorityQueue.indexOf(group);
        return place < 0 ? Integer.MAX_VALUE : place;
    }

    /** Groups in the order they would get the next freed slot (a copy, so callers cannot change it). */
    List<String> priorityLine() {
        return new ArrayList<String>(priorityQueue);
    }

    /** Waiting list ordered by slot, then by who would get it first. */
    List<WaitlistEntry> sortedWaitlist() {
        List<WaitlistEntry> list = new ArrayList<WaitlistEntry>(waitlist);
        list.sort((a, b) -> {
            int c = a.date.compareTo(b.date);
            if (c == 0) c = Integer.compare(a.start, b.start);
            if (c == 0) c = a.equipmentId.compareTo(b.equipmentId);
            if (c == 0) c = isAheadOf(a, b) ? -1 : (isAheadOf(b, a) ? 1 : 0);
            return c;
        });
        return list;
    }

    /** Removes waiting-list entries whose start time has passed. Returns how many. */
    int expireWaitlist() {
        LocalDateTime now = TimeUtil.now();
        int removed = 0;
        for (Iterator<WaitlistEntry> it = waitlist.iterator(); it.hasNext(); ) {
            WaitlistEntry w = it.next();
            if (!w.startTime().isAfter(now)) {
                it.remove();
                removed++;
                DeniedRequest d = findDenied(w.deniedId);
                if (d != null) {
                    d.afterwards = DeniedRequest.EXPIRED;
                }
                log(now, HistoryEntry.EXPIRED, w.id, w.equipmentId, w.group,
                        slotText(w.date, w.start, w.end) + " (slot never became free)");
            }
        }
        return removed;
    }

    /** Takes a request off the waiting list. Throws IllegalStateException if it is no longer on it. */
    void withdraw(WaitlistEntry w) {
        LocalDateTime now = TimeUtil.now();
        if (!waitlist.remove(w)) {
            throw new IllegalStateException(w.id + " is not on the waiting list any more.");
        }
        DeniedRequest d = findDenied(w.deniedId);
        if (d != null) {
            d.afterwards = DeniedRequest.WITHDRAWN;
        }
        log(now, HistoryEntry.WITHDRAWN, w.id, w.equipmentId, w.group, slotText(w.date, w.start, w.end));
    }

    // ===================================================================
    // Suggestions
    // ===================================================================

    /** Unbooked stretches of an item on a day, within lab hours and not in the past. Each is {start, end}. */
    List<int[]> freeBlocks(String equipmentId, LocalDate date) {
        List<int[]> blocks = new ArrayList<int[]>();
        LocalDateTime now = TimeUtil.now();
        if (date.isBefore(now.toLocalDate())) {
            return blocks;
        }
        int from = policy.openHour;
        if (date.equals(now.toLocalDate())) {
            int nextFullHour = now.getMinute() == 0 ? now.getHour() : now.getHour() + 1;
            from = Math.max(from, nextFullHour);
        }
        boolean[] busy = new boolean[24];
        for (Booking b : activeBookings(equipmentId, date)) {
            for (int h = b.start; h < b.end && h < 24; h++) {
                busy[h] = true;
            }
        }
        int h = from;
        while (h < policy.closeHour) {
            if (busy[h]) {
                h++;
                continue;
            }
            int s = h;
            while (h < policy.closeHour && !busy[h]) {
                h++;
            }
            blocks.add(new int[] { s, h });
        }
        return blocks;
    }

    /** The longest single booking the rules allow: the smaller of the two limits, and never more than the lab day. */
    int longestAllowedBooking() {
        int longest = policy.hoursPerDay();
        if (policy.maxHoursInARow > 0) longest = Math.min(longest, policy.maxHoursInARow);
        if (policy.maxHoursPerDay > 0) longest = Math.min(longest, policy.maxHoursPerDay);
        return Math.max(1, longest);
    }

    /** Start hours at which this group could book this item for the given hours, passing every rule. */
    List<Integer> bookableStarts(String equipmentId, String group, LocalDate date, int hours) {
        List<Integer> starts = new ArrayList<Integer>();
        for (int s = policy.openHour; s + hours <= policy.closeHour; s++) {
            if (check(equipmentId, group, date, s, s + hours).ok()) {
                starts.add(s);
            }
        }
        return starts;
    }

    /** Start times found by suggestTimes, day by day. */
    static final class Suggestion {
        /** The length the suggestions use (may be shorter than asked, if the rules cap it). */
        int hours;
        boolean shortened;
        int longest;
        LocalDate searchedFrom;
        final List<LocalDate> days = new ArrayList<LocalDate>();
        final List<List<Integer>> starts = new ArrayList<List<Integer>>();
    }

    /**
     * Start times that pass every rule for this group, looking up to 7 days ahead
     * and returning at most daysToShow days that have any.
     */
    Suggestion suggestTimes(String equipmentId, String group, LocalDate from, int hours, int daysToShow) {
        Suggestion sg = new Suggestion();
        sg.longest = longestAllowedBooking();
        sg.shortened = hours > sg.longest;
        sg.hours = Math.min(hours, sg.longest);
        LocalDate today = TimeUtil.today();
        sg.searchedFrom = from.isBefore(today) ? today : from;
        LocalDate day = sg.searchedFrom;
        for (int i = 0; i < 7 && sg.days.size() < daysToShow; i++, day = day.plusDays(1)) {
            List<Integer> found = bookableStarts(equipmentId, group, day, sg.hours);
            if (!found.isEmpty()) {
                sg.days.add(day);
                sg.starts.add(found);
            }
        }
        return sg;
    }

    /** Total unbooked hours of an item on a day (within lab hours, not in the past). */
    int freeHours(String equipmentId, LocalDate date) {
        int total = 0;
        for (int[] b : freeBlocks(equipmentId, date)) {
            total += b[1] - b[0];
        }
        return total;
    }

    /** Other items of the same type that this group could book for exactly this time. */
    List<Equipment> sameTypeAlternatives(String equipmentId, String group, LocalDate date, int start, int end) {
        List<Equipment> list = new ArrayList<Equipment>();
        Equipment base = equipment.get(equipmentId);
        if (base == null) {
            return list;
        }
        for (Equipment e : equipment.values()) {
            if (!e.id.equals(equipmentId) && e.type.equalsIgnoreCase(base.type)
                    && check(e.id, group, date, start, end).ok()) {
                list.add(e);
            }
        }
        return list;
    }

    // ===================================================================
    // Checks used when asking for and validating input
    // ===================================================================

    /** Why this booking cannot be cancelled by this group, or null if it can. */
    String cancelProblem(Booking b, String typedId, String group) {
        if (b == null) return "There is no booking with ID '" + typedId + "'.";
        if (!b.group.equals(group)) return b.id + " belongs to " + b.group + ", not " + group + ".";
        if (!b.isActive()) return b.id + " was already cancelled on " + TimeUtil.stamp(b.cancelledAt) + ".";
        if (!b.startTime().isAfter(TimeUtil.now())) {
            return b.id + " has already started or finished, so it cannot be cancelled.";
        }
        return null;
    }

    /** Why this cannot be a new equipment ID, or null if it can. */
    String problemWithNewEquipmentId(String id) {
        if (id == null || !id.trim().toUpperCase(Locale.ROOT).matches("[A-Z0-9-]{2,12}")) {
            return "Use 2 to 12 letters, digits or dashes, with no spaces.";
        }
        if (findEquipment(id) != null) return id.trim().toUpperCase(Locale.ROOT) + " already exists.";
        return null;
    }

    /** Why this cannot be a new group name, or null if it can. */
    String problemWithNewGroup(String name) {
        if (name == null || !name.trim().matches("[A-Za-z0-9_-]{2,20}")) {
            return "Use 2 to 20 letters, digits, - or _, with no spaces.";
        }
        if (findGroup(name) != null) return findGroup(name) + " already exists.";
        return null;
    }

    /** Why this text cannot be used as a type or description, or null if it can. */
    static String problemWithText(String v, int maxLength) {
        if (v == null || v.trim().isEmpty()) return "Please fill this in.";
        if (v.contains("|")) return "Please do not use the | character.";
        if (v.trim().length() > maxLength) return "Please keep it to " + maxLength + " characters or fewer.";
        return null;
    }

    /** Matches a typed type to an existing one ignoring capitals, so "CAMERA" joins "Camera". */
    String normaliseType(String type) {
        for (Equipment e : equipment.values()) {
            if (e.type.equalsIgnoreCase(type.trim())) return e.type;
        }
        return type.trim();
    }

    /**
     * Changes any of the rules (pass null to leave one unchanged). Checks all values
     * first, so either every change is applied or none is. Returns a description of
     * each change, which is also written to the history.
     */
    List<String> changeRules(Integer open, Integer close, Integer maxInARow, Integer maxPerDay, Boolean rotating) {
        Policy p = policy;
        int newOpen = open == null ? p.openHour : open;
        int newClose = close == null ? p.closeHour : close;
        if (newOpen < 0 || newOpen > 23) throw new IllegalArgumentException("The opening hour must be from 0 to 23.");
        if (newClose < 1 || newClose > 24) throw new IllegalArgumentException("The closing hour must be from 1 to 24.");
        if (newClose <= newOpen) throw new IllegalArgumentException("Closing must be after opening.");
        if (maxInARow != null && (maxInARow < 0 || maxInARow > 12)) {
            throw new IllegalArgumentException("The back-to-back limit must be from 0 (off) to 12 hours.");
        }
        if (maxPerDay != null && (maxPerDay < 0 || maxPerDay > 24)) {
            throw new IllegalArgumentException("The daily limit must be from 0 (off) to 24 hours.");
        }
        List<String> changes = new ArrayList<String>();
        if (newOpen != p.openHour || newClose != p.closeHour) {
            changes.add("Lab hours " + TimeUtil.slot(p.openHour, p.closeHour) + " -> " + TimeUtil.slot(newOpen, newClose));
            p.openHour = newOpen;
            p.closeHour = newClose;
        }
        if (maxInARow != null && maxInARow != p.maxHoursInARow) {
            changes.add("Back-to-back limit " + limitText(p.maxHoursInARow) + " -> " + limitText(maxInARow));
            p.maxHoursInARow = maxInARow;
        }
        if (maxPerDay != null && maxPerDay != p.maxHoursPerDay) {
            changes.add("Daily limit " + limitText(p.maxHoursPerDay) + " -> " + limitText(maxPerDay));
            p.maxHoursPerDay = maxPerDay;
        }
        if (rotating != null && rotating != p.rotatingPriority) {
            changes.add("Rotating priority " + (rotating ? "OFF -> ON" : "ON -> OFF"));
            p.rotatingPriority = rotating;
        }
        for (String c : changes) {
            ruleChanged(c);
        }
        return changes;
    }

    private static String limitText(int hours) {
        return hours == 0 ? "off" : TimeUtil.hours(hours);
    }

    // ===================================================================
    // History searches
    // ===================================================================

    private static final java.util.regex.Pattern ID_PATTERN = java.util.regex.Pattern.compile("\\b[RWD]\\d{3,}\\b");

    /**
     * Every history line connected to an ID: the booking, the denial before it, the
     * waiting-list entry, the cancellation that freed it, and anyone passed over.
     */
    List<HistoryEntry> historyStory(String id) {
        java.util.Set<String> ids = new java.util.HashSet<String>();
        ids.add(id.trim().toUpperCase(Locale.ROOT));
        boolean grew = true;
        while (grew) {
            grew = false;
            for (HistoryEntry h : history) {
                if (!mentionsAny(h, ids)) continue;
                if (!h.ref.isEmpty() && ids.add(h.ref)) grew = true;
                java.util.regex.Matcher m = ID_PATTERN.matcher(h.detail);
                while (m.find()) {
                    if (ids.add(m.group())) grew = true;
                }
            }
        }
        List<HistoryEntry> story = new ArrayList<HistoryEntry>();
        for (HistoryEntry h : history) {
            if (mentionsAny(h, ids)) story.add(h);
        }
        return story;
    }

    private static boolean mentionsAny(HistoryEntry h, java.util.Set<String> ids) {
        if (ids.contains(h.ref)) return true;
        java.util.regex.Matcher m = ID_PATTERN.matcher(h.detail);
        while (m.find()) {
            if (ids.contains(m.group())) return true;
        }
        return false;
    }

    List<HistoryEntry> historyOfGroup(String group) {
        List<HistoryEntry> list = new ArrayList<HistoryEntry>();
        for (HistoryEntry h : history) {
            if (h.group.equals(group)) list.add(h);
        }
        return list;
    }

    List<HistoryEntry> historyOfEquipment(String equipmentId) {
        List<HistoryEntry> list = new ArrayList<HistoryEntry>();
        for (HistoryEntry h : history) {
            if (h.equipmentId.equals(equipmentId)) list.add(h);
        }
        return list;
    }

    // ===================================================================
    // Setup changes and the history
    // ===================================================================

    /** Adds an item. Throws IllegalArgumentException if the ID, type or description is not allowed. */
    void addEquipment(Equipment e) {
        String problem = problemWithNewEquipmentId(e.id);
        if (problem == null) problem = problemWithText(e.type, 20);
        if (problem == null) problem = problemWithText(e.name, 50);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        equipment.put(e.id, e);
        log(TimeUtil.now(), HistoryEntry.EQUIPMENT, e.id, e.id, "", e.type + ": " + e.name);
    }

    /** Adds a group at the end of the priority queue. Throws IllegalArgumentException if the name is not allowed. */
    void addGroup(String name) {
        String problem = problemWithNewGroup(name);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
        groups.add(name);
        priorityQueue.add(name);
        log(TimeUtil.now(), HistoryEntry.GROUP, "", "", name, "added at the end of the priority line");
    }

    void ruleChanged(String what) {
        log(TimeUtil.now(), HistoryEntry.RULE, "", "", "", what);
    }

    void log(LocalDateTime at, String action, String ref, String equipmentId, String group, String detail) {
        history.add(new HistoryEntry(at, action, ref, equipmentId, group, detail));
    }

    static String slotText(LocalDate date, int start, int end) {
        return TimeUtil.shortDay(date) + " " + TimeUtil.slot(start, end);
    }

    private static String nextId(String prefix, int n) {
        return prefix + (n < 10 ? "00" : n < 100 ? "0" : "") + n;
    }
}

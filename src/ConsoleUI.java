import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Everything the user sees and types: the numbered menu, the questions, and the
 * printed results. It turns each answer into a call on Scheduler and saves after
 * every action, so nothing is lost if the window is closed.
 */
final class ConsoleUI {

    private static final int WIDTH = 78;
    private static final String DATE_PROMPT = "Date (YYYY-MM-DD, or today / tomorrow): ";

    /** Thrown when the input ends (for example Ctrl+D, or a script runs out of lines). */
    private static final class EndOfInput extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    /** Thrown when the user presses Enter on an empty answer: go back to the main menu. */
    private static final class GoBack extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private final Scheduler s;
    private final Path dataDir;
    private final BufferedReader in;
    private final PrintStream out;
    /** Repeats typed input in the output. Only for recording sample sessions: -Dscheduler.echo=true */
    private final boolean echo;

    ConsoleUI(Scheduler s, Path dataDir, BufferedReader in, PrintStream out) {
        this.s = s;
        this.dataDir = dataDir;
        this.in = in;
        this.out = out;
        this.echo = Boolean.getBoolean("scheduler.echo");
    }

    // ===================================================================
    // Main loop
    // ===================================================================

    void run(boolean firstRun) {
        out.println();
        out.println("SHARED EQUIPMENT FAIR-USE SCHEDULER");
        try {
            if (firstRun) {
                firstRunSetup();
            }
            while (true) {
                tidyUp();
                printMenu();
                String choice = ask("Choose: ");
                boolean keepGoing = true;
                try {
                    tidyUp();
                    keepGoing = handle(choice);
                } catch (GoBack back) {
                    out.println("  (Back to the main menu.)");
                }
                if (!keepGoing) {
                    out.println("Everything is saved in the folder '" + dataDir + "'. Goodbye.");
                    return;
                }
            }
        } catch (EndOfInput end) {
            out.println();
            out.println("No more input. Everything is saved in the folder '" + dataDir + "'. Goodbye.");
        }
    }

    private boolean handle(String choice) {
        switch (choice) {
            case "1": makeReservation(); break;
            case "2": cancelReservation(); break;
            case "3": viewBookings(); break;
            case "4": suggestSlots(); break;
            case "5": utilizationReport(); break;
            case "6": deniedRequests(); break;
            case "7": bookingHistory(); break;
            case "8": waitingList(); break;
            case "9": fairnessRules(); break;
            case "10": equipmentAndGroups(); break;
            case "0": return false;
            case "": break;
            default: out.println("Please type one of the menu numbers, 0 to 10.");
        }
        return true;
    }

    private void printMenu() {
        out.println();
        out.println("=== Equipment Scheduler ===   " + TimeUtil.clock(TimeUtil.now()));
        out.println("1. Make a reservation");
        out.println("2. Cancel a reservation");
        out.println("3. View bookings");
        out.println("4. Suggest free slots");
        out.println("5. Utilization report");
        out.println("6. Denied requests");
        out.println("7. Booking history");
        out.println("8. Waiting list and priority order");
        out.println("9. Fairness rules");
        out.println("10. Equipment and groups");
        out.println("0. Save and exit");
    }

    private void firstRunSetup() {
        out.println();
        out.println("No saved data was found in the folder '" + dataDir + "'.");
        if (askYesNo("Load sample equipment, groups and bookings so you can try it straight away?")) {
            DemoData.load(s);
            say("[OK] Loaded " + s.equipment.size() + " equipment items, " + s.groups.size() + " project teams and "
                    + s.bookings.size() + " bookings covering the past two weeks and the days ahead.", "", "     ");
        } else {
            out.println("Starting with no data. Use menu 10 to add equipment and groups first.");
        }
        save();
    }

    // ===================================================================
    // 1. Make a reservation
    // ===================================================================

    private void makeReservation() {
        section("Make a reservation", true);
        if (!haveEquipmentAndGroups()) return;
        String eq = askEquipment("Equipment ID: ");
        String group = askGroup("Group: ");
        LocalDate date = askDate(DATE_PROMPT);
        int start = askHour("Start hour (0-23): ", 0, 23);
        int end = askEndHour(start);

        if (!refresh()) return;
        if (s.findEquipment(eq) == null || s.findGroup(group) == null) {
            out.println("The data was changed in another window while you were typing. Please try again.");
            return;
        }
        Scheduler.Outcome o = s.request(eq, group, date, start, end);
        save();
        out.println();
        if (o.booking != null) {
            out.println("[OK] Booked: " + o.booking.describe() + " (ID " + o.booking.id + ")");
            return;
        }
        out.println("[DENIED] " + eq + " for " + group + ", " + TimeUtil.shortDay(date) + " " + TimeUtil.slot(start, end));
        for (String m : o.check.messages) {
            say(m, "  - ", "    ");
        }
        out.println("  Recorded as " + o.denial.id + " in the denied-requests report.");
        suggestAfterDenial(eq, group, date, start, end, o.check);
        if (o.check.canWaitlist()) {
            offerWaitingList(o.denial);
        }
    }

    private void suggestAfterDenial(String eq, String group, LocalDate date, int start, int end, Scheduler.Check check) {
        out.println();
        out.println("Suggestions:");
        boolean timeUsable = !check.reasons.contains(DenyReason.OUTSIDE_LAB_HOURS)
                && !check.reasons.contains(DenyReason.IN_THE_PAST);
        if (timeUsable) {
            List<Equipment> alternatives = s.sameTypeAlternatives(eq, group, date, start, end);
            if (!alternatives.isEmpty()) {
                List<String> ids = new ArrayList<String>();
                for (Equipment e : alternatives) ids.add(e.id);
                say("Same time on another " + s.findEquipment(eq).type.toLowerCase(Locale.ROOT) + ": "
                        + String.join(", ", ids) + ". Use menu 1 again to book it.", "  ", "  ");
            }
        }
        printBookableTimes(eq, group, date, end - start, 2);
    }

    private void offerWaitingList(DeniedRequest d) {
        out.println();
        say("You can wait for this exact slot. If the group holding it cancels, the slot is handed over "
                + "automatically" + (s.policy.rotatingPriority
                ? ", with waiting groups taking turns (rotating priority)."
                : ", first come, first served."), "", "");
        if (askYesNo("Join the waiting list?")) {
            if (!refresh()) return;
            DeniedRequest fresh = s.findDenied(d.id);
            if (fresh == null) {
                out.println("The data was changed in another window meanwhile. Please make the request again.");
                return;
            }
            WaitlistEntry w = s.joinWaitlist(fresh);
            save();
            out.println("[OK] " + w.group + " is on the waiting list (" + w.id + ").");
            if (s.policy.rotatingPriority) {
                out.println("  " + w.group + " is number " + (s.placeInLine(w.group) + 1) + " of " + s.groups.size()
                        + " in the priority line right now.");
            }
        } else {
            out.println("  Not added to the waiting list.");
        }
    }

    // ===================================================================
    // 2. Cancel a reservation
    // ===================================================================

    private void cancelReservation() {
        section("Cancel a reservation", true);
        if (!haveEquipmentAndGroups()) return;
        String group = askGroup("Your group: ");
        List<Booking> mine = s.cancellableBookings(group);
        if (mine.isEmpty()) {
            out.println(group + " has no upcoming bookings to cancel.");
            return;
        }
        out.println(group + "'s upcoming bookings:");
        for (Booking b : mine) {
            out.println("  " + b.id + "  " + TimeUtil.padRight(b.equipmentId, 8) + TimeUtil.shortDay(b.date)
                    + "  " + TimeUtil.slot(b.start, b.end));
        }
        Booking target;
        while (true) {
            String typed = need("Booking ID to cancel: ");
            target = s.findBooking(typed);
            String problem = s.cancelProblem(target, typed, group);
            if (problem == null) break;
            out.println("  " + problem);
        }
        if (!askYesNo("Cancel " + target.id + " (" + target.describe() + ")?")) {
            out.println("  Nothing was changed.");
            return;
        }
        if (!refresh()) return;
        String id = target.id;
        target = s.findBooking(id);
        String problem = s.cancelProblem(target, id, group);
        if (problem != null) {
            out.println("  " + problem);
            return;
        }
        Scheduler.Handover handover = s.cancel(target);
        save();
        out.println("[OK] Cancelled " + target.id + ". It stays in the booking history.");
        for (String note : handover.skipped) {
            say(note, "  ", "  ");
        }
        if (handover.granted.isEmpty()) {
            out.println("  No one on the waiting list could take this time, so it is now free for anyone.");
            return;
        }
        for (Booking nb : handover.granted) {
            say("Handed to " + nb.group + " from the waiting list: " + nb.equipmentId + " "
                    + Scheduler.slotText(nb.date, nb.start, nb.end) + " (new booking " + nb.id + ").", "  ", "  ");
        }
        if (s.policy.rotatingPriority) {
            say("Priority line is now: " + lineText(), "  ", "  ");
        }
    }

    // ===================================================================
    // 3. View bookings
    // ===================================================================

    private void viewBookings() {
        section("View bookings", true);
        out.println("1. Timetable for one day (all equipment)");
        out.println("2. List of current and upcoming bookings");
        int c = askNumber("Choose (1-2): ", 1, 2, null);
        if (c == 1) {
            printTimetable(askDate(DATE_PROMPT));
        } else {
            printUpcoming(askGroupOptional("Group (press Enter for everyone): "));
        }
    }

    private void printTimetable(LocalDate date) {
        Policy p = s.policy;
        int labelWidth = 9;
        for (String id : s.equipment.keySet()) labelWidth = Math.max(labelWidth, id.length() + 1);

        out.println();
        out.println("Timetable for " + TimeUtil.day(date));
        out.println("Each column is one hour. A letter is the group holding it; a dot is free.");
        StringBuilder head = new StringBuilder(TimeUtil.padRight("", labelWidth));
        for (int h = p.openHour; h < p.closeHour; h++) {
            head.append(h < 10 ? "0" : "").append(h).append(' ');
        }
        out.println(head.toString().replaceAll("\\s+$", ""));

        Set<String> shownGroups = new LinkedHashSet<String>();
        List<Booking> thatDay = new ArrayList<Booking>();
        for (Equipment e : s.equipment.values()) {
            List<Booking> list = s.activeBookings(e.id, date);
            thatDay.addAll(list);
            StringBuilder row = new StringBuilder(TimeUtil.padRight(e.id, labelWidth));
            for (int h = p.openHour; h < p.closeHour; h++) {
                String cell = " . ";
                for (Booking b : list) {
                    if (h >= b.start && h < b.end) {
                        cell = " " + letter(b.group) + " ";
                        shownGroups.add(b.group);
                    }
                }
                row.append(cell);
            }
            out.println(row.toString().replaceAll("\\s+$", ""));
        }
        if (!shownGroups.isEmpty()) {
            List<String> key = new ArrayList<String>();
            for (String g : s.groups) {
                if (shownGroups.contains(g)) key.add(letter(g) + " = " + g);
            }
            out.println("Key: " + String.join(", ", key));
        }

        out.println();
        if (thatDay.isEmpty()) {
            out.println("No bookings on this day.");
            return;
        }
        Scheduler.sortByTime(thatDay);
        out.println("Bookings that day:");
        LocalDateTime now = TimeUtil.now();
        for (Booking b : thatDay) {
            out.println("  " + b.id + "  " + TimeUtil.slot(b.start, b.end) + "  " + TimeUtil.padRight(b.equipmentId, 8)
                    + TimeUtil.padRight(b.group, groupWidth()) + b.statusLabel(now)
                    + (Booking.FROM_WAITLIST.equals(b.source) ? " (from waiting list)" : ""));
        }
    }

    private void printUpcoming(String group) {
        List<Booking> list = s.currentAndUpcoming(group);
        out.println();
        if (list.isEmpty()) {
            out.println((group == null ? "There are" : group + " has") + " no current or upcoming bookings.");
            return;
        }
        out.println((group == null ? "All" : group + "'s") + " current and upcoming bookings:");
        LocalDateTime now = TimeUtil.now();
        LocalDate lastDate = null;
        for (Booking b : list) {
            if (!b.date.equals(lastDate)) {
                out.println("  " + TimeUtil.day(b.date));
                lastDate = b.date;
            }
            out.println("    " + b.id + "  " + TimeUtil.slot(b.start, b.end) + "  " + TimeUtil.padRight(b.equipmentId, 8)
                    + TimeUtil.padRight(b.group, groupWidth()) + b.statusLabel(now)
                    + (Booking.FROM_WAITLIST.equals(b.source) ? " (from waiting list)" : ""));
        }
    }

    // ===================================================================
    // 4. Suggest free slots
    // ===================================================================

    private void suggestSlots() {
        section("Suggest free slots", true);
        if (!haveEquipmentAndGroups()) return;
        String eq = askEquipment("Equipment ID: ");
        LocalDate date = askDate(DATE_PROMPT);
        int hours = askNumber("How many hours do you need? [2]: ", 1, 24, 2);
        String group = askGroupOptional("Group, so its fairness limits are applied (press Enter to skip): ");
        out.println();

        if (date.isBefore(TimeUtil.today())) {
            out.println(TimeUtil.day(date) + " is in the past, so here are the next options from today.");
        } else {
            List<int[]> free = s.freeBlocks(eq, date);
            if (free.isEmpty()) {
                out.println(eq + " has no free time left on " + TimeUtil.day(date) + ".");
            } else {
                out.println("Free on " + eq + ", " + TimeUtil.day(date) + ": " + blocksText(free));
            }
        }
        printBookableTimes(eq, group == null ? Scheduler.ANY_GROUP : group, date, hours, 3);

        Equipment base = s.findEquipment(eq);
        List<String> others = new ArrayList<String>();
        if (!date.isBefore(TimeUtil.today())) {
            for (Equipment e : s.equipment.values()) {
                if (!e.id.equals(eq) && e.type.equalsIgnoreCase(base.type)) {
                    int freeHours = s.freeHours(e.id, date);
                    if (freeHours > 0) others.add(e.id + " (" + TimeUtil.hours(freeHours) + " free)");
                }
            }
        }
        if (!others.isEmpty()) {
            say("Other " + base.type.toLowerCase(Locale.ROOT) + " items with free time that day: "
                    + String.join(", ", others) + ".", "", "  ");
        }
    }

    /** Prints start times that pass every rule for this group, looking up to 7 days ahead. */
    private void printBookableTimes(String eq, String group, LocalDate from, int hours, int daysToShow) {
        Scheduler.Suggestion sg = s.suggestTimes(eq, group, from, hours, daysToShow);
        if (sg.shortened) {
            say("The longest booking the rules allow in one go is " + TimeUtil.hours(sg.longest)
                    + ", so these suggestions use that length.", "  ", "  ");
        }
        String who = group.isEmpty() ? "" : group + " ";
        if (sg.days.isEmpty()) {
            say("No start time in the 7 days from " + TimeUtil.shortDay(sg.searchedFrom) + " gives " + who
                    + TimeUtil.hours(sg.hours) + " on " + eq + ". Check the back-to-back and daily limits in menu 9.",
                    "  ", "  ");
            return;
        }
        out.println("  Start times that give " + who + TimeUtil.hours(sg.hours) + " on " + eq + ":");
        for (int i = 0; i < sg.days.size(); i++) {
            List<String> times = new ArrayList<String>();
            for (int st : sg.starts.get(i)) times.add(TimeUtil.hour(st));
            say(String.join(", ", times), "    " + TimeUtil.padRight(TimeUtil.shortDay(sg.days.get(i)) + ":", 12),
                    "                ");
        }
    }

    // ===================================================================
    // 5 and 6. Reports
    // ===================================================================

    private void utilizationReport() {
        section("Utilization report", true);
        out.println("1. Last 7 days (before today)");
        out.println("2. Next 7 days (from today)");
        out.println("3. Choose the dates");
        int c = askNumber("Choose (1-3): ", 1, 3, null);
        LocalDate today = TimeUtil.today();
        LocalDate from;
        LocalDate to;
        if (c == 1) {
            from = today.minusDays(7);
            to = today.minusDays(1);
        } else if (c == 2) {
            from = today;
            to = today.plusDays(6);
        } else {
            from = askDate("From date: ");
            while (true) {
                to = askDate("To date: ");
                if (!to.isBefore(from)) break;
                out.println("  The end date must be on or after " + TimeUtil.day(from) + ".");
            }
        }
        out.println();
        Reports.utilization(s, out, from, to);
    }

    private void deniedRequests() {
        section("Denied requests", false);
        Reports.denied(s, out, 15);
    }

    // ===================================================================
    // 7. Booking history
    // ===================================================================

    private void bookingHistory() {
        section("Booking history", true);
        out.println("Every booking, cancellation, denial and waiting-list change is kept here.");
        out.println("1. Everything");
        out.println("2. One group");
        out.println("3. One equipment item");
        out.println("4. The full story of one booking, waiting-list or denied request (e.g. R004)");
        int c = askNumber("Choose (1-4): ", 1, 4, null);

        List<HistoryEntry> matches = new ArrayList<HistoryEntry>();
        if (c == 1) {
            matches.addAll(s.history);
        } else if (c == 2) {
            matches = s.historyOfGroup(askGroup("Group: "));
        } else if (c == 3) {
            matches = s.historyOfEquipment(askEquipment("Equipment ID: "));
        } else {
            matches = s.historyStory(need("ID: "));
        }
        out.println();
        if (matches.isEmpty()) {
            out.println("No history matches that.");
            return;
        }
        int limit = matches.size();
        if (c != 4) {
            limit = askNumber("How many of the most recent entries? [30]: ", 1, 1000000, 30);
            out.println();
        }
        int from = Math.max(0, matches.size() - limit);
        out.println(from > 0
                ? "The latest " + (matches.size() - from) + " of " + matches.size() + " entries, oldest first:"
                : "All " + matches.size() + " entries, oldest first:");
        for (int i = from; i < matches.size(); i++) {
            HistoryEntry h = matches.get(i);
            out.println(TimeUtil.stamp(h.time) + "  " + TimeUtil.padRight(h.label(), 23) + TimeUtil.padRight(h.ref, 6)
                    + TimeUtil.padRight(h.equipmentId, 8) + TimeUtil.padRight(h.group, groupWidth()) + h.detail);
        }
    }

    // ===================================================================
    // 8. Waiting list and priority order
    // ===================================================================

    private void waitingList() {
        section("Waiting list and priority order", false);
        if (s.policy.rotatingPriority) {
            say("Rotating priority is ON. When a booked slot is cancelled, it goes to the waiting group that "
                    + "is highest in this line, and that group then moves to the back. So no group can win every time.", "", "");
            say("Line now: " + lineText(), "  ", "  ");
        } else {
            say("Rotating priority is OFF. A cancelled slot goes to whichever group joined the waiting list first.", "", "");
        }
        out.println();
        List<WaitlistEntry> entries = s.sortedWaitlist();
        if (entries.isEmpty()) {
            out.println("Nobody is on the waiting list.");
            return;
        }
        out.println("Waiting now (the first group listed for a slot gets it if it frees up):");
        for (WaitlistEntry w : entries) {
            out.println("  " + w.id + "  " + TimeUtil.padRight(w.group, groupWidth()) + TimeUtil.padRight(w.equipmentId, 8)
                    + Scheduler.slotText(w.date, w.start, w.end) + "   since " + TimeUtil.stamp(w.requestedAt)
                    + (s.policy.rotatingPriority ? "   (no. " + (s.placeInLine(w.group) + 1) + " in line)" : ""));
        }
        out.println();
        out.println("1. Take a request off the waiting list");
        out.println("0. Back to the menu");
        int c = askNumber("Choose [0]: ", 0, 1, 0);
        if (c == 0) return;
        WaitlistEntry w;
        while (true) {
            String typed = need("Waiting-list ID (e.g. " + entries.get(0).id + "): ");
            w = s.findWaitlistEntry(typed);
            if (w != null) break;
            out.println("  There is no waiting-list entry '" + typed + "'.");
        }
        if (askYesNo("Remove " + w.id + " (" + w.group + ", " + w.equipmentId + " "
                + Scheduler.slotText(w.date, w.start, w.end) + ")?")) {
            if (!refresh()) return;
            WaitlistEntry fresh = s.findWaitlistEntry(w.id);
            if (fresh == null) {
                out.println("  " + w.id + " is no longer on the waiting list.");
                return;
            }
            s.withdraw(fresh);
            save();
            out.println("[OK] Removed " + w.id + " from the waiting list.");
        } else {
            out.println("  Nothing was changed.");
        }
    }

    // ===================================================================
    // 9. Fairness rules
    // ===================================================================

    private void fairnessRules() {
        section("Fairness rules", true);
        Policy p = s.policy;
        say("1. Lab hours: " + TimeUtil.slot(p.openHour, p.closeHour) + ". Every booking must fit inside these hours.", "", "   ");
        say("2. Back-to-back limit: " + (p.maxHoursInARow == 0 ? "off." : TimeUtil.hours(p.maxHoursInARow)
                + ". A group cannot hold the same item for longer than this in one unbroken stretch. "
                + "Bookings that touch each other count as one stretch."), "", "   ");
        say("3. Daily limit: " + (p.maxHoursPerDay == 0 ? "off." : TimeUtil.hours(p.maxHoursPerDay)
                + " per item, per group, per day. This stops a group getting round rule 2 by leaving small gaps."), "", "   ");
        say("4. Rotating priority: " + (p.rotatingPriority
                ? "ON. A cancelled slot goes to waiting groups in turn, so the same group cannot always win."
                : "OFF. A cancelled slot goes to whichever group joined the waiting list first."), "", "   ");
        out.println();
        int c = askNumber("Change which rule? (1-4): ", 1, 4, null);

        Integer open = null;
        Integer close = null;
        Integer inARow = null;
        Integer perDay = null;
        Boolean rotating = null;
        if (c == 1) {
            open = askHour("New opening hour (0-23): ", 0, 23);
            while (true) {
                close = askHour("New closing hour (1-24): ", 1, 24);
                if (close > open) break;
                out.println("  Closing must be after opening (" + TimeUtil.hour(open) + ").");
            }
        } else if (c == 2) {
            inARow = askNumber("New back-to-back limit in hours (1-12, or 0 to switch it off): ", 0, 12, null);
        } else if (c == 3) {
            perDay = askNumber("New daily limit in hours (1-24, or 0 to switch it off): ", 0, 24, null);
        } else {
            boolean turnOn = !p.rotatingPriority;
            if (!askYesNo("Turn rotating priority " + (turnOn ? "ON" : "OFF") + "?")) {
                out.println("  Nothing was changed.");
                return;
            }
            rotating = turnOn;
        }
        if (!refresh()) return;
        List<String> changes = s.changeRules(open, close, inARow, perDay, rotating);
        if (changes.isEmpty()) {
            out.println("  That is already the rule, so nothing was changed.");
            return;
        }
        save();
        out.println("[OK] Rule updated. It applies to new requests; existing bookings are kept as they are.");
    }

    // ===================================================================
    // 10. Equipment and groups
    // ===================================================================

    private void equipmentAndGroups() {
        section("Equipment and groups", true);
        out.println("1. List equipment and groups");
        out.println("2. Add equipment");
        out.println("3. Add a group");
        out.println("4. Replace everything with the sample data");
        int c = askNumber("Choose (1-4): ", 1, 4, null);
        if (c == 1) listEquipmentAndGroups();
        else if (c == 2) addEquipment();
        else if (c == 3) addGroup();
        else resetToSample();
    }

    private void listEquipmentAndGroups() {
        out.println();
        out.println("Equipment (" + s.equipment.size() + "):");
        for (Equipment e : s.equipment.values()) {
            int upcoming = 0;
            for (Booking b : s.currentAndUpcoming(null)) if (b.equipmentId.equals(e.id)) upcoming++;
            out.println("  " + TimeUtil.padRight(e.id, 9) + TimeUtil.padRight(e.type, 14)
                    + TimeUtil.padRight(upcoming + " upcoming", 13) + e.name);
        }
        out.println();
        out.println("Groups (" + s.groups.size() + "): " + String.join(", ", s.groups));
        if (s.policy.rotatingPriority && !s.groups.isEmpty()) {
            say("Priority line for the next freed slot: " + lineText(), "", "  ");
        }
    }

    private void addEquipment() {
        String id;
        while (true) {
            id = need("New equipment ID (letters, digits and dashes, e.g. CAM-03): ").toUpperCase(Locale.ROOT);
            String problem = s.problemWithNewEquipmentId(id);
            if (problem == null) break;
            out.println("  " + problem);
        }
        Set<String> types = new LinkedHashSet<String>();
        for (Equipment e : s.equipment.values()) types.add(e.type);
        if (!types.isEmpty()) out.println("  Types already in use: " + String.join(", ", types));
        String type = askText("Type (items of the same type are offered as alternatives): ", 20);
        String name = askText("Description (e.g. Sony ZV-E10 camera): ", 50);
        if (!refresh()) return;
        String problem = s.problemWithNewEquipmentId(id);
        if (problem != null) {
            out.println("  " + problem);
            return;
        }
        type = s.normaliseType(type);
        s.addEquipment(new Equipment(id, type, name));
        save();
        out.println("[OK] Added " + id + " (" + type + ": " + name + ").");
    }

    private void addGroup() {
        String name;
        while (true) {
            name = need("New group name (letters, digits, - or _, e.g. GroupE): ");
            String problem = s.problemWithNewGroup(name);
            if (problem == null) break;
            out.println("  " + problem);
        }
        if (!refresh()) return;
        String problem = s.problemWithNewGroup(name);
        if (problem != null) {
            out.println("  " + problem);
            return;
        }
        s.addGroup(name);
        save();
        out.println("[OK] Added " + name + ". It joins the end of the priority line.");
    }

    private void resetToSample() {
        say("This replaces ALL equipment, groups, bookings and history with the sample data. "
                + "A backup copy of the current data is kept first.", "", "");
        String typed = ask("Type RESET to confirm: ");
        if (!typed.equals("RESET")) {
            out.println("  Nothing was changed.");
            return;
        }
        try {
            Path backup = Storage.backup(dataDir);
            out.println("  Backup saved in '" + backup + "'.");
        } catch (IOException e) {
            out.println("  [!] Could not make a backup (" + e.getMessage() + "), so nothing was changed.");
            return;
        }
        DemoData.load(s);
        save();
        out.println("[OK] Sample data loaded.");
    }

    // ===================================================================
    // Asking questions
    // ===================================================================

    private String readLine() {
        try {
            String line = in.readLine();
            if (line == null) throw new EndOfInput();
            return line.trim();
        } catch (IOException e) {
            throw new EndOfInput();
        }
    }

    private String ask(String prompt) {
        out.print(prompt);
        out.flush();
        String line = readLine();
        if (echo) out.println(line);
        return line;
    }

    /** Like ask, but an empty answer goes back to the main menu. */
    private String need(String prompt) {
        String v = ask(prompt);
        if (v.isEmpty()) throw new GoBack();
        return v;
    }

    private String askEquipment(String prompt) {
        Map<String, List<String>> byType = new LinkedHashMap<String, List<String>>();
        for (Equipment e : s.equipment.values()) {
            byType.computeIfAbsent(e.type, k -> new ArrayList<String>()).add(e.id);
        }
        List<String> parts = new ArrayList<String>();
        for (Map.Entry<String, List<String>> en : byType.entrySet()) {
            parts.add(en.getKey() + ": " + String.join(", ", en.getValue()));
        }
        say("Equipment - " + String.join("; ", parts), "  ", "    ");
        while (true) {
            String v = need(prompt);
            Equipment e = s.findEquipment(v);
            if (e != null) return e.id;
            out.println("  There is no equipment with ID '" + v + "'. Please type one of the IDs above.");
        }
    }

    private String askGroup(String prompt) {
        out.println("  Groups - " + String.join(", ", s.groups));
        while (true) {
            String v = need(prompt);
            String g = s.findGroup(v);
            if (g != null) return g;
            out.println("  There is no group called '" + v + "'. Please type one of the groups above.");
        }
    }

    /** Returns null if the user presses Enter (meaning "everyone" or "skip"). */
    private String askGroupOptional(String prompt) {
        out.println("  Groups - " + String.join(", ", s.groups));
        while (true) {
            String v = ask(prompt);
            if (v.isEmpty()) return null;
            String g = s.findGroup(v);
            if (g != null) return g;
            out.println("  There is no group called '" + v + "'. Type one of the groups above, or press Enter.");
        }
    }

    private LocalDate askDate(String prompt) {
        while (true) {
            String v = need(prompt);
            LocalDate d = TimeUtil.parseDate(v);
            if (d != null) return d;
            out.println("  Please type a real date like 2026-10-28 or 28-10-2026, or the word today or tomorrow.");
        }
    }

    private int askHour(String prompt, int min, int max) {
        while (true) {
            String v = need(prompt);
            Integer h = TimeUtil.parseHour(v);
            if (h == null && v.matches("\\d{1,2}:\\d{2}")) {
                out.println("  Bookings are in whole hours. Please type an hour like 10 or 14:00.");
            } else if (h == null || h < min || h > max) {
                out.println("  Please type a whole hour from " + min + " to " + max + ", like 10 or 14:00.");
            } else {
                return h;
            }
        }
    }

    private int askEndHour(int start) {
        while (true) {
            int end = askHour("End hour (1-24): ", 1, 24);
            if (end > start) return end;
            out.println("  The end hour must be after the start hour (" + TimeUtil.hour(start) + ").");
        }
    }

    /** Whole number between min and max. An empty answer gives the default, or goes back if there is none. */
    private int askNumber(String prompt, int min, int max, Integer defaultValue) {
        while (true) {
            String v = ask(prompt);
            if (v.isEmpty()) {
                if (defaultValue != null) return defaultValue;
                throw new GoBack();
            }
            try {
                int n = Integer.parseInt(v);
                if (n >= min && n <= max) return n;
            } catch (NumberFormatException e) {
                // fall through to the message
            }
            out.println("  Please type a number from " + min + " to " + max + ".");
        }
    }

    private String askText(String prompt, int maxLength) {
        while (true) {
            String v = need(prompt);
            String problem = Scheduler.problemWithText(v, maxLength);
            if (problem == null) return v;
            out.println("  " + problem);
        }
    }

    /** An empty answer counts as no. */
    private boolean askYesNo(String question) {
        while (true) {
            String v = ask(question + " (y/n): ").toLowerCase(Locale.ROOT);
            if (v.equals("y") || v.equals("yes")) return true;
            if (v.equals("n") || v.equals("no") || v.isEmpty()) return false;
            out.println("  Please answer y or n.");
        }
    }

    // ===================================================================
    // Small helpers
    // ===================================================================

    private void section(String title, boolean asksQuestions) {
        out.println();
        out.println("--- " + title + " ---");
        if (asksQuestions) {
            out.println("(Press Enter on an empty answer to go back to the menu.)");
        }
    }

    private boolean haveEquipmentAndGroups() {
        if (s.equipment.isEmpty() || s.groups.isEmpty()) {
            out.println("Add at least one equipment item and one group first (menu 10).");
            return false;
        }
        return true;
    }

    private void say(String text, String firstIndent, String restIndent) {
        for (String line : TimeUtil.wrap(text, WIDTH, firstIndent, restIndent)) {
            out.println(line);
        }
    }

    private String lineText() {
        List<String> line = s.priorityLine();
        List<String> parts = new ArrayList<String>();
        for (int i = 0; i < line.size(); i++) parts.add((i + 1) + ". " + line.get(i));
        return String.join("   ", parts);
    }

    /** Column width that fits the longest group name, so tables stay lined up. */
    private int groupWidth() {
        int w = 8;
        for (String g : s.groups) w = Math.max(w, g.length());
        return w + 1;
    }

    private String letter(String group) {
        int idx = s.groups.indexOf(group);
        return idx >= 0 && idx < 26 ? String.valueOf((char) ('A' + idx)) : "*";
    }

    private static String blocksText(List<int[]> blocks) {
        List<String> parts = new ArrayList<String>();
        for (int[] b : blocks) parts.add(TimeUtil.slot(b[0], b[1]));
        return String.join(", ", parts);
    }

    /** Saves straight after every change, so nothing is lost if the window closes. */
    private void save() {
        try {
            Storage.save(s, dataDir);
        } catch (IOException e) {
            out.println("[!] Could not save to '" + dataDir + "': " + e.getMessage());
        }
    }

    /**
     * Picks up anything another window saved since this program last loaded or saved.
     * Returns false if the saved data could not be read; the caller must then stop without
     * changing anything, so nothing old or incomplete is ever saved over it.
     */
    private boolean refresh() {
        List<String> warnings = new ArrayList<String>();
        try {
            if (Storage.reloadIfChanged(s, dataDir, warnings)) {
                out.println("  (Picked up changes saved from another window.)");
            }
            for (String w : warnings) {
                out.println("[!] " + w);
            }
            return true;
        } catch (IOException e) {
            out.println("[!] Could not read the latest saved data (" + e.getMessage() + ").");
            out.println("    Nothing was changed or saved, so your saved bookings are safe. Please try again in a moment.");
            return false;
        }
    }

    /** Before each menu: pick up changes from another window and clear waiting-list requests whose time has passed. */
    private void tidyUp() {
        if (!refresh()) return;
        if (s.expireWaitlist() > 0) {
            save();
        }
    }
}

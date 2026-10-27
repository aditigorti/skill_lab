import java.io.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.*;

/** Shared Equipment Fair-Use Scheduler: the numbered menu. It asks questions and prints answers; the Scheduler decides. */
public final class Main {

    private final Scheduler s;
    private final Path dataDir;
    private final BufferedReader in = new BufferedReader(new InputStreamReader(System.in));

    /** Thrown when the user presses Enter on an empty answer: go back to the menu. */
    private static class GoBack extends RuntimeException {
        private static final long serialVersionUID = 1L;
    }

    private Main(Scheduler s, Path dataDir) {
        this.s = s;
        this.dataDir = dataDir;
    }

    public static void main(String[] args) {
        Path dir = Paths.get(args.length > 0 ? args[0] : "data");
        Scheduler s;
        if (Storage.hasSavedData(dir)) {
            try {
                s = Storage.load(dir);
            } catch (IOException | RuntimeException e) {
                System.out.println("Could not read the saved data in '" + dir + "': " + e.getMessage());
                System.out.println("Nothing was changed or saved. Fix that file, or move the folder away to start again.");
                return;
            }
        } else {
            s = SampleData.create();
            System.out.println("No saved data yet, so sample data was loaded: 6 items, 6 teams, a few days of bookings.");
        }
        Main menu = new Main(s, dir);
        menu.save();
        menu.run();
    }

    private void run() {
        while (true) {
            if (s.expireOldWaits() > 0) save();
            System.out.println();
            System.out.println("=== Shared Equipment Scheduler ===   " + Scheduler.now().toString().replace('T', ' '));
            System.out.println("1. Make a reservation      6. Refused requests report");
            System.out.println("2. Cancel a reservation    7. Booking history");
            System.out.println("3. View bookings for a day 8. Waiting list and priority line");
            System.out.println("4. Suggest free slots      0. Exit (everything is already saved)");
            System.out.println("5. Usage report");
            String choice = ask("Choose: ");
            try {
                switch (choice) {
                    case "1": makeReservation(); break;
                    case "2": cancelReservation(); break;
                    case "3": viewDay(); break;
                    case "4": suggestSlots(); break;
                    case "5": usageReport(); break;
                    case "6": refusedReport(); break;
                    case "7": history(); break;
                    case "8": waitingList(); break;
                    case "0": System.out.println("Goodbye. Your data is in the folder '" + dataDir + "'."); return;
                    default: System.out.println("Please type a number from 0 to 8.");
                }
            } catch (GoBack | IllegalArgumentException | IllegalStateException e) { // e.g. the slot started meanwhile
                System.out.println(e instanceof GoBack ? "(Back to the menu.)" : "[NOT DONE] " + e.getMessage());
            }
        }
    }

    // ----- The menu options -----

    private void makeReservation() {
        System.out.println("(Press Enter on any empty answer to go back.)");
        String item = askItem(), team = askTeam();
        LocalDate date = askDate("Date (YYYY-MM-DD, today or tomorrow): ");
        int start = askNumber("Start hour (8-19): ", 0, 23);
        int end = askNumber("End hour (" + (start + 1) + "-20): ", start + 1, 24);
        Scheduler.Outcome o = s.request(item, team, date, start, end);
        save();
        if (o.booking != null) {
            System.out.println("[OK] Booked: " + o.booking);
            return;
        }
        System.out.println("[REFUSED] Recorded as " + o.refused.id + " in the refused requests report:");
        for (String m : o.check.messages) System.out.println("  - " + m);
        List<String> others = s.sameTypeAlternatives(item, team, date, start, end);
        if (!others.isEmpty()) System.out.println("  Same time on another " + s.equipment.get(item) + ": " + others);
        printFreeStarts(item, team, date, end - start, 2);
        if (o.check.canWait() && askYesNo("Wait for this exact slot? If it is cancelled, it is handed over"
                + " automatically, highest in the priority line first. (y/n): ")) {
            s.joinWaitingList(o.refused);
            save();
            System.out.println("[OK] " + team + " is waiting (" + o.refused.id + "), number "
                    + (s.placeInLine(team) + 1) + " in the priority line.");
        }
    }

    private void cancelReservation() {
        String team = askTeam();
        List<Booking> list = s.upcoming(team);
        if (list.isEmpty()) {
            System.out.println(team + " has no bookings that can still be cancelled.");
            return;
        }
        for (Booking b : list) System.out.println("  " + b);
        Booking b = s.findBooking(need("Booking ID to cancel: "));
        if (b == null || !list.contains(b)) {
            System.out.println("That is not one of the IDs above.");
            return;
        }
        if (!askYesNo("Cancel " + b.id + "? (y/n): ")) return;
        List<String> notes = s.cancel(b);
        save();
        System.out.println("[OK] Cancelled " + b.id + ". It stays in the history.");
        for (String note : notes) System.out.println("  " + note);
        if (notes.isEmpty()) System.out.println("  Nobody was waiting for it, so the time is now free.");
        printPriorityLine();
    }

    private void viewDay() {
        LocalDate date = askDate("Date (YYYY-MM-DD, today or tomorrow): ");
        boolean any = false;
        for (String item : s.equipment.keySet()) {
            for (Booking b : s.activeBookings(item, date)) {
                System.out.println("  " + b);
                any = true;
            }
        }
        if (!any) System.out.println("  No bookings on " + date + ".");
    }

    private void suggestSlots() {
        String item = askItem(), team = askTeam();
        LocalDate date = askDate("From date (YYYY-MM-DD, today or tomorrow): ");
        int hours = askNumber("How many hours (1-" + Scheduler.MAX_IN_A_ROW + "): ", 1, Scheduler.MAX_IN_A_ROW);
        printFreeStarts(item, team, date, hours, 3);
    }

    private void usageReport() {
        LocalDate from = askDate("From date (YYYY-MM-DD, today or tomorrow): ");
        LocalDate to = askDate("To date: ");
        if (to.isBefore(from)) {
            System.out.println("The end date is before the start date.");
            return;
        }
        int days = (int) ChronoUnit.DAYS.between(from, to) + 1;
        int open = days * (Scheduler.CLOSE - Scheduler.OPEN); // hours each item could have been used
        System.out.println("Usage " + from + " to " + to + " (" + days + " days, " + open + " open hours per item)");
        for (Map.Entry<String, Integer> e : s.usage(from, to, false).entrySet()) {
            System.out.printf("  %-7s %3d hours  %3d%%%n", e.getKey(), e.getValue(), percent(e.getValue(), open));
        }
        Map<String, Integer> perTeam = s.usage(from, to, true);
        int total = 0;
        for (int h : perTeam.values()) total += h;
        System.out.println("Share per team (" + total + " hours booked in all)");
        for (Map.Entry<String, Integer> e : perTeam.entrySet()) {
            System.out.printf("  %-11s %3d hours  %3d%%%n", e.getKey(), e.getValue(), percent(e.getValue(), total));
        }
    }

    private void refusedReport() {
        Map<String, Integer> byReason = new LinkedHashMap<>();
        for (Request r : s.requests) byReason.put(r.reason, byReason.getOrDefault(r.reason, 0) + 1);
        System.out.println(s.requests.size() + " refused requests. By reason: " + byReason);
        System.out.println("  ID    Item    Team        Date       Time         Reason              What happened");
        for (Request r : s.requests) System.out.println("  " + r);
    }

    private void history() {
        String filter = ask("Show lines containing (a booking ID, team or item; Enter for everything): ").toLowerCase();
        for (String line : s.history) {
            if (line.toLowerCase().contains(filter)) System.out.println("  " + line);
        }
    }

    private void waitingList() {
        printPriorityLine();
        boolean any = false;
        for (Request r : s.requests) {
            if (r.isWaiting()) {
                System.out.println("  " + r + "  (no. " + (s.placeInLine(r.team) + 1) + " in line)");
                any = true;
            }
        }
        if (!any) System.out.println("  Nobody is waiting right now.");
    }

    // ----- Printing helpers -----

    private void printFreeStarts(String item, String team, LocalDate from, int hours, int daysToShow) {
        System.out.println("  Start times that give " + team + " " + hours + " hour(s) on " + item + ":");
        for (int d = 0; d < daysToShow; d++) {
            List<Integer> starts = s.freeStarts(item, team, from.plusDays(d), hours);
            System.out.println("    " + from.plusDays(d) + ": " + (starts.isEmpty() ? "none" : starts));
        }
    }

    private void printPriorityLine() {
        System.out.println("  Priority line (the first team gets the next freed slot): " + s.teams);
    }

    static int percent(int part, int whole) {
        return whole == 0 ? 0 : (int) Math.round(100.0 * part / whole);
    }

    private void save() {
        try {
            Storage.save(s, dataDir);
        } catch (IOException e) {
            System.out.println("[!] Could not save: " + e.getMessage());
        }
    }

    // ----- Asking questions: every answer is checked, and asked again if it is wrong -----

    private String ask(String prompt) {
        System.out.print(prompt);
        try {
            String line = in.readLine();
            if (line == null) System.exit(0); // input ended; everything is already saved
            if (Boolean.getBoolean("scheduler.echo")) System.out.println(line);
            return line.trim();
        } catch (IOException e) {
            throw new GoBack();
        }
    }

    /** Like ask, but an empty answer goes back to the menu. */
    private String need(String prompt) {
        String answer = ask(prompt);
        if (answer.isEmpty()) throw new GoBack();
        return answer;
    }

    private String askItem() {
        System.out.println("  Equipment: " + s.equipment);
        while (true) {
            String id = need("Equipment ID: ").toUpperCase();
            if (s.equipment.containsKey(id)) return id;
            System.out.println("  There is no " + id + ". Please type one of the IDs shown.");
        }
    }

    private String askTeam() {
        System.out.println("  Teams: " + s.teams);
        while (true) {
            String name = need("Team: ");
            for (String t : s.teams) {
                if (t.equalsIgnoreCase(name)) return t;
            }
            System.out.println("  There is no team " + name + ". Please type one of the names shown.");
        }
    }

    private LocalDate askDate(String prompt) {
        while (true) {
            String v = need(prompt).toLowerCase();
            LocalDate today = Scheduler.now().toLocalDate();
            if (v.equals("today")) return today;
            if (v.equals("tomorrow")) return today.plusDays(1);
            try {
                return LocalDate.parse(v);
            } catch (RuntimeException e) {
                System.out.println("  Please type a date like " + today + ", or today or tomorrow.");
            }
        }
    }

    private int askNumber(String prompt, int min, int max) {
        while (true) {
            String v = need(prompt).replace(":00", "");
            try {
                int n = Integer.parseInt(v);
                if (n >= min && n <= max) return n;
            } catch (NumberFormatException e) {
                // not a number: fall through and ask again
            }
            System.out.println("  Please type a whole number from " + min + " to " + max + ".");
        }
    }

    /** Anything starting with y means yes; anything else means no. */
    private boolean askYesNo(String prompt) {
        return ask(prompt).toLowerCase().startsWith("y");
    }
}

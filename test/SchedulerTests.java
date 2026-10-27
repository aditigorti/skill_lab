import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;

/**
 * Automatic tests in plain Java (no test library), so they run anywhere Java 8 or newer is installed.
 * Each test sets something up, does one thing, and checks the result. Any failed check throws an error.
 */
public class SchedulerTests {

    static final LocalDate TOMORROW = LocalDate.parse("2026-10-28");
    static final String A = "TeamA", B = "TeamB", C = "TeamC", D = "TeamD", E = "TeamE";
    static int passed = 0, failed = 0;

    /** One test: a piece of code that throws if something is wrong. */
    interface Test {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        System.setProperty("scheduler.now", "2026-10-27T09:00"); // fixed clock: "tomorrow" is always 28 Oct 2026

        System.out.println("Create and cancel reservations");
        test("a free slot is booked and written in the history", SchedulerTests::freeSlotIsBooked);
        test("cancelling keeps the booking, frees the time, and is logged", SchedulerTests::cancelKeepsBooking);
        test("cancelling twice, or after the start, is refused", SchedulerTests::cancelTwiceOrStartedRefused);
        test("impossible requests are rejected and nothing is recorded", SchedulerTests::impossibleRequestsRejected);

        System.out.println("Detect conflicts");
        test("a clash is refused, names the booking, and can wait", SchedulerTests::clashIsRefusedAndNamed);
        test("touching bookings (10-12 and 12-14) are allowed", SchedulerTests::touchingIsAllowed);
        test("a team cannot overlap its own booking", SchedulerTests::ownOverlapRefused);

        System.out.println("Enforce fairness rules");
        test("lab hours and past times are refused and recorded", SchedulerTests::hoursAndPastRefused);
        test("3-hour limit: touching bookings count as one stretch", SchedulerTests::backToBackLimit);
        test("4-hour daily limit catches a split with a gap", SchedulerTests::dailyLimit);
        test("only a clash can wait, and only once", SchedulerTests::onlyClashCanWait);
        test("a freed slot goes to the waiting team first in line", SchedulerTests::handoverToFirstInLine);
        test("A,B,C,D,E: D wins then C wins gives A,B,E,D,C", SchedulerTests::winnerMovesToBack);
        test("a team that would break a rule is passed over and keeps its place", SchedulerTests::passedOverKeepsPlace);
        test("a wait whose time has passed expires", SchedulerTests::oldWaitExpires);

        System.out.println("Suggest available slots");
        test("every suggested time and item really is free", SchedulerTests::suggestionsAreFree);

        System.out.println("Report utilization and refused requests");
        test("the usage report adds up a hand-worked example", SchedulerTests::usageAddsUp);

        System.out.println("Retain booking history (saving and loading)");
        test("saving and loading keeps everything, including the priority line", SchedulerTests::saveAndLoad);
        test("a damaged file stops the load and names the line", SchedulerTests::damagedFileStopsLoad);
        test("the sample data follows every rule and has the demo ready", SchedulerTests::sampleDataIsValid);

        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        System.exit(failed == 0 ? 0 : 1);
    }

    static void test(String name, Test t) {
        try {
            t.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("  FAIL  " + name + " -> " + e);
        }
    }

    // ----- Create and cancel -----

    static void freeSlotIsBooked() {
        Scheduler s = newScheduler();
        Booking b = book(s, "CAM-01", A, 10, 12);
        equal("R001", b.id, "first booking ID");
        check(s.history.get(0).contains("Booked | R001"), "history line: " + s.history.get(0));
    }

    static void cancelKeepsBooking() {
        Scheduler s = newScheduler();
        Booking b = book(s, "CAM-01", A, 10, 12);
        s.cancel(b);
        check(b.cancelled && s.bookings.contains(b), "the booking is kept, marked cancelled");
        book(s, "CAM-01", B, 10, 12); // the time is free again
        check(s.history.get(1).contains("Cancelled | R001"), "cancel is logged");
    }

    static void cancelTwiceOrStartedRefused() throws Exception {
        Scheduler s = newScheduler();
        Booking b = book(s, "CAM-01", A, 10, 12);
        s.cancel(b);
        expectError(() -> s.cancel(b), "cancel twice");
        Booking today = s.request("CAM-01", A, TOMORROW.minusDays(1), 10, 12).booking;
        System.setProperty("scheduler.now", "2026-10-27T10:30"); // the booking has now started
        try {
            expectError(() -> s.cancel(today), "cancel after the start");
        } finally {
            System.setProperty("scheduler.now", "2026-10-27T09:00");
        }
    }

    static void impossibleRequestsRejected() throws Exception {
        Scheduler s = newScheduler();
        expectError(() -> s.request("CAM-01", A, TOMORROW, 12, 12), "zero length");
        expectError(() -> s.request("CAM-01", A, TOMORROW, 14, 12), "backwards");
        expectError(() -> s.request("CAM-01", A, TOMORROW, 23, 25), "past 24");
        expectError(() -> s.request("CAM-99", A, TOMORROW, 10, 12), "unknown item");
        expectError(() -> s.request("CAM-01", "Nobody", TOMORROW, 10, 12), "unknown team");
        expectError(() -> s.request("CAM-01", A, null, 10, 12), "no date");
        check(s.bookings.isEmpty() && s.requests.isEmpty() && s.history.isEmpty(), "nothing was recorded");
    }

    // ----- Conflicts -----

    static void clashIsRefusedAndNamed() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 12);
        Scheduler.Outcome o = s.request("CAM-01", B, TOMORROW, 11, 13);
        check(o.booking == null && o.refused != null, "refused");
        equal(Scheduler.CLASH, o.refused.reason, "reason");
        check(o.check.messages.get(0).contains("R001"), "names the booking: " + o.check.messages);
        check(o.check.canWait(), "a clash can wait");
    }

    static void touchingIsAllowed() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 12);
        book(s, "CAM-01", B, 12, 14);
    }

    static void ownOverlapRefused() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 12);
        equal(Scheduler.OWN_OVERLAP, refuse(s, "CAM-01", A, 11, 12).reason, "reason");
    }

    // ----- Fairness rules -----

    static void hoursAndPastRefused() {
        Scheduler s = newScheduler();
        equal(Scheduler.OUTSIDE_HOURS, refuse(s, "CAM-01", A, 7, 9).reason, "before opening");
        equal(Scheduler.OUTSIDE_HOURS, refuse(s, "CAM-01", A, 19, 21).reason, "after closing");
        Scheduler.Outcome o = s.request("CAM-01", A, TOMORROW.minusDays(1), 8, 9); // 08:00 today, it is 09:00
        equal(Scheduler.PAST, o.refused.reason, "in the past");
        equal(3, s.requests.size(), "all three recorded");
    }

    static void backToBackLimit() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 12);
        Request r = refuse(s, "CAM-01", A, 12, 14); // would be 10-14, four hours in a row
        equal(Scheduler.IN_A_ROW, r.reason, "reason");
        book(s, "CAM-01", A, 12, 13); // 10-13 is exactly three: allowed
        book(s, "CAM-02", A, 13, 16); // a different item is fine
    }

    static void dailyLimit() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 8, 11);
        equal(Scheduler.DAILY, refuse(s, "CAM-01", A, 12, 14).reason, "3 + 2 hours with a gap");
        book(s, "CAM-01", A, 12, 13); // 3 + 1 = 4 is allowed
    }

    static void onlyClashCanWait() throws Exception {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 8, 11);
        Request daily = refuse(s, "CAM-01", A, 12, 14);
        expectError(() -> s.joinWaitingList(daily), "a rule break cannot wait");
        book(s, "CAM-01", B, 14, 16);
        Request clash = refuse(s, "CAM-01", C, 14, 16);
        s.joinWaitingList(clash);
        expectError(() -> s.joinWaitingList(clash), "waiting twice");
    }

    static void handoverToFirstInLine() {
        Scheduler s = newScheduler();
        Booking held = book(s, "CAM-01", A, 10, 12);
        waitFor(s, "CAM-01", E, 10, 12); // E asks first...
        waitFor(s, "CAM-01", B, 10, 12); // ...but B is higher in the line
        List<String> notes = s.cancel(held);
        check(notes.get(0).contains("Handed to TeamB"), "B gets it: " + notes);
        equal(Arrays.asList(A, C, D, E, B), s.teams, "B moves to the back");
    }

    static void winnerMovesToBack() {
        Scheduler s = newScheduler();
        winHandover(s, "CAM-01", D);
        equal(Arrays.asList(A, B, C, E, D), s.teams, "after D wins");
        winHandover(s, "CAM-02", C);
        equal(Arrays.asList(A, B, E, D, C), s.teams, "after C wins");
    }

    static void passedOverKeepsPlace() {
        Scheduler s = newScheduler();
        Booking held = book(s, "CAM-01", E, 10, 12);
        waitFor(s, "CAM-01", A, 10, 12);
        waitFor(s, "CAM-01", B, 10, 12);
        book(s, "CAM-01", A, 8, 10); // now 10-12 would give A four hours in a row
        List<String> notes = s.cancel(held);
        check(notes.get(0).contains("TeamA was passed over"), "A passed over openly: " + notes);
        check(notes.get(1).contains("Handed to TeamB"), "B gets it: " + notes);
        equal(Arrays.asList(A, C, D, E, B), s.teams, "A keeps first place");
    }

    static void oldWaitExpires() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 12);
        Request w = waitFor(s, "CAM-01", B, 10, 12);
        System.setProperty("scheduler.now", "2026-10-28T10:00");
        try {
            equal(1, s.expireOldWaits(), "expired count");
            equal("expired", w.status, "status");
        } finally {
            System.setProperty("scheduler.now", "2026-10-27T09:00");
        }
    }

    // ----- Suggestions -----

    static void suggestionsAreFree() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 12);
        book(s, "CAM-01", B, 14, 17);
        List<Integer> starts = s.freeStarts("CAM-01", C, TOMORROW, 2);
        equal(Arrays.asList(8, 12, 17, 18), starts, "two-hour starts around 10-12 and 14-17");
        for (int h : starts) check(s.check("CAM-01", C, TOMORROW, h, h + 2).ok(), "start " + h + " passes");
        equal(Arrays.asList("CAM-02"), s.sameTypeAlternatives("CAM-01", C, TOMORROW, 10, 12), "other camera");
    }

    // ----- Reports -----

    static void usageAddsUp() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 8, 11);   // 3 hours
        book(s, "CAM-01", B, 12, 15);  // 3 hours
        s.cancel(book(s, "CAM-02", C, 9, 10)); // cancelled: does not count
        Map<String, Integer> perItem = s.usage(TOMORROW, TOMORROW, false);
        equal(6, perItem.get("CAM-01"), "CAM-01 hours");
        equal(0, perItem.get("CAM-02"), "CAM-02 hours");
        equal(50, Main.percent(6, 12), "6 of 12 open hours is 50%");
        equal(3, s.usage(TOMORROW, TOMORROW, true).get(A), "TeamA hours");
    }

    // ----- Saving and loading -----

    static void saveAndLoad() throws Exception {
        Scheduler s = newScheduler();
        winHandover(s, "CAM-01", D);
        refuse(s, "CAM-01", A, 7, 9);
        Path dir = Files.createTempDirectory("scheduler-test");
        Storage.save(s, dir);
        Scheduler loaded = Storage.load(dir);
        equal(s.equipment, loaded.equipment, "equipment");
        equal(s.teams, loaded.teams, "priority line");
        equal(s.history, loaded.history, "history");
        equal(s.bookings.size(), loaded.bookings.size(), "bookings");
        equal(s.bookings.get(0).toString(), loaded.bookings.get(0).toString(), "cancelled booking");
        equal(s.requests.toString(), loaded.requests.toString(), "refused requests");
        equal("R003", book(loaded, "CAM-02", A, 15, 16).id, "IDs carry on");
    }

    static void damagedFileStopsLoad() throws Exception {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 12);
        Path dir = Files.createTempDirectory("scheduler-test");
        Storage.save(s, dir);
        Files.write(dir.resolve("bookings.txt"), Arrays.asList("R001|CAM-01|TeamA|not-a-date|10|12|false"));
        try {
            Storage.load(dir);
            throw new AssertionError("load should have failed");
        } catch (IOException e) {
            check(e.getMessage().contains("bookings.txt line 1"), "names the line: " + e.getMessage());
        }
    }

    static void sampleDataIsValid() {
        Scheduler s = SampleData.create();
        for (Booking a : s.bookings) {
            for (Booking b : s.bookings) {
                boolean sameSlot = a != b && !a.cancelled && !b.cancelled && a.equipment.equals(b.equipment)
                        && a.date.equals(b.date) && a.overlaps(b.start, b.end);
                check(!sameSlot, "overlap: " + a + " / " + b);
            }
        }
        List<Request> waiting = new ArrayList<>();
        for (Request r : s.requests) if (r.isWaiting()) waiting.add(r);
        equal(2, waiting.size(), "two teams waiting");
        check(s.placeInLine(waiting.get(1).team) < s.placeInLine(waiting.get(0).team), "the later asker is ahead");
        equal("2026-10-27T09:00", System.getProperty("scheduler.now"), "the clock is put back");
    }

    // ----- Helpers -----

    /** Three items (two cameras) and five teams in the order A, B, C, D, E. */
    static Scheduler newScheduler() {
        Scheduler s = new Scheduler();
        s.equipment.put("CAM-01", "Camera");
        s.equipment.put("CAM-02", "Camera");
        s.equipment.put("BRD-01", "Dev board");
        Collections.addAll(s.teams, A, B, C, D, E);
        return s;
    }

    static Booking book(Scheduler s, String item, String team, int start, int end) {
        Scheduler.Outcome o = s.request(item, team, TOMORROW, start, end);
        check(o.booking != null, team + " should get " + item + " " + start + "-" + end + ": " + o.check.messages);
        return o.booking;
    }

    static Request refuse(Scheduler s, String item, String team, int start, int end) {
        Scheduler.Outcome o = s.request(item, team, TOMORROW, start, end);
        check(o.refused != null, team + " should be refused " + item + " " + start + "-" + end);
        return o.refused;
    }

    static Request waitFor(Scheduler s, String item, String team, int start, int end) {
        Request r = refuse(s, item, team, start, end);
        s.joinWaitingList(r);
        return r;
    }

    /** The winner waits for a slot held by someone else, which is then cancelled, so the winner gets it. */
    static void winHandover(Scheduler s, String item, String winner) {
        String holder = winner.equals(E) ? A : E;
        Booking held = book(s, item, holder, 10, 12);
        waitFor(s, item, winner, 10, 12);
        s.cancel(held);
    }

    interface Action {
        void run() throws Exception;
    }

    static void expectError(Action a, String what) throws Exception {
        try {
            a.run();
        } catch (IllegalArgumentException | IllegalStateException e) {
            return;
        }
        throw new AssertionError(what + " should have been refused with an error");
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void equal(Object expected, Object actual, String what) {
        if (!Objects.equals(expected, actual)) throw new AssertionError(what + ": expected " + expected + " but got " + actual);
    }
}

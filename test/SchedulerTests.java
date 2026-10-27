import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Automated tests for the scheduler. No outside libraries: just plain Java.
 *
 * Run with run-tests.bat (Windows) or ./run-tests.sh (macOS / Linux).
 * Each test prints PASS or FAIL. The program ends with exit code 1 if anything failed.
 *
 * The clock is fixed at Tue 27 Oct 2026, 09:00, so "tomorrow" is always Wed 28 Oct 2026.
 */
public final class SchedulerTests {

    private static final LocalDate TOMORROW = LocalDate.parse("2026-10-28");

    /** The five test groups, written A to E in the tests. (Group names must be 2 to 20 characters.) */
    private static final String A = "TeamA", B = "TeamB", C = "TeamC", D = "TeamD", E = "TeamE", F = "TeamF";
    private static int passed = 0;
    private static int failed = 0;
    private static final List<Path> tempFolders = new ArrayList<Path>();

    /** One test: a piece of code that throws if something is wrong. */
    interface Test {
        void run() throws Exception;
    }

    public static void main(String[] args) {
        System.setProperty("scheduler.now", "2026-10-27T09:00");

        System.out.println("Fairness queue");
        test("the queue starts in the order the groups were added", SchedulerTests::queueStartsInGroupOrder);
        test("A,B,C,D,E: D wins then C wins gives A,B,E,D,C", SchedulerTests::winnerMovesToBack);
        test("a passed-over group keeps its place in the queue", SchedulerTests::passedOverKeepsPlace);
        test("the queue order survives saving and loading", SchedulerTests::queueSurvivesSaveAndLoad);
        test("old data files with only a rotation pointer still load", SchedulerTests::oldPointerFileStillLoads);
        test("a new group joins the end of the queue", SchedulerTests::newGroupJoinsEnd);
        test("with rotating priority off, the earliest request wins", SchedulerTests::rotationOffMeansFirstCome);

        System.out.println();
        System.out.println("Safe loading");
        test("a failed reload keeps the last good data in memory", SchedulerTests::failedReloadKeepsLastGoodData);
        test("after a failed reload, saving is refused and the files are untouched", SchedulerTests::saveBlockedAfterFailedReload);
        test("once the files can be read again, reload and save work", SchedulerTests::reloadWorksAgain);
        test("a damaged line found on reload is backed up and reported", SchedulerTests::damagedLineOnReloadIsBackedUp);
        test("a failed load leaves the scheduler exactly as it was", SchedulerTests::failedLoadLeavesSchedulerUntouched);

        System.out.println();
        System.out.println("Core validation (the scheduler protects itself, not just the menu)");
        test("a zero-length booking is rejected and nothing is recorded", SchedulerTests::zeroLengthRejected);
        test("a backwards booking is rejected", SchedulerTests::backwardsRejected);
        test("hours outside 0 to 24 are rejected", SchedulerTests::hoursOutOfRangeRejected);
        test("unknown equipment is rejected", SchedulerTests::unknownEquipmentRejected);
        test("an unknown group is rejected", SchedulerTests::unknownGroupRejected);
        test("a booking with no date is rejected", SchedulerTests::missingDateRejected);
        test("cancelling the same booking twice is refused", SchedulerTests::cancelTwiceRefused);
        test("cancelling a booking that has started is refused", SchedulerTests::cancelStartedRefused);
        test("leaving the waiting list twice is refused", SchedulerTests::withdrawTwiceRefused);
        test("adding a duplicate group or a bad equipment ID is refused", SchedulerTests::addChecks);

        System.out.println();
        System.out.println("Existing behaviour (must keep working)");
        test("a clash is refused, names the booking, and can wait", SchedulerTests::clashIsRefusedAndNamed);
        test("a booking that only touches another one is allowed", SchedulerTests::touchingIsAllowed);
        test("touching bookings count as one stretch (3-hour limit)", SchedulerTests::backToBackLimit);
        test("the daily limit catches a split with a gap (4-hour limit)", SchedulerTests::dailyLimitAcrossGap);
        test("a cancelled booking no longer counts towards the limits", SchedulerTests::cancelledDoesNotCount);
        test("outside lab hours and past times are refused and recorded", SchedulerTests::outsideHoursAndPast);
        test("a cancelled slot goes to the waiting group first in the queue", SchedulerTests::handoverGoesToFirstInQueue);
        test("one freed slot can serve two shorter waits", SchedulerTests::oneSlotServesTwoWaits);
        test("a wait that never comes good expires at its start time", SchedulerTests::waitExpires);
        test("the usage report adds up a small hand-worked example", SchedulerTests::usageReportAddsUp);
        test("saving and loading keeps every record", SchedulerTests::saveAndLoadRoundTrip);

        cleanUp();
        System.out.println();
        System.out.println(passed + " passed, " + failed + " failed");
        if (failed > 0) {
            System.exit(1);
        }
    }

    // ===================================================================
    // Fairness queue
    // ===================================================================

    static void queueStartsInGroupOrder() {
        Scheduler s = newScheduler();
        equal(Arrays.asList(A, B, C, D, E), s.priorityLine(), "starting queue");
    }

    static void winnerMovesToBack() {
        Scheduler s = newScheduler();
        winHandover(s, "CAM-01", D);
        equal(Arrays.asList(A, B, C, E, D), s.priorityLine(), "queue after D wins");
        winHandover(s, "CAM-02", C);
        equal(Arrays.asList(A, B, E, D, C), s.priorityLine(), "queue after C wins");
    }

    static void passedOverKeepsPlace() {
        Scheduler s = newScheduler();
        Booking held = book(s, "CAM-01", E, 10, 12);
        waitFor(s, "CAM-01", A, 10, 12);           // A is first in the queue
        waitFor(s, "CAM-01", B, 10, 12);
        book(s, "CAM-01", A, 8, 10);               // now 10-12 would give A 4 hours in a row
        Scheduler.Handover h = s.cancel(held);
        equal(1, h.skipped.size(), "groups passed over");
        equal(B, h.granted.get(0).group, "winner");
        equal(Arrays.asList(A, C, D, E, B), s.priorityLine(), "queue after B wins");
    }

    static void queueSurvivesSaveAndLoad() throws IOException {
        Scheduler s = newScheduler();
        winHandover(s, "CAM-01", D);
        winHandover(s, "CAM-02", C);
        Path dir = tempFolder();
        Storage.save(s, dir);
        Scheduler loaded = new Scheduler();
        Storage.load(loaded, dir);
        equal(Arrays.asList(A, B, E, D, C), loaded.priorityLine(), "queue after loading");
    }

    static void oldPointerFileStillLoads() throws IOException {
        Scheduler s = newScheduler();
        Path dir = tempFolder();
        Storage.save(s, dir);
        // Rewrite settings.txt the way older versions wrote it: a pointer, no queue.
        Path settings = dir.resolve("settings.txt");
        List<String> lines = new ArrayList<String>();
        for (String line : Files.readAllLines(settings)) {
            if (!line.startsWith("priorityQueue=") && !line.startsWith("rotationPointer=")) lines.add(line);
        }
        lines.add("rotationPointer=2");
        Files.write(settings, lines);
        Scheduler loaded = new Scheduler();
        Storage.load(loaded, dir);
        equal(Arrays.asList(C, D, E, A, B), loaded.priorityLine(), "queue rebuilt from the old pointer");
    }

    static void newGroupJoinsEnd() {
        Scheduler s = newScheduler();
        winHandover(s, "CAM-01", B);
        s.addGroup(F);
        equal(Arrays.asList(A, C, D, E, B, F), s.priorityLine(), "queue after adding F");
    }

    static void rotationOffMeansFirstCome() {
        Scheduler s = newScheduler();
        s.changeRules(null, null, null, null, false);
        Booking held = book(s, "CAM-01", A, 10, 12);
        waitFor(s, "CAM-01", E, 10, 12);           // E asks first
        waitFor(s, "CAM-01", B, 10, 12);           // B is earlier in the queue but asks later
        Scheduler.Handover h = s.cancel(held);
        equal(E, h.granted.get(0).group, "winner with rotation off");
        equal(Arrays.asList(A, B, C, D, E), s.priorityLine(), "queue is not moved when rotation is off");
    }

    // ===================================================================
    // Safe loading
    // ===================================================================

    /**
     * Sets up the situation from the audit: a window has 10 bookings loaded, another window saves
     * an 11th, and then bookings.txt cannot be read at the moment the first window reloads.
     * Returns {folder, window}.
     */
    static Object[] windowWithUnreadableFile() throws IOException {
        Path dir = tempFolder();
        Scheduler other = newScheduler();
        for (int d = 1; d <= 10; d++) {
            other.request("CAM-01", A, TOMORROW.plusDays(d), 10, 11);
        }
        Storage.save(other, dir);
        Scheduler window = new Scheduler();
        Storage.load(window, dir);
        other.request("CAM-02", B, TOMORROW, 10, 11);  // the other window saves an 11th booking
        Storage.save(other, dir);
        makeUnreadable(dir.resolve("bookings.txt"));     // like a file locked for a moment on Windows
        return new Object[] { dir, window };
    }

    static void failedReloadKeepsLastGoodData() throws IOException {
        Object[] setup = windowWithUnreadableFile();
        final Path dir = (Path) setup[0];
        final Scheduler window = (Scheduler) setup[1];
        expectError(IOException.class, () -> Storage.reloadIfChanged(window, dir, new ArrayList<String>()), "reload");
        equal(10, window.bookings.size(), "bookings still in memory");
        equal(4, window.equipment.size(), "equipment still in memory");
        equal(5, window.groups.size(), "groups still in memory");
        makeReadable(dir.resolve("bookings.txt"));
    }

    static void saveBlockedAfterFailedReload() throws IOException {
        Object[] setup = windowWithUnreadableFile();
        final Path dir = (Path) setup[0];
        final Scheduler window = (Scheduler) setup[1];
        expectError(IOException.class, () -> Storage.reloadIfChanged(window, dir, new ArrayList<String>()), "reload");
        window.request("OSC-01", A, TOMORROW, 14, 15);  // someone carries on anyway
        expectError(IOException.class, () -> Storage.save(window, dir), "save after a failed reload");
        makeReadable(dir.resolve("bookings.txt"));
        Scheduler disk = new Scheduler();
        Storage.load(disk, dir);
        equal(11, disk.bookings.size(), "bookings still on disk");
    }

    static void reloadWorksAgain() throws IOException {
        Object[] setup = windowWithUnreadableFile();
        final Path dir = (Path) setup[0];
        final Scheduler window = (Scheduler) setup[1];
        expectError(IOException.class, () -> Storage.reloadIfChanged(window, dir, new ArrayList<String>()), "reload");
        makeReadable(dir.resolve("bookings.txt"));
        check(Storage.reloadIfChanged(window, dir, new ArrayList<String>()), "reload should now succeed");
        equal(11, window.bookings.size(), "bookings after reloading");
        window.request("OSC-01", A, TOMORROW, 14, 15);
        Storage.save(window, dir);                       // must not throw any more
        Scheduler disk = new Scheduler();
        Storage.load(disk, dir);
        equal(12, disk.bookings.size(), "bookings on disk after saving");
    }

    static void damagedLineOnReloadIsBackedUp() throws IOException {
        Path dir = tempFolder();
        Scheduler other = newScheduler();
        other.request("CAM-01", A, TOMORROW, 10, 11);
        Storage.save(other, dir);
        Scheduler window = new Scheduler();
        Storage.load(window, dir);
        other.request("CAM-02", B, TOMORROW, 10, 11);
        Storage.save(other, dir);
        Path file = dir.resolve("bookings.txt");
        List<String> lines = new ArrayList<String>(Files.readAllLines(file));
        lines.set(2, lines.get(2).replace("2026-10-28", "2026-1O-28")); // letter O: cannot be read
        Files.write(file, lines);
        List<String> warnings = new ArrayList<String>();
        check(Storage.reloadIfChanged(window, dir, warnings), "reload should succeed");
        equal(1, window.bookings.size(), "readable bookings loaded");
        check(warnings.size() >= 2, "the damaged line and the backup are both reported: " + warnings);
        equal(1, countBackups(dir), "backup folders");
    }

    static void failedLoadLeavesSchedulerUntouched() throws IOException {
        Path dir = tempFolder();
        Storage.save(newScheduler(), dir);
        makeUnreadable(dir.resolve("history.txt"));      // the last file read, so the others load first
        final Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 11);
        final Path folder = dir;
        expectError(IOException.class, () -> Storage.load(s, folder), "load");
        equal(1, s.bookings.size(), "bookings kept");
        equal(5, s.groups.size(), "groups kept");
    }

    // ===================================================================
    // Core validation
    // ===================================================================

    /** The request must throw IllegalArgumentException and leave no booking, refusal or history line behind. */
    static void expectRejected(final Scheduler s, final String item, final String group, final LocalDate date,
                               final int start, final int end, String what) {
        int bookings = s.bookings.size();
        int denied = s.denied.size();
        int history = s.history.size();
        expectError(IllegalArgumentException.class, () -> s.request(item, group, date, start, end), what);
        equal(bookings, s.bookings.size(), what + ": bookings");
        equal(denied, s.denied.size(), what + ": refusals recorded");
        equal(history, s.history.size(), what + ": history lines");
    }

    static void zeroLengthRejected() {
        expectRejected(newScheduler(), "CAM-01", A, TOMORROW, 12, 12, "12:00-12:00");
    }

    static void backwardsRejected() {
        expectRejected(newScheduler(), "CAM-01", A, TOMORROW, 15, 13, "15:00-13:00");
    }

    static void hoursOutOfRangeRejected() {
        Scheduler s = newScheduler();
        expectRejected(s, "CAM-01", A, TOMORROW, 23, 25, "23:00-25:00");
        expectRejected(s, "CAM-01", A, TOMORROW, -1, 2, "-1:00-02:00");
    }

    static void unknownEquipmentRejected() {
        expectRejected(newScheduler(), "XYZ-99", A, TOMORROW, 10, 11, "unknown equipment");
    }

    static void unknownGroupRejected() {
        expectRejected(newScheduler(), "CAM-01", "Nobody", TOMORROW, 10, 11, "unknown group");
    }

    static void missingDateRejected() {
        expectRejected(newScheduler(), "CAM-01", A, null, 10, 11, "no date");
    }

    static void cancelTwiceRefused() {
        final Scheduler s = newScheduler();
        final Booking b = book(s, "CAM-01", A, 10, 11);
        s.cancel(b);
        expectError(IllegalStateException.class, () -> s.cancel(b), "second cancel");
        int lines = 0;
        for (HistoryEntry h : s.history) {
            if (h.action.equals(HistoryEntry.CANCELLED) && h.ref.equals(b.id)) lines++;
        }
        equal(1, lines, "'Cancelled' lines in the history");
    }

    static void cancelStartedRefused() {
        String before = System.getProperty("scheduler.now");
        try {
            final Scheduler s = newScheduler();
            final Booking b = book(s, "CAM-01", A, 10, 12);   // booked the day before
            System.setProperty("scheduler.now", "2026-10-28T10:30");
            expectError(IllegalStateException.class, () -> s.cancel(b), "cancel at 10:30");
            check(b.isActive(), "the booking is still active");
        } finally {
            System.setProperty("scheduler.now", before);
        }
    }

    static void withdrawTwiceRefused() {
        final Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 12);
        waitFor(s, "CAM-01", B, 10, 12);
        final WaitlistEntry w = s.waitlist.get(0);
        s.withdraw(w);
        expectError(IllegalStateException.class, () -> s.withdraw(w), "second withdraw");
    }

    static void addChecks() {
        final Scheduler s = newScheduler();
        expectError(IllegalArgumentException.class, () -> s.addGroup(A), "duplicate group");
        expectError(IllegalArgumentException.class, () -> s.addGroup("has space"), "group name with a space");
        expectError(IllegalArgumentException.class, () -> s.addEquipment(new Equipment("CAM-01", "Camera", "again")), "duplicate equipment");
        expectError(IllegalArgumentException.class, () -> s.addEquipment(new Equipment("BAD ID", "Camera", "x")), "equipment ID with a space");
        equal(5, s.groups.size(), "groups unchanged");
        equal(4, s.equipment.size(), "equipment unchanged");
    }

    static void makeUnreadable(Path file) throws IOException {
        Files.move(file, file.resolveSibling(file.getFileName() + ".aside"));
        Files.createDirectory(file); // reading a folder as a file fails, like a locked file
    }

    static void makeReadable(Path file) throws IOException {
        Files.delete(file);
        Files.move(file.resolveSibling(file.getFileName() + ".aside"), file);
    }

    // ===================================================================
    // Existing behaviour
    // ===================================================================

    static void clashIsRefusedAndNamed() {
        Scheduler s = newScheduler();
        Booking held = book(s, "CAM-01", A, 10, 13);
        Scheduler.Outcome o = s.request("CAM-01", B, TOMORROW, 11, 12);
        check(o.booking == null, "should be refused");
        equal(DenyReason.CLASH, o.denial.reason, "reason");
        check(o.denial.explanation.contains(held.id), "explanation names " + held.id + ": " + o.denial.explanation);
        check(o.check.canWaitlist(), "a pure clash can join the waiting list");
    }

    static void touchingIsAllowed() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 13);
        check(s.request("CAM-01", B, TOMORROW, 13, 14).booking != null, "13-14 next to 10-13 should be booked");
    }

    static void backToBackLimit() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 12);
        Scheduler.Outcome o = s.request("CAM-01", A, TOMORROW, 12, 14);
        check(o.booking == null, "10-12 then 12-14 is 4 hours in a row and must be refused");
        equal(DenyReason.BACK_TO_BACK_LIMIT, o.denial.reason, "reason");
        check(s.request("CAM-01", A, TOMORROW, 13, 15).booking != null, "13-15 leaves a gap and should be booked");
    }

    static void dailyLimitAcrossGap() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 8, 11);
        Scheduler.Outcome o = s.request("CAM-01", A, TOMORROW, 12, 14);
        check(o.booking == null, "3 + 2 hours on one item in a day is over the 4-hour limit");
        equal(DenyReason.DAILY_LIMIT, o.denial.reason, "reason");
    }

    static void cancelledDoesNotCount() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 8, 10);
        Booking second = book(s, "CAM-01", A, 13, 15);
        check(s.request("CAM-01", A, TOMORROW, 16, 17).booking == null, "at the 4-hour limit, 16-17 must be refused");
        s.cancel(second);
        check(s.request("CAM-01", A, TOMORROW, 16, 18).booking != null, "after cancelling 13-15, 16-18 should be booked");
    }

    static void outsideHoursAndPast() {
        Scheduler s = newScheduler();
        Scheduler.Outcome late = s.request("CAM-01", A, TOMORROW, 19, 21);
        equal(DenyReason.OUTSIDE_LAB_HOURS, late.denial.reason, "19-21 reason");
        Scheduler.Outcome past = s.request("CAM-01", A, LocalDate.parse("2026-10-27"), 8, 9);
        equal(DenyReason.IN_THE_PAST, past.denial.reason, "today 08:00 reason (it is 09:00)");
        equal(2, s.denied.size(), "both refusals recorded");
    }

    static void handoverGoesToFirstInQueue() {
        Scheduler s = newScheduler();
        Booking held = book(s, "CAM-01", E, 10, 12);
        waitFor(s, "CAM-01", C, 10, 12);           // C asks first
        waitFor(s, "CAM-01", B, 10, 12);           // B asks later but is ahead in the queue
        Scheduler.Handover h = s.cancel(held);
        equal(1, h.granted.size(), "one handover");
        equal(B, h.granted.get(0).group, "winner");
        check(h.granted.get(0).source.equals(Booking.FROM_WAITLIST), "marked as from the waiting list");
    }

    static void oneSlotServesTwoWaits() {
        Scheduler s = newScheduler();
        Booking held = book(s, "CAM-01", E, 10, 13);
        waitFor(s, "CAM-01", A, 10, 11);
        waitFor(s, "CAM-01", B, 12, 13);
        Scheduler.Handover h = s.cancel(held);
        equal(2, h.granted.size(), "both short waits served");
        equal(0, s.waitlist.size(), "waiting list empty");
    }

    static void waitExpires() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 10, 12);
        waitFor(s, "CAM-01", B, 10, 12);
        String before = System.getProperty("scheduler.now");
        try {
            System.setProperty("scheduler.now", "2026-10-28T10:00");
            equal(1, s.expireWaitlist(), "entries expired at 10:00 tomorrow");
            equal(DeniedRequest.EXPIRED, s.denied.get(0).afterwards, "denied request marked as never got it");
        } finally {
            System.setProperty("scheduler.now", before);
        }
    }

    static void usageReportAddsUp() {
        Scheduler s = newScheduler();
        book(s, "CAM-01", A, 9, 12);               // 3 h
        book(s, "CAM-01", B, 13, 14);              // 1 h
        Booking c = book(s, "CAM-02", A, 10, 12);  // cancelled: counts as cancelled, not hours
        s.cancel(c);
        s.request("CAM-01", C, TOMORROW, 10, 11);  // clash: one refusal for C
        Reports.Usage u = Reports.usage(s, TOMORROW, TOMORROW);
        equal(4, u.totalHours, "total hours");
        equal(12, u.openPerItem, "hours each item could be booked (08-20)");
        equal(33, u.items.get(0).percent, "CAM-01 use (4 of 12 hours)");
        Reports.GroupUse a = u.groups.get(0);
        equal(3, a.hours, "A hours");
        equal(75, a.share, "A share of 4 hours");
        equal(1, a.cancelled, "A cancelled");
        equal(1, u.groups.get(2).denied, "C refused");
    }

    static void saveAndLoadRoundTrip() throws IOException {
        Scheduler s = newScheduler();
        Booking held = book(s, "CAM-01", A, 10, 12);
        waitFor(s, "CAM-01", B, 10, 12);
        book(s, "CAM-02", C, 14, 16);
        s.request("CAM-01", C, TOMORROW, 19, 21);
        Booking cancelled = book(s, "OSC-01", D, 9, 10);
        s.cancel(cancelled);
        Path dir = tempFolder();
        Storage.save(s, dir);
        Scheduler loaded = new Scheduler();
        List<String> problems = Storage.load(loaded, dir);
        equal(0, problems.size(), "problems while loading");
        equal(s.bookings.size(), loaded.bookings.size(), "bookings");
        equal(s.waitlist.size(), loaded.waitlist.size(), "waiting list");
        equal(s.denied.size(), loaded.denied.size(), "refusals");
        equal(s.history.size(), loaded.history.size(), "history lines");
        equal(held.id, loaded.findBooking(held.id).id, "booking found by ID");
        check(!loaded.findBooking(cancelled.id).isActive(), "cancelled booking stays cancelled");
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    /** Five groups A to E and four items: three cameras and a scope. */
    static Scheduler newScheduler() {
        Scheduler s = new Scheduler();
        s.addEquipment(new Equipment("CAM-01", "Camera", "Camera one"));
        s.addEquipment(new Equipment("CAM-02", "Camera", "Camera two"));
        s.addEquipment(new Equipment("CAM-03", "Camera", "Camera three"));
        s.addEquipment(new Equipment("OSC-01", "Scope", "Oscilloscope"));
        for (String g : new String[] { A, B, C, D, E }) {
            s.addGroup(g);
        }
        return s;
    }

    static Booking book(Scheduler s, String item, String group, int start, int end) {
        Scheduler.Outcome o = s.request(item, group, TOMORROW, start, end);
        check(o.booking != null, "setup booking " + item + " " + group + " " + start + "-" + end + " was refused: " + o.check.messages);
        return o.booking;
    }

    static void waitFor(Scheduler s, String item, String group, int start, int end) {
        Scheduler.Outcome o = s.request(item, group, TOMORROW, start, end);
        check(o.denial != null && o.check.canWaitlist(), "setup: expected a clash " + group + " could wait for: " + o.check.messages);
        s.joinWaitlist(o.denial);
    }

    /** A group outside the race (one not in the queue's way) holds a slot; the winner waits; the holder cancels. */
    static void winHandover(Scheduler s, String item, String winner) {
        String holder = winner.equals(E) ? A : E;
        Booking held = book(s, item, holder, 10, 12);
        waitFor(s, item, winner, 10, 12);
        Scheduler.Handover h = s.cancel(held);
        equal(winner, h.granted.get(0).group, "handover winner");
    }

    static void test(String name, Test t) {
        try {
            t.run();
            passed++;
            System.out.println("  PASS  " + name);
        } catch (Throwable e) {
            failed++;
            System.out.println("  FAIL  " + name);
            System.out.println("        " + e.getMessage());
        }
    }

    static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    static void equal(Object expected, Object actual, String what) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            throw new AssertionError(what + ": expected " + expected + " but got " + actual);
        }
    }

    /** Checks that running the code throws the given kind of error. */
    static void expectError(Class<? extends Throwable> type, Test t, String what) {
        try {
            t.run();
        } catch (Throwable e) {
            if (type.isInstance(e)) return;
            throw new AssertionError(what + ": expected " + type.getSimpleName() + " but got " + e);
        }
        throw new AssertionError(what + ": expected " + type.getSimpleName() + " but nothing was thrown");
    }

    static Path tempFolder() throws IOException {
        Path dir = Files.createTempDirectory("scheduler-test");
        tempFolders.add(dir);
        return dir;
    }

    static int countBackups(Path dir) throws IOException {
        int n = 0;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "backup-*")) {
            for (Path p : ds) n++;
        }
        return n;
    }

    static void cleanUp() {
        Collections.reverse(tempFolders);
        for (Path dir : tempFolders) {
            try {
                deleteAll(dir);
            } catch (IOException e) {
                // a leftover temporary folder does no harm
            }
        }
    }

    static void deleteAll(Path p) throws IOException {
        if (Files.isDirectory(p)) {
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(p)) {
                for (Path c : ds) deleteAll(c);
            }
        }
        Files.deleteIfExists(p);
    }
}

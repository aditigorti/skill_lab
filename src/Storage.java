import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Saves everything to plain text files in the data folder, and reads them back.
 * One record per line, fields separated by "|", so the files can be opened in
 * any text editor. Each file is written to a temporary copy first and then
 * swapped in, so a crash mid-save never leaves a half-written file.
 */
final class Storage {

    private static final String[] FILES = {
        "settings.txt", "equipment.txt", "groups.txt", "bookings.txt", "waitlist.txt", "denied.txt", "history.txt"
    };

    private Storage() { }

    static boolean hasSavedData(Path dir) {
        return Files.isRegularFile(dir.resolve("settings.txt"));
    }

    /**
     * The stamp written by the most recent save. Two open windows of this program compare it
     * with the stamp they last saw, so each picks up changes the other made.
     * Throws IOException if settings.txt cannot be read right now.
     */
    private static String readStamp(Path dir) throws IOException {
        for (String line : Files.readAllLines(dir.resolve("settings.txt"), StandardCharsets.UTF_8)) {
            if (line.startsWith("saveStamp=")) return line.substring("saveStamp=".length()).trim();
        }
        return "";
    }

    /**
     * Reloads everything if another window has saved since this copy last loaded or saved.
     * Returns true if it reloaded.
     *
     * It is safe: the files are read into a separate, temporary scheduler, and copied into s only
     * if every file was read. If reading keeps failing, s keeps its last good data and saving is
     * blocked until a reload works, so incomplete data can never overwrite the files. Damaged lines
     * found along the way are backed up before they are dropped, and described in warnings.
     */
    static boolean reloadIfChanged(Scheduler s, Path dir, List<String> warnings) throws IOException {
        if (!hasSavedData(dir)) {
            return false; // nothing saved yet: keep what we have
        }
        IOException last = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                if (readStamp(dir).equals(s.savedStamp)) {
                    s.saveBlocked = false; // this copy already matches the files
                    return false;
                }
                Scheduler fresh = new Scheduler();
                List<String> problems = readAll(fresh, dir);
                if (!problems.isEmpty()) {
                    Path copy = backup(dir); // keep the original files before the damaged lines are dropped
                    warnings.addAll(problems);
                    warnings.add("A copy of the original files was kept in '" + copy + "'.");
                }
                s.replaceWith(fresh);
                return true;
            } catch (IOException e) {
                last = e; // on Windows a file can be locked for a moment while another window replaces it
                try {
                    Thread.sleep(60);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        s.saveBlocked = true;
        throw last;
    }

    // ===================================================================
    // Saving
    // ===================================================================

    static void save(Scheduler s, Path dir) throws IOException {
        if (s.saveBlocked) {
            throw new IOException("saving is paused because the latest saved data could not be read,"
                    + " so nothing was overwritten. Please try again in a moment.");
        }
        Files.createDirectories(dir);

        List<String> lines = header("# id|type|description");
        for (Equipment e : s.equipment.values()) {
            lines.add(row(e.id, e.type, e.name));
        }
        write(dir.resolve("equipment.txt"), lines);

        lines = header("# One group per line. This order is the order used for rotating priority.");
        for (String g : s.groups) {
            lines.add(enc(g));
        }
        write(dir.resolve("groups.txt"), lines);

        lines = header("# id|equipment|group|date|startHour|endHour|status|createdAt|cancelledAt|source");
        for (Booking b : s.bookings) {
            lines.add(row(b.id, b.equipmentId, b.group, b.date.toString(), String.valueOf(b.start),
                    String.valueOf(b.end), b.status.name(), str(b.createdAt), str(b.cancelledAt), b.source));
        }
        write(dir.resolve("bookings.txt"), lines);

        lines = header("# id|equipment|group|date|startHour|endHour|requestedAt|deniedRequestId");
        for (WaitlistEntry w : s.waitlist) {
            lines.add(row(w.id, w.equipmentId, w.group, w.date.toString(), String.valueOf(w.start),
                    String.valueOf(w.end), str(w.requestedAt), w.deniedId));
        }
        write(dir.resolve("waitlist.txt"), lines);

        lines = header("# id|requestedAt|equipment|group|date|startHour|endHour|reason|explanation|afterwards");
        for (DeniedRequest d : s.denied) {
            lines.add(row(d.id, str(d.requestedAt), d.equipmentId, d.group, d.date.toString(),
                    String.valueOf(d.start), String.valueOf(d.end), d.reason.name(), d.explanation, d.afterwards));
        }
        write(dir.resolve("denied.txt"), lines);

        lines = header("# time|action|ref|equipment|group|detail");
        for (HistoryEntry h : s.history) {
            lines.add(row(str(h.time), h.action, h.ref, h.equipmentId, h.group, h.detail));
        }
        write(dir.resolve("history.txt"), lines);

        // Written last: its presence means a complete save has happened at least once.
        Policy p = s.policy;
        lines = header("# Lab hours, fairness rules and ID counters. Change the rules from menu 9.");
        lines.add("openHour=" + p.openHour);
        lines.add("closeHour=" + p.closeHour);
        lines.add("maxHoursInARow=" + p.maxHoursInARow);
        lines.add("maxHoursPerDay=" + p.maxHoursPerDay);
        lines.add("rotatingPriority=" + p.rotatingPriority);
        lines.add("priorityQueue=" + String.join(",", s.priorityQueue));
        lines.add("nextBookingNo=" + s.nextBookingNo);
        lines.add("nextWaitNo=" + s.nextWaitNo);
        lines.add("nextDeniedNo=" + s.nextDeniedNo);
        String stamp = UUID.randomUUID().toString();
        lines.add("saveStamp=" + stamp);
        write(dir.resolve("settings.txt"), lines);
        s.savedStamp = stamp;
    }

    /** Copies the current data files into a new backup folder inside the data folder. */
    static Path backup(Path dir) throws IOException {
        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(LocalDateTime.now());
        Path target = dir.resolve("backup-" + stamp);
        Files.createDirectories(target);
        for (String f : FILES) {
            Path src = dir.resolve(f);
            if (Files.exists(src)) {
                Files.copy(src, target.resolve(f), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        return target;
    }

    // ===================================================================
    // Loading
    // ===================================================================

    /**
     * Loads everything from the folder into s. The files are read into a separate, temporary
     * scheduler first, and s is replaced only if every file could be read, so a failure leaves
     * s exactly as it was. Returns the damaged lines that were skipped (empty if all is well).
     */
    static List<String> load(Scheduler s, Path dir) throws IOException {
        Scheduler fresh = new Scheduler();
        List<String> problems = readAll(fresh, dir);
        s.replaceWith(fresh);
        return problems;
    }

    /** Reads every file into s, which must be a new, empty scheduler. Throws IOException if a file cannot be read. */
    private static List<String> readAll(Scheduler s, Path dir) throws IOException {
        List<String> problems = new ArrayList<String>();
        Policy p = s.policy;
        List<String> savedQueue = null; // the queue order, if the file has one
        int oldPointer = 0;             // older versions saved a pointer instead of the order

        List<String> lines = read(dir, "settings.txt");
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (skip(line)) continue;
            int eq = line.indexOf('=');
            if (eq < 0) {
                problems.add(lineProblem("settings.txt", i));
                continue;
            }
            String key = line.substring(0, eq).trim();
            String value = line.substring(eq + 1).trim();
            try {
                if (key.equals("openHour")) p.openHour = Integer.parseInt(value);
                else if (key.equals("closeHour")) p.closeHour = Integer.parseInt(value);
                else if (key.equals("maxHoursInARow")) p.maxHoursInARow = Integer.parseInt(value);
                else if (key.equals("maxHoursPerDay")) p.maxHoursPerDay = Integer.parseInt(value);
                else if (key.equals("rotatingPriority")) p.rotatingPriority = Boolean.parseBoolean(value);
                else if (key.equals("priorityQueue")) savedQueue = Arrays.asList(value.split(","));
                else if (key.equals("rotationPointer")) oldPointer = Integer.parseInt(value);
                else if (key.equals("nextBookingNo")) s.nextBookingNo = Integer.parseInt(value);
                else if (key.equals("nextWaitNo")) s.nextWaitNo = Integer.parseInt(value);
                else if (key.equals("nextDeniedNo")) s.nextDeniedNo = Integer.parseInt(value);
                else if (key.equals("saveStamp")) s.savedStamp = value;
            } catch (NumberFormatException e) {
                problems.add(lineProblem("settings.txt", i));
            }
        }
        if (p.openHour < 0 || p.openHour > 23 || p.closeHour <= p.openHour || p.closeHour > 24) {
            problems.add("settings.txt had impossible lab hours, so 08:00-20:00 is used instead.");
            p.openHour = Policy.DEFAULT_OPEN;
            p.closeHour = Policy.DEFAULT_CLOSE;
        }
        if (p.maxHoursInARow < 0) p.maxHoursInARow = Policy.DEFAULT_MAX_IN_A_ROW;
        if (p.maxHoursPerDay < 0) p.maxHoursPerDay = Policy.DEFAULT_MAX_PER_DAY;

        lines = read(dir, "equipment.txt");
        for (int i = 0; i < lines.size(); i++) {
            if (skip(lines.get(i))) continue;
            try {
                String[] f = fields(lines.get(i), 3);
                Equipment e = new Equipment(f[0].toUpperCase(), dec(f[1]), dec(f[2]));
                s.equipment.put(e.id, e);
            } catch (RuntimeException e) {
                problems.add(lineProblem("equipment.txt", i));
            }
        }

        lines = read(dir, "groups.txt");
        for (String line : lines) {
            if (!skip(line) && s.findGroup(line.trim()) == null) {
                s.groups.add(line.trim());
            }
        }
        if (savedQueue == null) {
            // Older file: the pointer meant "the queue starts at this group and wraps around".
            savedQueue = new ArrayList<String>();
            int n = s.groups.size();
            for (int i = 0; i < n; i++) {
                savedQueue.add(s.groups.get(((oldPointer + i) % n + n) % n));
            }
        }
        s.restorePriorityQueue(savedQueue);

        lines = read(dir, "bookings.txt");
        for (int i = 0; i < lines.size(); i++) {
            if (skip(lines.get(i))) continue;
            try {
                String[] f = fields(lines.get(i), 10);
                s.bookings.add(new Booking(f[0], f[1], f[2], LocalDate.parse(f[3]), Integer.parseInt(f[4]),
                        Integer.parseInt(f[5]), Booking.Status.valueOf(f[6]), time(f[7]), time(f[8]), dec(f[9])));
            } catch (RuntimeException e) {
                problems.add(lineProblem("bookings.txt", i));
            }
        }

        lines = read(dir, "waitlist.txt");
        for (int i = 0; i < lines.size(); i++) {
            if (skip(lines.get(i))) continue;
            try {
                String[] f = fields(lines.get(i), 8);
                s.waitlist.add(new WaitlistEntry(f[0], f[1], f[2], LocalDate.parse(f[3]), Integer.parseInt(f[4]),
                        Integer.parseInt(f[5]), time(f[6]), dec(f[7])));
            } catch (RuntimeException e) {
                problems.add(lineProblem("waitlist.txt", i));
            }
        }

        lines = read(dir, "denied.txt");
        for (int i = 0; i < lines.size(); i++) {
            if (skip(lines.get(i))) continue;
            try {
                String[] f = fields(lines.get(i), 10);
                s.denied.add(new DeniedRequest(f[0], time(f[1]), f[2], f[3], LocalDate.parse(f[4]),
                        Integer.parseInt(f[5]), Integer.parseInt(f[6]), DenyReason.valueOf(f[7]), dec(f[8]), dec(f[9])));
            } catch (RuntimeException e) {
                problems.add(lineProblem("denied.txt", i));
            }
        }

        lines = read(dir, "history.txt");
        for (int i = 0; i < lines.size(); i++) {
            if (skip(lines.get(i))) continue;
            try {
                String[] f = fields(lines.get(i), 6);
                s.history.add(new HistoryEntry(time(f[0]), f[1], dec(f[2]), dec(f[3]), dec(f[4]), dec(f[5])));
            } catch (RuntimeException e) {
                problems.add(lineProblem("history.txt", i));
            }
        }

        // New IDs must never repeat old ones, even if settings.txt was edited by hand.
        for (Booking b : s.bookings) s.nextBookingNo = Math.max(s.nextBookingNo, number(b.id) + 1);
        for (WaitlistEntry w : s.waitlist) s.nextWaitNo = Math.max(s.nextWaitNo, number(w.id) + 1);
        for (DeniedRequest d : s.denied) s.nextDeniedNo = Math.max(s.nextDeniedNo, number(d.id) + 1);
        for (HistoryEntry h : s.history) {
            if (h.ref.startsWith("W")) s.nextWaitNo = Math.max(s.nextWaitNo, number(h.ref) + 1);
        }
        return problems;
    }

    // ===================================================================
    // Small helpers
    // ===================================================================

    private static List<String> header(String comment) {
        List<String> lines = new ArrayList<String>();
        lines.add(comment);
        return lines;
    }

    private static String row(String... values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(enc(values[i]));
        }
        return sb.toString();
    }

    /** Empty values are written as "-"; the separator character can never appear inside a value. */
    private static String enc(String v) {
        if (v == null || v.isEmpty()) return "-";
        return v.replace('|', '/').replace('\n', ' ').replace('\r', ' ');
    }

    private static String dec(String v) {
        return "-".equals(v) ? "" : v;
    }

    private static String str(LocalDateTime t) {
        return t == null ? "-" : t.toString();
    }

    private static LocalDateTime time(String v) {
        return "-".equals(v) ? null : LocalDateTime.parse(v);
    }

    private static boolean skip(String line) {
        return line.trim().isEmpty() || line.startsWith("#");
    }

    private static String[] fields(String line, int expected) {
        String[] f = line.split("\\|", -1);
        if (f.length < expected) {
            throw new IllegalArgumentException("too few fields");
        }
        return f;
    }

    private static int number(String id) {
        try {
            return Integer.parseInt(id.substring(1));
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static String lineProblem(String file, int index) {
        return file + " line " + (index + 1) + " could not be read and was skipped.";
    }

    private static List<String> read(Path dir, String name) throws IOException {
        Path file = dir.resolve(name);
        if (!Files.exists(file)) {
            return new ArrayList<String>();
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8);
    }

    private static void write(Path file, List<String> lines) throws IOException {
        Path tmp = file.resolveSibling(file.getFileName().toString() + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

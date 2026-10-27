import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.function.Consumer;

/** Saves everything to plain text files, one record per line with fields separated by "|", and reads them back. */
final class Storage {

    private Storage() { }

    static boolean hasSavedData(Path dir) {
        return Files.exists(dir.resolve("teams.txt"));
    }

    static void save(Scheduler s, Path dir) throws IOException {
        Files.createDirectories(dir);
        List<String> lines = new ArrayList<>();
        for (Map.Entry<String, String> e : s.equipment.entrySet()) {
            lines.add(e.getKey() + "|" + e.getValue());
        }
        write(dir, "equipment.txt", lines);
        write(dir, "teams.txt", s.teams); // already in priority order
        lines = new ArrayList<>();
        for (Booking b : s.bookings) {
            lines.add(join(b.id, b.equipment, b.team, b.date, b.start, b.end, b.cancelled));
        }
        write(dir, "bookings.txt", lines);
        lines = new ArrayList<>();
        for (Request r : s.requests) {
            lines.add(join(r.id, r.equipment, r.team, r.date, r.start, r.end, r.reason, r.status));
        }
        write(dir, "requests.txt", lines);
        write(dir, "history.txt", s.history);
    }

    /**
     * Reads everything into a brand-new scheduler. If any line cannot be read, it stops with an error naming the
     * file and line, and the program refuses to start, so it never saves half-loaded data over good files.
     */
    static Scheduler load(Path dir) throws IOException {
        Scheduler s = new Scheduler();
        read(dir, "equipment.txt", 2, f -> s.equipment.put(f[0], f[1]));
        read(dir, "teams.txt", 1, f -> s.teams.add(f[0]));
        read(dir, "bookings.txt", 7, f -> s.bookings.add(new Booking(f[0], f[1], f[2], LocalDate.parse(f[3]),
                Integer.parseInt(f[4]), Integer.parseInt(f[5]), Boolean.parseBoolean(f[6]))));
        read(dir, "requests.txt", 8, f -> s.requests.add(new Request(f[0], f[1], f[2], LocalDate.parse(f[3]),
                Integer.parseInt(f[4]), Integer.parseInt(f[5]), f[6], f[7])));
        s.history.addAll(Files.readAllLines(dir.resolve("history.txt"), StandardCharsets.UTF_8));
        return s;
    }

    /** Splits each line of a file into its fields and hands them to 'use'. A damaged line stops the whole load. */
    private static void read(Path dir, String file, int fields, Consumer<String[]> use) throws IOException {
        List<String> lines = Files.readAllLines(dir.resolve(file), StandardCharsets.UTF_8);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().isEmpty()) continue;
            String[] f = lines.get(i).split("\\|", -1);
            try {
                if (f.length != fields) throw new IllegalArgumentException("wrong number of fields");
                use.accept(f);
            } catch (RuntimeException e) {
                throw new IOException(file + " line " + (i + 1) + " is damaged (" + e.getMessage() + ")");
            }
        }
    }

    /** Writes a temporary copy first, then swaps it in, so a crash in the middle of saving never leaves half a file. */
    private static void write(Path dir, String file, List<String> lines) throws IOException {
        Path tmp = dir.resolve(file + ".tmp");
        Files.write(tmp, lines, StandardCharsets.UTF_8);
        Files.move(tmp, dir.resolve(file), StandardCopyOption.REPLACE_EXISTING);
    }

    private static String join(Object... values) {
        List<String> parts = new ArrayList<>();
        for (Object v : values) parts.add(String.valueOf(v));
        return String.join("|", parts);
    }
}

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Small helpers for dates, times and text layout, used across the program. */
final class TimeUtil {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE dd MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter SHORT_DAY = DateTimeFormatter.ofPattern("EEE dd MMM", Locale.ENGLISH);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("EEE dd MMM yyyy, HH:mm", Locale.ENGLISH);

    private TimeUtil() { }

    /** Set only while the sample data is being built, so its requests are checked "as of" their own time. */
    private static LocalDateTime simulated;

    static void simulate(LocalDateTime t) {
        simulated = t;
    }

    /**
     * The current time, to the minute. For repeatable testing it can be fixed by
     * starting Java with -Dscheduler.now=2026-10-27T09:00
     */
    static LocalDateTime now() {
        if (simulated != null) {
            return simulated;
        }
        String fixed = System.getProperty("scheduler.now");
        if (fixed != null && fixed.trim().length() > 0) {
            return LocalDateTime.parse(fixed.trim());
        }
        return LocalDateTime.now().withSecond(0).withNano(0);
    }

    static LocalDate today() {
        return now().toLocalDate();
    }

    /** e.g. "Wed 28 Oct 2026" */
    static String day(LocalDate d) {
        return DAY.format(d);
    }

    /** e.g. "Wed 28 Oct" */
    static String shortDay(LocalDate d) {
        return SHORT_DAY.format(d);
    }

    /** e.g. "27 Oct 09:05" */
    static String stamp(LocalDateTime t) {
        return t == null ? "-" : STAMP.format(t);
    }

    /** e.g. "Tue 27 Oct 2026, 09:05" */
    static String clock(LocalDateTime t) {
        return CLOCK.format(t);
    }

    /** 9 becomes "09:00" */
    static String hour(int h) {
        return (h < 10 ? "0" : "") + h + ":00";
    }

    /** 10, 12 becomes "10:00-12:00" */
    static String slot(int start, int end) {
        return hour(start) + "-" + hour(end);
    }

    /** 1 becomes "1 hour", 3 becomes "3 hours" */
    static String hours(int n) {
        return n == 1 ? "1 hour" : n + " hours";
    }

    /** Accepts YYYY-MM-DD, DD-MM-YYYY, DD/MM/YYYY, today and tomorrow. Returns null if not a real date. */
    static LocalDate parseDate(String v) {
        if (v == null) return null;
        String t = v.trim().toLowerCase(Locale.ROOT);
        LocalDate today = today();
        if (t.equals("today")) return today;
        if (t.equals("tomorrow")) return today.plusDays(1);
        try {
            if (t.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
                String[] p = t.split("-");
                return LocalDate.of(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]));
            }
            if (t.matches("\\d{1,2}[-/.]\\d{1,2}[-/.]\\d{4}")) {
                String[] p = t.split("[-/.]");
                return LocalDate.of(Integer.parseInt(p[2]), Integer.parseInt(p[1]), Integer.parseInt(p[0]));
            }
        } catch (java.time.DateTimeException e) {
            return null;
        }
        return null;
    }

    /** Accepts "10" or "10:00". Returns null for anything else. */
    static Integer parseHour(String v) {
        if (v == null) return null;
        String t = v.trim();
        if (t.matches("\\d{1,2}")) return Integer.parseInt(t);
        if (t.matches("\\d{1,2}:00")) return Integer.parseInt(t.substring(0, t.indexOf(':')));
        return null;
    }

    static String repeat(char c, int n) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sb.append(c);
        }
        return sb.toString();
    }

    static String padRight(String s, int width) {
        return s.length() >= width ? s : s + repeat(' ', width - s.length());
    }

    static String padLeft(String s, int width) {
        return s.length() >= width ? s : repeat(' ', width - s.length()) + s;
    }

    /**
     * Breaks text into lines no wider than width. The first line starts with
     * firstIndent and the rest with restIndent.
     */
    static List<String> wrap(String text, int width, String firstIndent, String restIndent) {
        List<String> lines = new ArrayList<String>();
        String indent = firstIndent;
        StringBuilder line = new StringBuilder(indent);
        for (String word : text.split(" ")) {
            if (word.isEmpty()) {
                continue;
            }
            boolean lineHasWords = line.length() > indent.length();
            if (lineHasWords && line.length() + 1 + word.length() > width) {
                lines.add(line.toString());
                indent = restIndent;
                line = new StringBuilder(indent);
                lineHasWords = false;
            }
            if (lineHasWords) {
                line.append(' ');
            }
            line.append(word);
        }
        if (line.length() > indent.length()) {
            lines.add(line.toString());
        }
        return lines;
    }
}

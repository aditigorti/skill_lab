import java.io.PrintStream;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The two reports: how much the equipment is used, and which requests were turned down.
 * Each report is worked out once (usage, denied) and then either printed for the terminal
 * or used elsewhere, so the numbers are worked out in one place.
 */
final class Reports {

    private static final int WIDTH = 78;

    private Reports() { }

    // ===================================================================
    // Utilization: the numbers
    // ===================================================================

    static final class ItemUse {
        String id;
        String type;
        String name;
        int hours;
        int percent;
    }

    static final class GroupUse {
        String name;
        int hours;
        int share;
        int bookings;
        int cancelled;
        int denied;
    }

    static final class Usage {
        LocalDate from;
        LocalDate to;
        int days;
        int openHour;
        int closeHour;
        int openPerItem;
        final List<ItemUse> items = new ArrayList<ItemUse>();
        int totalHours;
        int totalPercent;
        String mostUsed;
        int mostPercent;
        String leastUsed;
        int leastPercent;
        final List<GroupUse> groups = new ArrayList<GroupUse>();
        String biggestGroup;
        int biggestShare;
        String smallestGroup;
        int smallestShare;
        int evenShare;
        /** For each hour of the day, the average share of equipment booked (percent). Outside lab hours: 0. */
        final int[] hourPercent = new int[24];
        int peakHour = -1;
    }

    static Usage usage(Scheduler s, LocalDate from, LocalDate to) {
        Policy p = s.policy;
        Usage u = new Usage();
        u.from = from;
        u.to = to;
        u.days = (int) ChronoUnit.DAYS.between(from, to) + 1;
        u.openHour = p.openHour;
        u.closeHour = p.closeHour;
        u.openPerItem = u.days * p.hoursPerDay();

        Map<String, ItemUse> items = new LinkedHashMap<String, ItemUse>();
        for (Equipment e : s.equipment.values()) {
            ItemUse iu = new ItemUse();
            iu.id = e.id;
            iu.type = e.type;
            iu.name = e.name;
            items.put(e.id, iu);
        }
        Map<String, GroupUse> groups = new LinkedHashMap<String, GroupUse>();
        for (String g : s.groups) {
            groups.put(g, newGroup(g));
        }
        int[] inUse = new int[24];

        for (Booking b : s.bookings) {
            if (b.date.isBefore(from) || b.date.isAfter(to)) continue;
            GroupUse g = groups.get(b.group);
            if (g == null) {
                g = newGroup(b.group);
                groups.put(b.group, g);
            }
            if (!b.isActive()) {
                g.cancelled++;
                continue;
            }
            ItemUse iu = items.get(b.equipmentId);
            if (iu == null) continue;
            int first = Math.max(b.start, p.openHour);
            int last = Math.min(b.end, p.closeHour);
            int hours = Math.max(0, last - first); // only hours inside lab hours count
            iu.hours += hours;
            g.hours += hours;
            g.bookings++;
            u.totalHours += hours;
            for (int h = first; h < last; h++) inUse[h]++;
        }
        for (DeniedRequest d : s.denied) {
            if (d.date.isBefore(from) || d.date.isAfter(to)) continue;
            GroupUse g = groups.get(d.group);
            if (g == null) {
                g = newGroup(d.group);
                groups.put(d.group, g);
            }
            g.denied++;
        }

        u.mostPercent = -1;
        u.leastPercent = 101;
        for (ItemUse iu : items.values()) {
            iu.percent = percent(iu.hours, u.openPerItem);
            u.items.add(iu);
            if (iu.percent > u.mostPercent) {
                u.mostPercent = iu.percent;
                u.mostUsed = iu.id;
            }
            if (iu.percent < u.leastPercent) {
                u.leastPercent = iu.percent;
                u.leastUsed = iu.id;
            }
        }
        u.totalPercent = percent(u.totalHours, u.openPerItem * items.size());

        u.biggestShare = -1;
        u.smallestShare = 101;
        for (GroupUse g : groups.values()) {
            g.share = percent(g.hours, u.totalHours);
            u.groups.add(g);
            if (g.share > u.biggestShare) {
                u.biggestShare = g.share;
                u.biggestGroup = g.name;
            }
            if (g.share < u.smallestShare) {
                u.smallestShare = g.share;
                u.smallestGroup = g.name;
            }
        }
        u.evenShare = percent(1, Math.max(1, groups.size()));

        int capacityPerHour = u.days * items.size();
        for (int h = p.openHour; h < p.closeHour; h++) {
            u.hourPercent[h] = percent(inUse[h], capacityPerHour);
            if (inUse[h] > 0 && (u.peakHour < 0 || inUse[h] > inUse[u.peakHour])) {
                u.peakHour = h;
            }
        }
        return u;
    }

    private static GroupUse newGroup(String name) {
        GroupUse g = new GroupUse();
        g.name = name;
        return g;
    }

    // ===================================================================
    // Utilization: printed for the terminal
    // ===================================================================

    static void utilization(Scheduler s, PrintStream out, LocalDate from, LocalDate to) {
        Usage u = usage(s, from, to);
        out.println("Utilization from " + TimeUtil.day(from) + " to " + TimeUtil.day(to)
                + " (" + (u.days == 1 ? "1 day" : u.days + " days") + ")");
        print(out, "The lab is open " + TimeUtil.slot(u.openHour, u.closeHour)
                + ", so each item could be booked for up to " + u.openPerItem + " hours in this period.", "", "");
        if (u.items.isEmpty()) {
            out.println("There is no equipment yet.");
            return;
        }

        out.println();
        out.println("By equipment");
        out.println("  " + TimeUtil.padRight("ID", 10) + TimeUtil.padRight("Type", 13)
                + TimeUtil.padLeft("Booked", 7) + TimeUtil.padLeft("Use", 7));
        for (ItemUse iu : u.items) {
            out.println("  " + TimeUtil.padRight(iu.id, 10) + TimeUtil.padRight(cut(iu.type, 12), 13)
                    + TimeUtil.padLeft(iu.hours + " h", 7) + TimeUtil.padLeft(iu.percent + "%", 7) + "  " + bar(iu.percent));
        }
        out.println("  " + TimeUtil.padRight("All equipment", 23) + TimeUtil.padLeft(u.totalHours + " h", 7)
                + TimeUtil.padLeft(u.totalPercent + "%", 7));
        if (u.items.size() > 1 && u.totalHours > 0) {
            out.println("  Most used: " + u.mostUsed + " (" + u.mostPercent + "%).  Least used: "
                    + u.leastUsed + " (" + u.leastPercent + "%).");
        }

        out.println();
        out.println("By group");
        out.println("  " + TimeUtil.padRight("Group", 10) + TimeUtil.padLeft("Hours", 6) + TimeUtil.padLeft("Share", 7)
                + TimeUtil.padLeft("Bookings", 10) + TimeUtil.padLeft("Cancelled", 11) + TimeUtil.padLeft("Denied", 8));
        for (GroupUse g : u.groups) {
            out.println("  " + TimeUtil.padRight(g.name, 10) + TimeUtil.padLeft(String.valueOf(g.hours), 6)
                    + TimeUtil.padLeft(g.share + "%", 7) + TimeUtil.padLeft(String.valueOf(g.bookings), 10)
                    + TimeUtil.padLeft(String.valueOf(g.cancelled), 11) + TimeUtil.padLeft(String.valueOf(g.denied), 8));
        }
        if (u.groups.size() > 1 && u.totalHours > 0) {
            print(out, "Fairness check: the largest share is " + u.biggestGroup + " with " + u.biggestShare
                    + "%, the smallest is " + u.smallestGroup + " with " + u.smallestShare
                    + "%. An even split would be about " + u.evenShare + "% each.", "  ", "  ");
        }

        if (u.peakHour >= 0) {
            out.println();
            print(out, "Busiest hour of the day: " + TimeUtil.slot(u.peakHour, u.peakHour + 1) + ", when "
                    + u.hourPercent[u.peakHour] + "% of all equipment was booked on average.", "", "");
        }
    }

    // ===================================================================
    // Denied requests: the numbers
    // ===================================================================

    static final class DeniedSummary {
        int total;
        final Map<DenyReason, Integer> byReason = new EnumMap<DenyReason, Integer>(DenyReason.class);
        final Map<String, Integer> byGroup = new LinkedHashMap<String, Integer>();
        int waiting;
        int granted;
        int expired;
        int withdrawn;

        int joinedWaitingList() {
            return waiting + granted + expired + withdrawn;
        }
    }

    static DeniedSummary deniedSummary(Scheduler s) {
        DeniedSummary sum = new DeniedSummary();
        for (String g : s.groups) {
            sum.byGroup.put(g, 0);
        }
        for (DeniedRequest d : s.denied) {
            sum.total++;
            Integer r = sum.byReason.get(d.reason);
            sum.byReason.put(d.reason, r == null ? 1 : r + 1);
            Integer g = sum.byGroup.get(d.group);
            sum.byGroup.put(d.group, g == null ? 1 : g + 1);
            String a = d.afterwards == null ? "" : d.afterwards;
            if (a.equals(DeniedRequest.WAITING)) sum.waiting++;
            else if (a.startsWith(DeniedRequest.GRANTED_PREFIX)) sum.granted++;
            else if (a.equals(DeniedRequest.EXPIRED)) sum.expired++;
            else if (a.equals(DeniedRequest.WITHDRAWN)) sum.withdrawn++;
        }
        return sum;
    }

    // ===================================================================
    // Denied requests: printed for the terminal
    // ===================================================================

    static void denied(Scheduler s, PrintStream out, int limit) {
        DeniedSummary sum = deniedSummary(s);
        int n = sum.total;
        if (n == 0) {
            out.println("No requests have been denied so far.");
            return;
        }
        out.println((n == 1 ? "1 request has" : n + " requests have") + " been denied so far.");

        out.println();
        out.println("Why they were denied");
        for (Map.Entry<DenyReason, Integer> e : sum.byReason.entrySet()) {
            out.println("  " + TimeUtil.padRight(e.getKey().label, 36) + TimeUtil.padLeft(String.valueOf(e.getValue()), 4));
        }
        out.println();
        out.println("Who was denied");
        for (Map.Entry<String, Integer> e : sum.byGroup.entrySet()) {
            out.println("  " + TimeUtil.padRight(e.getKey(), 36) + TimeUtil.padLeft(String.valueOf(e.getValue()), 4));
        }
        if (sum.joinedWaitingList() > 0) {
            out.println();
            print(out, sum.joinedWaitingList() + " of these joined the waiting list: " + sum.granted
                    + " got their slot later, " + sum.waiting + " still waiting, " + sum.expired + " never got it, "
                    + sum.withdrawn + " left the list.", "", "");
        }

        out.println();
        out.println("Most recent first");
        int shown = 0;
        for (int i = n - 1; i >= 0 && shown < limit; i--, shown++) {
            DeniedRequest d = s.denied.get(i);
            out.println("  " + d.id + "  " + d.group + " asked for " + d.equipmentId + ", "
                    + TimeUtil.shortDay(d.date) + " " + TimeUtil.slot(d.start, d.end)
                    + "   (asked " + TimeUtil.stamp(d.requestedAt) + ")");
            print(out, "Why: " + d.explanation, "        ", "             ");
            String after = d.afterwardsText();
            if (!after.isEmpty()) {
                print(out, "Then: " + after, "        ", "              ");
            }
        }
        if (n > shown) {
            out.println("  (" + (n - shown) + " older denied requests are not shown.)");
        }
    }

    // ===================================================================
    // Helpers
    // ===================================================================

    static int percent(int part, int whole) {
        return whole <= 0 ? 0 : (int) Math.round(100.0 * part / whole);
    }

    private static String bar(int pct) {
        int filled = Math.min(20, Math.round(pct / 5.0f));
        return TimeUtil.repeat('#', filled) + TimeUtil.repeat('.', 20 - filled);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static void print(PrintStream out, String text, String firstIndent, String restIndent) {
        for (String line : TimeUtil.wrap(text, WIDTH, firstIndent, restIndent)) {
            out.println(line);
        }
    }
}

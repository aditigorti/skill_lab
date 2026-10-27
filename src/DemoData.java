import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;

/**
 * Builds a realistic sample data set for a college lab: 12 items, 6 project teams,
 * two weeks of past bookings and bookings for the days ahead.
 *
 * Nothing is typed in by hand. The data is made by replaying about 150 requests,
 * each checked by the real rules as of the moment it was made (requests are made one
 * to four days ahead, like real students do). So every refusal, waiting-list
 * handover, cancellation and expired wait in the data really happened under the
 * rules. Dates are always around the day it is loaded, so it never goes stale.
 *
 * The same pattern comes out every time (the random numbers use a fixed seed).
 */
final class DemoData {

    private static final String[][] EQUIPMENT = {
        { "CAM-01", "Camera", "Sony ZV-E10 mirrorless camera" },
        { "CAM-02", "Camera", "Canon EOS M50 camera" },
        { "CAM-03", "Camera", "GoPro Hero 12 action camera" },
        { "BRD-01", "Dev board", "Raspberry Pi 5 starter kit" },
        { "BRD-02", "Dev board", "Raspberry Pi 4 kit" },
        { "BRD-03", "Dev board", "ESP32 development kit" },
        { "BRD-04", "Dev board", "Arduino Mega kit" },
        { "SNS-01", "Sensor kit", "IoT sensor kit (temperature, ultrasonic, motion)" },
        { "SNS-02", "Sensor kit", "Environment sensor kit (gas, humidity, light)" },
        { "OSC-01", "Oscilloscope", "100 MHz digital oscilloscope" },
        { "OSC-02", "Oscilloscope", "50 MHz digital oscilloscope" },
        { "DRN-01", "Drone", "DJI Tello EDU drone" },
    };

    /** Project teams, in their starting priority-line order. */
    private static final String[] GROUPS = { "SmartFarm", "GestureBot", "AirWatch", "TrafficAI", "HealthBand", "DroneMap" };

    /** The items each team needs most, most-wanted first. Shared favourites create real clashes. */
    private static final String[][] NEEDS = {
        { "SNS-01", "SNS-02", "BRD-03", "BRD-01" },   // SmartFarm: soil and weather sensing
        { "CAM-01", "BRD-01", "BRD-04", "CAM-02" },   // GestureBot: hand-gesture robot
        { "SNS-02", "BRD-03", "OSC-01", "DRN-01" },   // AirWatch: air-quality monitor
        { "CAM-01", "CAM-02", "BRD-01", "BRD-02" },   // TrafficAI: traffic camera analytics
        { "BRD-03", "OSC-01", "OSC-02", "SNS-01" },   // HealthBand: wearable health band
        { "DRN-01", "CAM-03", "BRD-02", "CAM-01" },   // DroneMap: drone mapping
    };

    /** Average requests per weekday for each team. TrafficAI is the busiest, HealthBand the quietest. */
    private static final double[] BUSY = { 2.2, 2.4, 2.0, 3.1, 1.3, 2.6 };

    private static final int[] START_HOURS = { 8, 9, 9, 10, 10, 10, 11, 11, 12, 13, 14, 14, 15, 15, 16, 17 };

    /** Something that happens at a given time. */
    private static final class Event implements Comparable<Event> {
        final LocalDateTime at;
        final int order;
        final Runnable action;

        Event(LocalDateTime at, int order, Runnable action) {
            this.at = at;
            this.order = order;
            this.action = action;
        }

        public int compareTo(Event o) {
            int c = at.compareTo(o.at);
            return c != 0 ? c : Integer.compare(order, o.order);
        }
    }

    private DemoData() { }

    static void load(final Scheduler s) {
        s.clearAll();
        final LocalDateTime realNow = TimeUtil.now();
        final LocalDate today = realNow.toLocalDate();
        for (String[] e : EQUIPMENT) {
            s.equipment.put(e[0], new Equipment(e[0], e[1], e[2]));
        }
        Collections.addAll(s.groups, GROUPS);
        s.priorityQueue.addAll(s.groups); // everyone starts in the order listed

        // Random cancellations stop at 18:00 yesterday, so the priority line is settled
        // before the waiting-list example below is set up.
        final LocalDateTime cancelCutoff = today.minusDays(1).atTime(18, 0);
        final PriorityQueue<Event> queue = new PriorityQueue<Event>();
        final int[] seq = { 0 };
        Random rnd = new Random(20261027L);

        for (int offset = -14; offset <= 6; offset++) {
            final LocalDate day = today.plusDays(offset);
            double dayFactor = day.getDayOfWeek() == DayOfWeek.SUNDAY ? 0
                    : day.getDayOfWeek() == DayOfWeek.SATURDAY ? 0.4 : 1.0;
            for (int g = 0; g < GROUPS.length; g++) {
                int count = (int) Math.floor(BUSY[g] * dayFactor + rnd.nextDouble());
                for (int i = 0; i < count; i++) {
                    // Draw every random number first, so the pattern never shifts.
                    final String item = pickItem(rnd, NEEDS[g]);
                    final int hours = pickHours(rnd);
                    final int start = pickStart(rnd, hours);
                    final LocalDateTime made = madeAt(rnd, day, start);
                    final boolean waits = rnd.nextDouble() < 0.6;
                    final boolean cancelsLater = rnd.nextDouble() < 0.16;
                    final double cancelPoint = rnd.nextDouble();
                    final boolean checksOwnFirst = rnd.nextDouble() < 0.8;
                    final boolean holderGivesUp = rnd.nextDouble() < 0.45;
                    final double giveUpPoint = rnd.nextDouble();
                    final String group = GROUPS[g];
                    if (made.isAfter(realNow) || !made.isBefore(day.atTime(Math.min(start, 23), 0))) {
                        continue; // not asked yet, or asked too late
                    }
                    queue.add(new Event(made, seq[0]++, () -> {
                        if (checksOwnFirst && holdsAlready(s, item, group, day, start, start + hours)) {
                            return; // most teams notice they already have it and don't ask
                        }
                        Scheduler.Outcome o = s.request(item, group, day, start, start + hours);
                        if (o.booking != null) {
                            scheduleCancel(s, queue, seq, o.booking, cancelsLater, cancelPoint, cancelCutoff);
                        } else if (waits && o.check.canWaitlist()) {
                            s.joinWaitlist(o.denial, TimeUtil.now().plusMinutes(1));
                            // Contested slots are the ones that get given up: sometimes the holder cancels later.
                            Booking holder = clashingBooking(s, item, group, day, start, start + hours);
                            if (holder != null) {
                                scheduleCancel(s, queue, seq, holder, holderGivesUp, giveUpPoint, cancelCutoff,
                                        TimeUtil.now().plusHours(2));
                            }
                        }
                    }));
                }
            }
        }

        // A worked example of rotating priority: yesterday evening two teams ask for the
        // same booked slot in the coming days. The one further back in the line asks first,
        // the one further forward asks second. If the holder cancels, the second one gets it.
        final Object[] example = new Object[2];
        queue.add(new Event(today.minusDays(1).atTime(18, 20), seq[0]++, () -> setUpExample(s, today, example, true)));
        queue.add(new Event(today.minusDays(1).atTime(19, 30), seq[0]++, () -> setUpExample(s, today, example, false)));

        try {
            while (!queue.isEmpty()) {
                Event e = queue.poll();
                TimeUtil.simulate(e.at);
                s.expireWaitlist();
                e.action.run();
            }
        } finally {
            TimeUtil.simulate(null);
        }
        s.expireWaitlist();
        s.log(TimeUtil.now(), HistoryEntry.RESET, "", "", "", "Sample data loaded");
    }

    // ===================================================================
    // Pieces of the simulation
    // ===================================================================

    private static void scheduleCancel(final Scheduler s, PriorityQueue<Event> queue, int[] seq, final Booking b,
                                       boolean cancelsLater, double cancelPoint, LocalDateTime cutoff) {
        scheduleCancel(s, queue, seq, b, cancelsLater, cancelPoint, cutoff, b.createdAt.plusHours(1));
    }

    /** Cancels b at a point between "earliest" and an hour before it starts, unless that is past the cutoff. */
    private static void scheduleCancel(final Scheduler s, PriorityQueue<Event> queue, int[] seq, final Booking b,
                                       boolean cancelsLater, double cancelPoint, LocalDateTime cutoff,
                                       LocalDateTime earliest) {
        if (!cancelsLater) return;
        long minutes = Duration.between(earliest, b.startTime().minusHours(1)).toMinutes();
        if (minutes < 60) return;
        LocalDateTime at = earliest.plusMinutes((long) (minutes * cancelPoint));
        if (at.isAfter(cutoff)) return;
        queue.add(new Event(at, seq[0]++, () -> {
            if (b.isActive() && b.startTime().isAfter(TimeUtil.now())) {
                s.cancel(b);
            }
        }));
    }

    /**
     * First call: finds a booked slot of 2+ hours in the next few days that at least two
     * other teams could take (only the clash stops them), and the team furthest back in
     * the priority line asks for it and joins the waiting list. Second call: the team
     * furthest forward asks for the same slot and joins too.
     */
    private static void setUpExample(Scheduler s, LocalDate today, Object[] example, boolean first) {
        if (first) {
            for (int d = 1; d <= 5 && example[0] == null; d++) {
                LocalDate day = today.plusDays(d);
                for (Equipment e : s.equipment.values()) {
                    for (Booking b : s.activeBookings(e.id, day)) {
                        if (b.hours() < 2 || !Booking.DIRECT.equals(b.source)) continue;
                        List<String> able = new ArrayList<String>();
                        for (String g : s.groups) {
                            if (g.equals(b.group) || waiting(s, g, b)) continue;
                            if (s.check(b.equipmentId, g, b.date, b.start, b.end).canWaitlist()) able.add(g);
                        }
                        if (able.size() < 2) continue;
                        able.sort((x, y) -> Integer.compare(s.placeInLine(x), s.placeInLine(y)));
                        example[0] = b;
                        example[1] = able.get(0); // furthest forward: asks second
                        ask(s, b, able.get(able.size() - 1));
                        break;
                    }
                    if (example[0] != null) break;
                }
            }
        } else if (example[0] != null) {
            ask(s, (Booking) example[0], (String) example[1]);
        }
    }

    private static void ask(Scheduler s, Booking held, String group) {
        Scheduler.Outcome o = s.request(held.equipmentId, group, held.date, held.start, held.end);
        if (o.denial != null && o.check.canWaitlist()) {
            s.joinWaitlist(o.denial, TimeUtil.now().plusMinutes(1));
        }
    }

    private static Booking clashingBooking(Scheduler s, String item, String group, LocalDate day, int start, int end) {
        for (Booking b : s.activeBookings(item, day)) {
            if (!b.group.equals(group) && b.overlaps(start, end)) return b;
        }
        return null;
    }

    private static boolean holdsAlready(Scheduler s, String item, String group, LocalDate day, int start, int end) {
        for (Booking b : s.activeBookings(item, day)) {
            if (b.group.equals(group) && b.overlaps(start, end)) return true;
        }
        return false;
    }

    private static boolean waiting(Scheduler s, String group, Booking b) {
        for (WaitlistEntry w : s.waitlist) {
            if (w.group.equals(group) && w.equipmentId.equals(b.equipmentId) && w.date.equals(b.date)) return true;
        }
        return false;
    }

    private static String pickItem(Random rnd, String[] needs) {
        double r = rnd.nextDouble();
        if (r < 0.50) return needs[0];
        if (r < 0.75) return needs[1];
        if (r < 0.90) return needs[2];
        return needs[3];
    }

    /** Mostly 1-3 hours; now and then 4, which the back-to-back limit refuses. */
    private static int pickHours(Random rnd) {
        double r = rnd.nextDouble();
        if (r < 0.25) return 1;
        if (r < 0.70) return 2;
        if (r < 0.94) return 3;
        return 4;
    }

    /** Mostly in the busy middle of the day; now and then too early or too late for lab hours. */
    private static int pickStart(Random rnd, int hours) {
        double r = rnd.nextDouble();
        int pick = START_HOURS[rnd.nextInt(START_HOURS.length)];
        if (r < 0.012) return 7;
        if (r < 0.025) return 19;
        return Math.min(pick, Policy.DEFAULT_CLOSE - hours);
    }

    /** Requests are made one to five days ahead (sometimes the same morning), at a waking hour. */
    private static LocalDateTime madeAt(Random rnd, LocalDate day, int start) {
        double r = rnd.nextDouble();
        int minute = rnd.nextInt(60);
        int hour = 8 + rnd.nextInt(15); // 08:00 to 22:59
        if (r < 0.08) {
            return day.atTime(7, minute); // the same morning, before the lab opens
        }
        int daysAhead = r < 0.38 ? 1 : r < 0.63 ? 2 : r < 0.80 ? 3 : r < 0.92 ? 4 : 5;
        return day.minusDays(daysAhead).atTime(hour, minute);
    }
}

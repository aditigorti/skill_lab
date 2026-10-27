import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collections;

/** Six items, six teams and a few days of activity around today, all made through the real rules. Ready demo: two
 *  teams wait for TrafficAI's CAM-02 slot tomorrow, and the one that asked second is first in the priority line. */
final class SampleData {

    private SampleData() { }

    // Each row: action, days from today, item, team, start hour, end hour.
    // book = ask for a booking; wait = ask, and wait if refused for a clash; cancel = cancel that booking.
    private static final String[] ROWS = {
        "book -3 CAM-01 GestureBot 9 12", "book -3 CAM-02 TrafficAI 10 12", "book -3 BRD-01 AirWatch 13 16",
        "book -3 SNS-01 SmartFarm 8 11", "book -3 CAM-01 HealthBand 14 16", "book -2 CAM-01 GestureBot 9 12",
        "book -2 CAM-01 GestureBot 12 14", "book -2 OSC-01 DroneMap 10 13", "book -2 BRD-02 HealthBand 9 11",
        "book -2 SNS-01 SmartFarm 8 11", "book -2 SNS-01 SmartFarm 12 15", "book -1 CAM-02 TrafficAI 9 12",
        "book -1 CAM-02 AirWatch 11 13", "book -1 BRD-01 GestureBot 14 17", "book -1 OSC-01 DroneMap 13 15",
        "book -1 CAM-01 HealthBand 8 10", "book -1 BRD-02 SmartFarm 16 19", "book -1 SNS-01 AirWatch 7 9",
        "book 0 CAM-01 TrafficAI 10 12", "book 0 BRD-01 GestureBot 9 12", "book 0 OSC-01 AirWatch 15 17",
        "book 1 CAM-01 TrafficAI 8 10", "book 1 CAM-02 TrafficAI 10 13", "wait 1 CAM-02 HealthBand 10 13",
        "wait 1 CAM-02 SmartFarm 10 13", "book 1 BRD-02 HealthBand 9 12", "book 1 OSC-01 GestureBot 15 16",
        "book 2 BRD-01 AirWatch 14 16", "wait 2 BRD-01 DroneMap 14 16", "cancel 2 BRD-01 AirWatch 14 16",
        "book 2 CAM-01 SmartFarm 9 11", "book 2 SNS-01 GestureBot 13 15",
    };

    static Scheduler create() {
        Scheduler s = new Scheduler();
        String[][] items = {{"CAM-01", "Camera"}, {"CAM-02", "Camera"}, {"BRD-01", "Dev board"},
            {"BRD-02", "Dev board"}, {"SNS-01", "Sensor kit"}, {"OSC-01", "Oscilloscope"}};
        for (String[] item : items) s.equipment.put(item[0], item[1]);
        Collections.addAll(s.teams, "SmartFarm", "GestureBot", "AirWatch", "TrafficAI", "HealthBand", "DroneMap");

        // Replay the rows as if they were typed in 4 days ago, ten minutes apart, so the rules treat them as future.
        LocalDate today = Scheduler.now().toLocalDate();
        LocalDateTime clock = today.minusDays(4).atTime(8, 0);
        String realClock = System.getProperty("scheduler.now");
        try {
            for (String row : ROWS) {
                System.setProperty("scheduler.now", clock.toString());
                clock = clock.plusMinutes(10);
                String[] f = row.split(" ");
                String item = f[2], team = f[3];
                LocalDate day = today.plusDays(Integer.parseInt(f[1]));
                int start = Integer.parseInt(f[4]), end = Integer.parseInt(f[5]);
                if (f[0].equals("cancel")) {
                    for (Booking b : s.activeBookings(item, day)) {
                        if (b.team.equals(team) && b.start == start) s.cancel(b);
                    }
                } else {
                    Scheduler.Outcome o = s.request(item, team, day, start, end);
                    if (f[0].equals("wait") && o.check.canWait()) s.joinWaitingList(o.refused);
                }
            }
        } finally {
            if (realClock == null) System.clearProperty("scheduler.now");
            else System.setProperty("scheduler.now", realClock);
        }
        return s;
    }
}

# Shared Equipment Fair-Use Scheduler

A command-line program for booking shared lab equipment — cameras, development
boards, sensor kits, oscilloscopes — fairly between groups. You type answers into
the terminal; there is no graphical screen.

It stops two groups booking the same item at the same time, applies a fairness
policy everyone can read, suggests other times or other items when a request is
turned down, runs a waiting list with rotating priority, reports how the equipment
is used, and keeps a permanent history of everything that happened.

---

## How to run it

You need the Java JDK, version 8 or newer. Check with `javac -version`.
Nothing else is needed — no internet, no installs, no database.

| Computer | What to do |
|---|---|
| Windows | Double-click `run.bat`, or type `.\run.bat` in PowerShell inside this folder |
| macOS / Linux | Type `./run.sh` in a terminal inside this folder |
| Any (by hand) | `javac -d out src/*.java` then `java -cp out Main` |

**Unzip first.** On Windows, right-click the zip and choose Extract All. The scripts
do not work from inside the zip.

The first time it starts, it offers to load **sample data**. Answer `y` to see every
feature straight away. Everything is saved automatically after every change into a
folder called `data`, so your bookings are still there next time.

### The sample data

A college lab with 12 items (three cameras, four development boards, two sensor kits,
two oscilloscopes and a drone) shared by six project teams: SmartFarm, GestureBot,
AirWatch, TrafficAI, HealthBand and DroneMap. It holds two weeks of past bookings and
bookings for the coming days, about 140 in all, plus refused requests, cancellations,
waiting-list handovers and waits that never came good.

None of it is typed in by hand. The program replays about 150 requests, each made one
to five days ahead like real students do, and checks every one with the real rules as
of the moment it was made. So every refusal and handover in the data really happened
under the rules. The dates are always around the day you load it, so it is never out
of date at a demo.

It always includes one worked example of rotating priority: two teams are waiting for
the same booked slot in the next few days, and the team that asked later is first in
line. Open menu 8 to see it, cancel the holder's booking in menu 2, and watch the slot
go to the team first in line. To load the sample data again later, use menu 10.

---

## The menu

```
1. Make a reservation           asks for item, group, date, start hour, end hour
2. Cancel a reservation         shows your group's upcoming bookings, then asks which one
3. View bookings                a one-day timetable of every item, or a list of upcoming bookings
4. Suggest free slots           free times for an item, filtered by a group's fairness limits
5. Utilization report           how much each item and each group used, over any dates
6. Denied requests              every refused request, why, and what happened next
7. Booking history              the full log, or the complete story of one booking
8. Waiting list and priority    who is waiting, and who is first in line for the next freed slot
9. Fairness rules               read the rules, or change them
10. Equipment and groups        list, add equipment, add a group, reset to sample data
0. Save and exit
```

At any question, pressing **Enter on an empty answer** takes you back to the menu.
Dates can be typed as `2026-10-28`, `28-10-2026`, `28/10/2026`, `today` or `tomorrow`.
Hours are whole hours: `10` or `10:00`. Wrong answers get a clear message and another
try; the program never crashes on bad input.

---

## The fairness policy

Four rules, all shown in plain English in menu 9 and all changeable there:

1. **Lab hours** (default 08:00–20:00). Every booking must fit inside them.
2. **Back-to-back limit** (default 3 hours). A group cannot hold the same item for longer
   than this in one unbroken stretch. Bookings that touch each other count as one stretch,
   so booking 10–12 and then 12–14 is treated as 4 hours in a row and refused.
3. **Daily limit** (default 4 hours per item, per group, per day). Without it, a group could
   get round rule 2 by leaving one-hour gaps — 8–11, 12–15, 16–19 — and hold an item all day.
4. **Rotating priority** (default on). See below.

A rule change applies to new requests only. Existing bookings are never touched.

### What happens when a slot is already taken

The request is refused, and the program says exactly why — for example
*"CAM-02 is already booked by TrafficAI from 10:00 to 13:00 (R130)."* It then suggests
the same time on another item of the same type, and other start times that pass
every rule for that group.

If the only problem is that another group holds the slot, the group can **join the
waiting list**. If the slot is later cancelled, it is handed over automatically.

### Rotating priority

When several groups are waiting for the same freed slot, it does not simply go to
whoever asked first. Groups stand in a line (shown in menu 8). The freed slot goes to
the waiting group highest in the line, and that group then moves to the back. So the
same group cannot win every time.

If the group first in line would break a rule by taking the slot (say it would hold the
item 4 hours in a row), it is **passed over in the open**: the program says who was
skipped and why, records it in the history, and the group keeps its place in line.

Turn rotating priority off in menu 9 to switch to plain first come, first served.

---

## Sample session

Recorded from a real run (sample data, clock fixed at Tue 27 Oct 2026, 09:00).
The menu is shown once to save space. Your booking IDs will differ a little,
because the sample data depends on the time you load it.

```
=== Equipment Scheduler ===   Tue 27 Oct 2026, 09:00
1. Make a reservation
...
0. Save and exit
Choose: 3

--- View bookings ---
1. Timetable for one day (all equipment)
2. List of current and upcoming bookings
Choose (1-2): 1
Date (YYYY-MM-DD, or today / tomorrow): tomorrow

Timetable for Wed 28 Oct 2026
Each column is one hour. A letter is the group holding it; a dot is free.
         08 09 10 11 12 13 14 15 16 17 18 19
CAM-01    D  D  .  .  .  .  .  .  .  .  .  .
CAM-02    .  .  D  D  D  .  .  .  .  .  .  .
CAM-03    F  F  .  .  .  .  .  .  .  .  .  .
BRD-01    .  .  .  .  .  .  .  .  .  .  .  .
BRD-02    .  .  .  .  .  .  .  .  .  .  .  .
BRD-03    .  E  E  E  .  .  .  .  .  .  .  .
BRD-04    .  .  .  .  .  .  .  B  .  .  .  .
SNS-01    .  .  .  .  .  .  .  .  .  .  .  .
SNS-02    C  C  .  .  .  .  .  .  .  .  .  .
OSC-01    .  .  .  .  .  .  .  .  .  .  .  .
OSC-02    .  .  .  .  .  .  .  .  .  .  .  .
DRN-01    .  .  .  .  .  .  .  .  .  .  .  .
Key: B = GestureBot, C = AirWatch, D = TrafficAI, E = HealthBand, F = DroneMap

Choose: 1

--- Make a reservation ---
Equipment ID: CAM-02
Group: GestureBot
Date (YYYY-MM-DD, or today / tomorrow): tomorrow
Start hour (0-23): 11
End hour (1-24): 12

[DENIED] CAM-02 for GestureBot, Wed 28 Oct 11:00-12:00
  - CAM-02 is already booked by TrafficAI from 10:00 to 13:00 (R130).
  Recorded as D043 in the denied-requests report.

Suggestions:
  Same time on another camera: CAM-01, CAM-03. Use menu 1 again to book it.
  Start times that give GestureBot 1 hour on CAM-02:
    Wed 28 Oct: 08:00, 09:00, 13:00, 14:00, 15:00, 16:00, 17:00, 18:00, 19:00
    Thu 29 Oct: 08:00, 09:00, 10:00, 11:00, 12:00, 13:00, 14:00, 15:00, 16:00,
                17:00, 18:00, 19:00

You can wait for this exact slot. If the group holding it cancels, the slot is
handed over automatically, with waiting groups taking turns (rotating
priority).
Join the waiting list? (y/n): y
[OK] GestureBot is on the waiting list (W007).
  GestureBot is number 2 of 6 in the priority line right now.

Choose: 2

--- Cancel a reservation ---
Your group: TrafficAI
TrafficAI's upcoming bookings:
  R128  CAM-01  Tue 27 Oct  10:00-12:00
  R133  CAM-02  Tue 27 Oct  11:00-13:00
  R131  CAM-01  Tue 27 Oct  17:00-19:00
  R138  CAM-01  Wed 28 Oct  08:00-10:00
  R130  CAM-02  Wed 28 Oct  10:00-13:00
  R115  BRD-01  Thu 29 Oct  15:00-18:00
  R140  CAM-02  Fri 30 Oct  11:00-12:00
Booking ID to cancel: R130
Cancel R130 (CAM-02 for TrafficAI, Wed 28 Oct 10:00-13:00)? (y/n): y
[OK] Cancelled R130. It stays in the booking history.
  Handed to SmartFarm from the waiting list: CAM-02 Wed 28 Oct 10:00-13:00
  (new booking R142).
  Priority line is now: 1. GestureBot 2. DroneMap 3. AirWatch 4. HealthBand 5.
  TrafficAI 6. SmartFarm
```

In that last step, three teams were waiting for part of TrafficAI's CAM-02 slot:
HealthBand (asked first), SmartFarm (asked second) and GestureBot (just now, for
11:00-12:00 only). SmartFarm was first in the priority line, so it got the slot even
though HealthBand asked earlier, and then moved to the back of the line. Everyone
else kept their order. GestureBot's hour is now inside SmartFarm's booking, so it
keeps waiting.

---

## How the expected outcomes are met

| Expected outcome | Where |
|---|---|
| Create and cancel reservations | Menus 1 and 2. Cancelled bookings are kept, never deleted. |
| Detect conflicts | Every request is checked against existing bookings of that item; the clashing booking is named. |
| Suggest available slots | Menu 4, and automatically after every refused request (other times, and the same time on a similar item). |
| Enforce fairness rules | Back-to-back limit, daily limit, rotating priority on the waiting list (menu 9). |
| Report utilization | Menu 5: hours and percentage per item, share per group with a fairness check, busiest hour. |
| Report denied requests | Menu 6: counts by reason and by group, each request with its reason and what happened afterwards. |
| Retain booking history | Menu 7: every booking, cancellation, denial, waiting-list change and rule change, with time stamps. Option 4 follows one booking's full story. |

---

## Where the data lives

Plain text files in the `data` folder, one record per line, readable in any editor:

| File | Holds |
|---|---|
| `equipment.txt` | the items |
| `groups.txt` | the groups, in the order they were added |
| `bookings.txt` | every booking ever made, including cancelled ones |
| `waitlist.txt` | who is waiting now |
| `denied.txt` | every refused request |
| `history.txt` | the full log |
| `settings.txt` | lab hours, fairness rules, the priority line, ID counters, and a stamp that changes on every save |

Everything is saved straight after every change. Each file is written to a temporary
copy and then swapped in, so closing the window mid-save cannot corrupt it. If two
windows of the program are open on the same data, each picks up the other's changes
before acting, so neither overwrites the other. If a line is ever damaged (for example
by hand-editing), the program skips it, says which line, and keeps a backup copy of
the original files before carrying on.

Saved data is always read into a fresh copy first, and only swapped in once the whole
read has worked. If the files cannot be read at all (for example, another program has
one locked), the program keeps what it had, refuses the change you asked for, and
saves nothing until the files can be read again, so good data is never overwritten
with half-loaded data.

To keep data somewhere else: `java -cp out Main --data my-folder`.

---

## How the code is organised

| File | Job |
|---|---|
| `Main.java` | Starts the program, loads saved data |
| `ConsoleUI.java` | The menu, every question, every printed result |
| `Scheduler.java` | All the rules: conflict check, fairness rules, waiting list, rotating priority, suggestions, history searches |
| `Reports.java` | Utilization and denied-requests reports |
| `Storage.java` | Saving to and loading from the text files |
| `DemoData.java` | The sample data |
| `Booking.java`, `Equipment.java`, `WaitlistEntry.java`, `DeniedRequest.java`, `HistoryEntry.java`, `Policy.java`, `DenyReason.java` | The data each part works with |
| `TimeUtil.java` | Date and text helpers |
| `test/SchedulerTests.java` | Automatic tests (see below) |

The rules live only in `Scheduler.java`. The menu code never decides anything; it
asks the scheduler and prints the answer.

### Running the tests

The tests are plain Java with no test library, so they work on Java 8 and newer.

| Computer | What to do |
|---|---|
| Windows | Double-click `run-tests.bat`, or type `.\run-tests.bat` in PowerShell inside this folder |
| macOS / Linux | Type `./run-tests.sh` in a terminal inside this folder |

Each test prints `PASS` or `FAIL` with its name, then a total. If any test fails, the
test program ends with exit code 1. The tests use their own temporary folders and never touch
your `data` folder.

### Testing with a fixed clock

"Past" and "upcoming" depend on the current time. To get the same results every run,
fix the clock: `java -Dscheduler.now=2026-10-27T09:00 -cp out Main`.
Adding `-Dscheduler.echo=true` prints typed answers too, which is how the sample
session above was recorded from a script.

# Shared Equipment Fair-Use Scheduler (simple version)

A command-line program for booking shared lab equipment fairly between project teams.
You type answers into a numbered menu; everything is saved to plain text files.

About 790 lines of Java in six files, with no libraries. Works on Java 8 or newer.

## How it meets the expected outcomes

| Expected outcome | How |
|---|---|
| Create and cancel reservations | Menus 1 and 2. A cancelled booking is marked, never deleted. |
| Detect conflicts | Every request is checked against the item's bookings that day; the clashing booking is named. Touching bookings (10-12 and 12-14) are allowed. |
| Suggest available slots | After any refusal: the same time on another item of the same type, and start times that pass every rule. Also menu 4. |
| Enforce fairness rules | Lab hours 08:00-20:00, at most 3 hours in a row, at most 4 hours per item per team per day, and a waiting list with a fair priority line. |
| Report utilization and denied requests | Menu 5 (hours and % per item, share per team) and menu 6 (refused requests by reason, and what happened to each). |
| Retain booking history | Menu 7: every booking, refusal, wait, cancellation and handover, with times, kept for good. |

## How to run it

You need the Java JDK (check with `javac -version`). Unzip first, then inside the folder:

| Computer | Run the program | Run the tests |
|---|---|---|
| Windows (PowerShell) | `.\run.bat` | `.\run-tests.bat` |
| macOS / Linux | `./run.sh` | `./run-tests.sh` |

The first time, there is no saved data, so sample data is loaded: six items, six teams and a
few days of bookings around today. To start again from fresh sample data, delete the `data` folder.

## A two-minute demo

1. Menu 8: two teams are waiting for TrafficAI's CAM-02 slot tomorrow, 10:00-13:00.
   HealthBand asked first, but SmartFarm is higher in the priority line.
2. Menu 1: ask for CAM-02, GestureBot, tomorrow, 11 to 12. It is refused, the clash is named,
   other times and CAM-01 are suggested. Answer `y` to wait.
3. Menu 2: TrafficAI cancels that booking. The slot goes to SmartFarm (highest in the line,
   not first to ask), and SmartFarm moves to the back of the line.
4. Menus 5, 6 and 7: the usage report, the refused requests, the history.

## The fairness rules

- **Lab hours.** Every booking must fit inside 08:00-20:00.
- **Back-to-back limit.** A team cannot hold one item for more than 3 hours in a row.
  Bookings that touch count as one stretch, so 10-12 followed by 12-14 is refused.
- **Daily limit.** At most 4 hours per item, per team, per day. Without it, a team could leave
  one-hour gaps (8-11, 12-15, 16-19) and hold an item all day.
- **Waiting list and priority line.** Only a request refused for a clash can wait (a broken rule
  never goes away by waiting). When a booking is cancelled, the freed slot goes to the waiting
  team highest in the priority line, and that team moves to the back. Everyone else keeps
  their order. A waiting team that would now break a rule is passed over openly, and keeps
  its place.

The rules are fixed numbers at the top of `Scheduler.java`.

## Where the data lives

Plain text files in the `data` folder, one record per line, fields separated by `|`:

| File | Holds |
|---|---|
| `equipment.txt` | each item and its type |
| `teams.txt` | the teams, in priority-line order |
| `bookings.txt` | every booking ever made, cancelled ones too |
| `requests.txt` | every refused request, and what happened to it (refused, waiting, granted or expired) |
| `history.txt` | the full log |

Everything is saved straight after every change. Each file is written to a temporary copy and
then swapped in, so closing the window mid-save cannot leave half a file. If a saved file is
damaged, the program says which file and line, and stops without saving anything, so good data
is never overwritten.

To keep data somewhere else: `java -cp out Main my-folder`.

## How the code is organised

| File | Job |
|---|---|
| `Main.java` | The menu: asks questions, checks each answer, prints results |
| `Scheduler.java` | All the data and every rule: conflict check, limits, waiting list, priority line, suggestions, usage |
| `Booking.java` | One booking |
| `Request.java` | One refused request (which may be waiting for its slot) |
| `Storage.java` | Saving to and loading from the text files |
| `SampleData.java` | The sample data, made through the real rules |
| `test/SchedulerTests.java` | 20 automatic tests in plain Java |

The rules live only in `Scheduler.java`; the menu never decides anything.

To get the same results every run (for tests or a recorded demo), fix the clock:
`java -Dscheduler.now=2026-10-27T09:00 -cp out Main`.

# UCACS — University Campus Access Control System

A smart-card access control system for a university campus. Students carry a
Java Card applet holding their access rights and their presence state; a
terminal application reads the card over APDU, checks each request against the
student's timetable in PostgreSQL, and records how long they actually spent in
each zone.

The interesting part is not the door opening. It is that attendance is
*measured* — time in lecture halls and laboratories accumulates per day and per
semester, and access to those rooms is granted only when the student's schedule
says they should be there.

Bachelor's degree project. Java Card 3.1, Java 21, PostgreSQL.

## Demo

[![Watch the demo](https://img.youtube.com/vi/_bvCfoz5qyo/hqdefault.jpg)](https://www.youtube.com/watch?v=_bvCfoz5qyo)

<https://www.youtube.com/watch?v=_bvCfoz5qyo>

---

## How it fits together

```
┌────────────────────┐   APDU over T=1    ┌──────────────────┐   JDBC   ┌────────────┐
│  Java Card applet  │◄──────────────────►│  Terminal (Java) │◄────────►│ PostgreSQL │
│  accessControl     │   localhost:9025   │  CLI             │          │ campusAcc… │
└────────────────────┘                    └──────────────────┘          └────────────┘
   holds, on-card:                          decides:                       stores:
   • PIN (OwnerPIN, 3 tries)                • is it term time?             • students
   • 9 zone permission bits                 • is it on your timetable?     • timetable
   • presence flag per zone                 • have you met your quota?     • hours per zone
   • per-day / per-semester counters        • one zone at a time
```

The split matters. **The card is the authority on identity and entitlement** —
it answers no command until the PIN is verified, and it refuses an exit from a
zone it never recorded you entering. **The terminal is the authority on policy** —
semester dates, timetables and hour quotas live in the database, because those
change every term and a card cannot be reissued for that.

---

## The card applet

`ACSC/src/accesscontrol/accessControl.java` — a Java Card 3.1 applet.

- **Package AID** `A0:00:00:00:88:18:05:18:58`
- **Applet AID** `A0:00:00:00:88:18:05:18:59`
- **CLA** `0x80`

### On-card state

| Field | Size | Purpose |
|---|---|---|
| `pin` | `OwnerPIN`, 3 tries, max 8 bytes | Access PIN, installed as `01 02 03 04 05` |
| `accessZones` | 23 bytes | Entitlements and attendance counters (below) |
| `studentInZone` | 9 bytes | Presence flag per zone — `1` while inside |

`accessZones` layout:

```
[0]  course classroom      [5]  canteen
[1]  lab classroom         [6]  coffee shop
[2]  lecture classroom     [7]  confectionery
[3]  library               [8]  dormitory
[4]  bookstore

[9..15]  course data — hours today, HH, MM, DD, MM, YY, hours this semester
[16..22] lab data    — the same seven fields
```

Zones 0 and 1 are the *special* zones: the ones with a timetable and an hour
quota attached. The rest are plain yes/no entitlements.

### Instruction set

| INS | Name | Lc | Data in | Effect |
|---|---|---|---|---|
| `0x20` | `VERIFY` | *n* | PIN digits | Validates the PIN for this session |
| `0x30` | `UPDATE_PIN` | 10 | current PIN (5) + new PIN (5) | Changes the PIN |
| `0x40` | `CHECK_ACCESS` | 1 | zone | Grants entry, sets the presence flag |
| `0x41` | `EXIT_ZONE` | 1 | zone | Clears the presence flag |
| `0x50` | `SET_ACCESS_ZONES` | 23 | entitlement map | Loads the student's rights |
| `0x51` | `SET_STUDENT_ZONE` | 9 | presence map | Restores presence state |

Every instruction except `VERIFY` calls `pin.isValidated()` first and fails with
`0x6301` if it has not been. `deselect()` clears that flag, so the PIN must be
entered again each time the card is presented.

### Status words

| SW | Meaning |
|---|---|
| `0x9000` | Success |
| `0x6300` | `SW_VERIFICATION_FAILED` — wrong PIN |
| `0x6301` | `SW_PIN_VERIFICATION_REQUIRED` — PIN not validated this session |
| `0x6982` | `SW_ACCESS_DENIED` — no entitlement for that zone |
| `0x6985` | `SW_EXIT_DENIED` — not currently recorded inside that zone |
| `0x6700` | Wrong length |

`select()` returns false once `getTriesRemaining()` reaches zero, so three wrong
PINs lock the card rather than merely refusing the command.

---

## The terminal

`Terminal/src/main/java/org/example/Terminal.java` — a CLI that talks to the card
simulator over a socket and to PostgreSQL over JDBC.

### Commands

| Command | What it does |
|---|---|
| `verifyPin` | Sends `VERIFY`. On success, pushes the entitlement map to the card and resets the daily counter if the date has rolled over. |
| `updatePin` | Sends current + new PIN as one 10-byte payload. Invalidates the session, so you must verify again. |
| `checkAccess` | Asks for a zone 0–8 and runs the full policy check before consulting the card. |
| `exitZone` | Releases the zone and accumulates the elapsed time. |
| `checkHours` | Reads total hours studied this semester from the database. |
| `listCommands` | Lists the above. |
| `exitCampus` | Writes the session's per-zone time to the database and exits. |

### The access decision

`checkAccess` on a special zone passes five gates, in order:

1. **Session** — is the PIN validated? Nothing proceeds otherwise.
2. **One zone at a time** — you cannot enter a second zone while still inside the
   first. Tracked terminal-side.
3. **Semester window** — the request must fall between `SEMESTER_START` and
   `SEMESTER_END` (26 Feb – 7 Jun 2024). Outside it, special zones are closed.
4. **Timetable** — `student_schedule` must hold a row for this student and zone
   matching today's weekday, with the current time inside `start_time … end_time`.
5. **Quota** — if the student has already logged the required 120 hours in that
   zone this semester, entry is refused as unnecessary.

Only then is `CHECK_ACCESS` sent, and the card makes the final call against its
own entitlement bit.

Exit is the mirror image, and the card is what enforces it: `EXIT_ZONE` returns
`0x6985` when the presence flag is not set, so a card cannot leave a zone it
never entered.

### Time accounting

Entry time is stamped in memory on a successful `CHECK_ACCESS`. On exit, the
elapsed span is added to a per-zone total. `exitCampus` flushes those totals to
the database as PostgreSQL `interval` values, updating both
`hours_studied_today` and `hours_studied_semester`, and stamps
`last_update_date` so the next login knows whether the daily figure needs
resetting.

---

## Database

`db/database_dump.sql` — three tables.

```sql
students         (student_id, student_name, faculty,
                  hours_studied_today interval, hours_studied_semester interval,
                  last_update_date date)

student_schedule (student_id, day_of_week, start_time, end_time, zone)

zone_hours       (student_id, zone, hours_studied_semester interval)
```

`students` holds the semester-wide total; `zone_hours` breaks it down per zone,
which is what the quota check reads. Storing durations as `interval` rather than
as integer minutes keeps accumulation to a single `?::interval + ?::interval` in
SQL instead of arithmetic in Java.

The dump seeds a full week of timetable rows for student `0`, positioned partway
through a semester — 120:43 hours in zone 0, 101:00 in zone 1 — so the quota
check can be exercised in both the met and unmet states.

---

## Running it

**Requirements**

- Oracle Java Card Development Kit **Simulator 3.1.0** — provides `cref` and `apduio`
- JDK 21
- PostgreSQL, with a database named `campusAccessDB`

**Steps**

1. Restore the schema and sample data:

   ```
   createdb campusAccessDB
   psql -d campusAccessDB -f db/database_dump.sql
   ```

2. Build the applet using `ACSC/configurations/ACSC.conf`. It targets 3.1.0 and
   emits CAP, JCA and EXP into `ACSC/deliverables/ACSC/`; prebuilt output is
   already committed.

3. Edit the two hardcoded paths at the top of `Terminal.java` — the `cref`
   executable and `apdu_scripts/cap-ACSC.script`.

4. Check the JDBC settings in `DatabaseConnection.java`.

5. Run `Terminal`. It launches `cref`, connects on `localhost:9025`, replays the
   CAP loading script, creates and selects the applet, then drops into the
   command loop. The installed PIN is `12345`.

> **On the build:** `pom.xml` declares only the PostgreSQL driver, but
> `Terminal.java` imports `com.sun.javacard.apduio`. That jar ships with the Java
> Card Development Kit and must be put on the classpath manually — or installed
> into your local Maven repository — before the project will compile.

---

## Layout

```
ACSC/                         Java Card applet
  src/accesscontrol/          applet source
  configurations/             build config — AIDs, target, output formats
  apdu_scripts/               CAP loading and applet selection scripts
  deliverables/               prebuilt CAP / JCA / EXP
Terminal/                     Maven project — CLI terminal
  src/main/java/org/example/  Terminal.java, DatabaseConnection.java
db/database_dump.sql          schema and sample data
```

---

## Known limitations

Written down rather than hidden — they are the obvious next steps.

- **Single student.** `studentId` is initialised to `0` and never read back from
  the card, so every session resolves to the seeded student. The applet reserves
  a `studentId` field for this but does not yet expose it over APDU.
- **Hardcoded paths.** The simulator and script locations in `Terminal.java` are
  absolute Windows paths and have to be edited per machine.
- **Credentials in source.** `DatabaseConnection` carries a local
  `postgres`/`postgres` development login; it belongs in an environment variable
  or a properties file.
- **Attendance counters are declared twice.** The applet reserves 14 bytes for
  per-day and per-semester course and lab totals, but the working figures live in
  PostgreSQL. Moving them onto the card would let it enforce the quota without a
  database round trip.
- **The CLI is in Romanian.** Protocol, schema and source are in English; only
  the user-facing prompts are not.

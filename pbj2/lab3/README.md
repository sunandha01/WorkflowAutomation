# Lab 3 – Hospital Appointment and Patient Triage (Camunda 8)

All patient data is synthetic. The triage rules are a predefined educational scenario, not clinical guidance.

## Contents

| Path | What it is |
|---|---|
| `models/hospital-appointment.bpmn` | Process `hospital-appointment` |
| `models/triage-category.dmn` | Decision table `triage-category` |
| `models/staff-review.form` | Tasklist form for staff review |
| `models/urgent-assessment.form` | Tasklist form for urgent assessment |
| `src/main/java/.../RegistrationWorker.java` | Job workers `validate-registration` (validate fields and prepare the availability request) and `book-appointment` |
| `src/main/java/.../MockSchedulingApi.java` | Mock scheduling API `GET /api/slots` and an outage switch |
| `test-data/*.json` | One request body per test scenario |

## Architecture

```
Start ─► Validate registration (worker) ─► Registration valid? ─no─► Registration rejected
                                              │yes
                                              ▼
                                       Triage (DMN) ─► Triage category?
          Urgent Assessment ─► Immediate clinical assessment (Tasklist) ─► Handed to clinician
          Routine ─► Check appointment slots (REST Connector ─► mock scheduling :8092)
                         ─► Slot available? ─yes─► Book appointment (worker) ─► Appointment booked
                                            └no──┐
          Priority Review ───────────────────────┴─► Staff review (Tasklist) ─► Staff review completed
```

* **Camunda 8 Run (c8run) 8.8** provides Zeebe, Operate, Tasklist and the Connectors runtime.
* **This Spring Boot app** (port 8092) runs both job workers and hosts the mock scheduling API.

## Setup

1. Start c8run: `./start.sh` in its folder.
2. Start this app: `mvn spring-boot:run` (or run `TriageApplication` in IntelliJ).
3. In Desktop Modeler, deploy `triage-category.dmn`, `staff-review.form`, `urgent-assessment.form`, then `hospital-appointment.bpmn` (target: c8run (local), Camunda 8.8).
4. Start instances:

```bash
curl -u demo:demo -H "Content-Type: application/json" \
  -X POST http://localhost:8080/v2/process-instances -d @test-data/1-routine-booked.json
```

## DMN rules (`triage-category`, hit policy FIRST)

| # | chestPain | breathingDifficulty | painScore | symptomDays | urgencyCategory |
|---|---|---|---|---|---|
| R1 | true | – | – | – | Urgent Assessment |
| R2 | – | true | – | – | Urgent Assessment |
| R3 | – | – | >= 7 | – | Priority Review |
| R4 | – | – | – | > 14 | Priority Review |
| R5 | – | – | – | – | Routine |

These are predefined educational rules. Red-flag indicators come first, then severity or duration, and everything else is routine.

## Gateway routing

| Gateway | Condition | Route |
|---|---|---|
| Registration valid? | `registrationValid = true` / `false` | Triage / Registration rejected |
| Triage category? | `urgencyCategory = "Urgent Assessment"` | Immediate clinical assessment (task priority 100) |
| | `urgencyCategory = "Routine"` | Check appointment slots |
| | `urgencyCategory = "Priority Review"` | Staff review |
| Slot available? | `slotCount > 0` | Book appointment |
| | default | Staff review (no suitable slot) |

## REST Connector (Check appointment slots)

* Template: REST Outbound Connector (`io.camunda.connectors.HttpJson.v2`, version 12)
* `GET ="http://localhost:8092/api/slots?department=" + department + "&date=" + preferredDate + "&session=" + preferredSession`, no auth, with retries 3 and backoff PT5S
* Result expression: `={availableSlots: response.body.slots, slotCount: count(response.body.slots)}`

Mock behaviour: MORNING returns 09:00, 09:30 and 10:00; AFTERNOON returns 14:00 and 14:30. **2026-11-06 is fully booked** and returns no slots. When the outage switch is on, the API returns HTTP 503.

```
GET /api/slots?department=GP&date=2026-11-03&session=MORNING
200 {"department":"GP","date":"2026-11-03","session":"MORNING","slots":["09:00","09:30","10:00"]}

GET /api/slots?department=GP&date=2026-11-06&session=MORNING
200 {"department":"GP","date":"2026-11-06","session":"MORNING","slots":[]}

(outage on) 503 {"error":"Scheduling service unavailable"}
```

## Process variables

| Variable | Type | Set by | Purpose |
|---|---|---|---|
| patientRef | String | start | Synthetic patient reference (PT-1234) |
| preferredDate, preferredSession | String | start | Appointment preference (date and MORNING/AFTERNOON) |
| symptomsSummary | String | start | Free-text summary (synthetic) |
| chestPain, breathingDifficulty | Boolean | start | Urgency indicators, used as DMN inputs |
| painScore, symptomDays | Number | start | Urgency indicators, used as DMN inputs |
| registrationValid, validationErrors | Boolean, List | worker | Validation result |
| department | String | worker | Clinic for the availability request (GP) |
| urgencyCategory | String | DMN | Routine / Priority Review / Urgent Assessment |
| availableSlots, slotCount | List, Number | REST connector | Slots returned by the scheduling API |
| bookingStatus | String | booking worker or staff form | BOOKED / BOOKED_MANUALLY / WAITLISTED / REFERRED |
| bookedSlot, bookingRef | String | booking worker | Confirmed slot and reference |
| staffNotes | String | staff form | Staff comment |
| assessmentOutcome, clinicianNotes | String | urgent form | Simulated urgent assessment result |

## Test cases

All runs on 31 Oct 2026 against local Camunda 8 Run 8.8.32, process version 2. All 5 passed.

| # | File | Instance key | Key inputs | Expected route | Actual | Status |
|---|---|---|---|---|---|---|
| 1 | 1-routine-booked | 2251799819256790 | PT-1001, pain 2, 2026-11-03 MORNING | Routine → slots → Appointment booked | Routine (R5), slotCount 3, booked 09:00 (BK-1001-0900) → **Appointment booked** | ✅ Pass |
| 2 | 2-priority-review | 2251799819256856 | PT-1002, pain 8 | Priority Review → Staff review | Priority Review (R3) → Tasklist: BOOKED_MANUALLY → **Staff review completed** | ✅ Pass |
| 3 | 3-urgent-assessment | 2251799819256908 | PT-1003, chestPain true | Urgent Assessment → Immediate clinical assessment | Urgent Assessment (R1) → Tasklist (priority Critical): SEEN_BY_CLINICIAN → **Handed to clinician** | ✅ Pass |
| 4 | 4-no-slots | 2251799819256945 | PT-1004, 2026-11-06 (fully booked) | Routine → 0 slots → Staff review | Routine, slotCount 0 → Tasklist: WAITLISTED → **Staff review completed** | ✅ Pass |
| 5 | 5-scheduling-failure | 2251799819257099 | PT-1005, scheduling API down | 503 → incident → restore → Retry → booked | Incident (HTTP 503, no retries left) → outage switched off → Retry → slotCount 2, booked 14:00 (BK-1005-1400) → **Appointment booked** (11:41:50) | ✅ Pass |

## Incident and recovery (scenario 5)

1. Simulate the outage: `curl -X POST "http://localhost:8092/mock/scheduling/outage?down=true"`
2. Start `5-scheduling-failure.json`. The REST Connector gets HTTP 503 three times, and Operate shows an incident on *Check appointment slots*.
3. Fix the cause: `curl -X POST "http://localhost:8092/mock/scheduling/outage?down=false"`
4. In Operate, click **Retry**. The slots are found and the appointment is booked.

## Screenshots (`screenshots/`)

| File | Shows |
|---|---|
| 01-scheduling-api-curl | Mock scheduling API returning slots |
| 02 – 05 | BPMN, DMN, staff-review form and urgent-assessment form in Desktop Modeler |
| 06-start-instances-curl | The 5 scenario instances started, plus the outage switch |
| 07-operate-instance-list | Operate instance list |
| 08 | Scenario 1: routine request booked |
| 09 – 11 | Scenario 2: waiting at Staff review → Tasklist form → completed |
| 12 – 14 | Scenario 3: waiting at Immediate clinical assessment → Tasklist (Critical) → handed to clinician |
| 15 – 17 | Scenario 4: no slots → Staff review → Tasklist (Slots found: 0) → completed |
| 18 – 21 | Scenario 5: incident → 503 details → scheduling restored → retried and booked |

## Limitations

* The triage rules are simplified and not clinically validated. All patients are synthetic.
* Every request goes to one department (GP). Slots are static and are not reserved after booking.
* Staff and urgent tasks are assigned to the single `demo` user. The urgent path only simulates handing the case to a clinician.
* The scheduling API is a mock with no authentication.

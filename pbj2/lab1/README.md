# Lab 1 – Loan Approval Automation (Camunda 8)

All data is synthetic. There are no real credentials or personal data.

## Contents

| Path | What it is |
|---|---|
| `models/loan-approval.bpmn` | Process `loan-approval` |
| `models/loan-decision.dmn` | Decision table `loan-decision` |
| `models/loan-manual-review.form` | Tasklist form for the manual review |
| `src/main/java/.../ValidateApplicantWorker.java` | Spring Boot job worker (`validate-applicant`) |
| `src/main/java/.../MockCreditApi.java` | Mock credit-assessment API (`GET /api/credit-score/{applicantId}`) |
| `test-data/*.json` | One request body per test scenario |

## Architecture

```
Start ─► Validate applicant (Java worker) ─► Valid? ─no─► Invalid application
                                              │yes
                                              ▼
                     Credit assessment (REST Connector ─► mock API :8090)
                       │ error/timeout (boundary event)       │ ok
                       │                                      ▼
                       │                         Loan decision (DMN) ─► Decision?
                       │                              Approved ─► Loan approved
                       │                              Rejected ─► Loan rejected
                       ▼                              Manual Review
                  Manual review (Tasklist) ◄──────────────┘
                       ▼
               Reviewer decision? ─► Approved after review / Rejected after review
```

* **Camunda 8 Run (c8run)** provides Zeebe, Operate, Tasklist and the Connectors runtime, all on localhost.
* **This Spring Boot app** (port 8090) runs the job worker and also hosts the mock credit API.

## Setup

1. Install **Java 21**, **Camunda Desktop Modeler** and **Camunda 8 Run** 8.8.x (the client library in `pom.xml` is 8.8.32).
2. Start c8run: `./start.sh` in its folder. Operate is at <http://localhost:8080/operate> and Tasklist at <http://localhost:8080/tasklist> (`demo` / `demo`).
3. Open this folder in IntelliJ as a Maven project and run `LoanApplication`. Alternatively run `mvn spring-boot:run`.
4. Check the mock API: `curl http://localhost:8090/api/credit-score/A-100`
5. In Desktop Modeler, open the three files in `models/`. Deploy each one using the rocket icon → *Camunda 8 Self-Managed* → `http://localhost:8080`. Deploy the DMN and the form first, then the BPMN.

## Starting instances

Using curl (one file per scenario):

```bash
curl -u demo:demo -H "Content-Type: application/json" \
  -X POST http://localhost:8080/v2/process-instances -d @test-data/1-approved.json
```

Alternatively, use Modeler → *Start instance* and paste the `variables` object from a test-data file.

## DMN rules (`loan-decision`, hit policy FIRST)

| # | creditScore | paymentToIncomeRatio | loanAmount | decision |
|---|---|---|---|---|
| R1 | < 550 | – | – | Rejected |
| R2 | – | > 0.5 | – | Rejected |
| R3 | >= 700 | <= 0.35 | <= 50000 | Approved |
| R4 | – | – | – | Manual Review |

These are simplified educational rules. `paymentToIncomeRatio` is `(loanAmount / loanTermMonths) / monthlyIncome`, with no interest.

Sample evaluations:

| creditScore | ratio | loanAmount | rule hit | decision |
|---|---|---|---|---|
| 780 | 0.09 | 20000 | R3 | Approved |
| 480 | 0.16 | 15000 | R1 | Rejected |
| 640 | 0.13 | 30000 | R4 | Manual Review |
| 720 | 0.62 | 40000 | R2 | Rejected |

## REST Connector

Settings in the *Credit assessment (REST)* task:

* Method `GET`, URL `="http://localhost:8090/api/credit-score/" + applicantId`, no auth, read timeout 5 s
* Result expression: `={creditScore: response.body.creditScore, creditReference: response.body.reference}`
* Error expression: `=if error != null then bpmnError("CREDIT_API_ERROR", error.message, {creditApiError: {code: error.code, message: error.message}}) else null`

  This sends any HTTP error or timeout to the error boundary event, which leads to **Manual review**.
* Boundary event output mapping: `creditApiError` = `=string join([creditApiError.code, creditApiError.message], " ")`. Variables passed with `bpmnError` stay local to the error event, so this copies them to process scope for the Tasklist form.

Example request and response:

```
GET http://localhost:8090/api/credit-score/A-100
200 {"applicantId":"A-100","creditScore":780,"reference":"CB-A-100"}

GET http://localhost:8090/api/credit-score/ERR-400
503 {"error":"Credit bureau unavailable"}
```

Mock behaviour: `A-100`→780, `A-200`→480, `A-300`→640, `A-600`→760, any other ID→650. IDs starting with `ERR` return 503. IDs starting with `SLOW` take 10 s, which is longer than the 5 s timeout.

## Process variables

| Variable | Type | Set by | Purpose |
|---|---|---|---|
| applicantId | String | start | Synthetic applicant reference, also used in the API URL |
| applicantName | String | start | Display name |
| age | Number | start | Eligibility check (18–75) |
| loanAmount | Number | start | Requested amount (1–500000) |
| monthlyIncome | Number | start | Used for affordability; if missing, the worker fails (incident test) |
| loanTermMonths | Number | start | Term (6–360) |
| valid | Boolean | worker | Validation result, used by the *Valid?* gateway |
| validationErrors | List | worker | Reasons the application is invalid |
| monthlyInstallment | Number | worker | loanAmount / loanTermMonths |
| paymentToIncomeRatio | Number | worker | DMN input |
| creditScore | Number | REST connector | DMN input |
| creditReference | String | REST connector | Bureau reference |
| creditApiError | String | REST error path (boundary output mapping) | e.g. "503" or "408 The request timed out…", shown to the reviewer |
| decision | String | DMN | Approved / Rejected / Manual Review |
| reviewDecision | String | Tasklist form | Approved / Rejected from the reviewer |
| reviewComment | String | Tasklist form | Reviewer note |

## Test cases

All runs on 31 Oct 2026 against local Camunda 8 Run 8.8.32. All 7 passed.

| # | File | Instance key (version) | Key inputs | Expected route | Actual | Status |
|---|---|---|---|---|---|---|
| 1 | 1-approved | 2251799819229310 (v2) | A-100, 20000/36, income 6000 | DMN R3 → Loan approved | creditScore 780, ratio 0.09, decision Approved → **Loan approved** | ✅ Pass |
| 2 | 2-rejected | 2251799819229361 (v2) | A-200 (score 480) | DMN R1 → Loan rejected | creditScore 480, decision Rejected → **Loan rejected** | ✅ Pass |
| 3 | 3-manual-review | 2251799819233882 (v3) | A-300 (score 640) | DMN R4 → Manual review → reviewer decides | decision Manual Review → Tasklist → reviewer Approved → **Approved after review** | ✅ Pass |
| 4a | 4a-credit-api-error | 2251799819234146 (v3) | ERR-400 → HTTP 503 | Boundary error → Manual review | creditApiError "503" → Tasklist → reviewer Rejected → **Rejected after review** | ✅ Pass |
| 4b | 4b-credit-api-timeout | 2251799819233559 (v3) | SLOW-500 → 10 s delay | Timeout → boundary error → Manual review | creditApiError "408 The request timed out…" → reviewer Approved → **Approved after review** | ✅ Pass |
| 5 | 5-worker-failure | 2251799819229585 (v2) | A-600, **no monthlyIncome** | Worker fails 3× → incident → fix → retry → Loan approved | Incident JOB_NO_RETRIES ("monthlyIncome is missing…") → added monthlyIncome=5000 in Operate → Retry → creditScore 760, Approved → **Loan approved** (09:14:07) | ✅ Pass |
| 6 | 6-invalid | 2251799819229608 (v2) | A-700, age 16 | Valid? = no → Invalid application | valid=false, "age must be between 18 and 75" → **Invalid application** | ✅ Pass |

Version notes:
* v1 was the initial test deployment.
* v2 stores `creditApiError` as an object on the boundary event only, so the Tasklist form showed it blank.
* v3 adds the boundary output mapping so the reviewer sees the error. Scenarios 3, 4a and 4b were re-run on v3 for the Tasklist evidence.

## Incident and recovery (scenario 5)

1. Start `5-worker-failure.json`. The worker throws *"monthlyIncome is missing…"* three times, which uses up its 3 retries.
2. In **Operate**, the instance shows an incident on *Validate applicant*. Take a screenshot of the incident and its error message.
3. Fix the cause: in the instance's **Variables** panel, add `monthlyIncome` = `5000`.
4. Click **Retry** on the incident. The worker succeeds and the instance continues to *Loan approved*. Take a screenshot of the completed instance.

Optional second incident: stop this Spring Boot app before starting an instance. The job then waits, because no worker is running. Restart the app and the job completes.

## Screenshots (`screenshots/`)

| File | Shows |
|---|---|
| 01-mock-api-curl | Mock credit API answering `A-100` → 780 |
| 02-modeler-dmn / 03-modeler-bpmn | Models in Desktop Modeler, connected to c8run (local) |
| 04-start-instances-curl | The 7 scenario instances started through the REST API |
| 05-operate-instance-list | Operate instance list (running, incident and completed) |
| 06 / 07 | Scenario 1 approved, scenario 2 rejected (path + variables) |
| 08 – 10 | Scenario 3: waiting at Manual review → Tasklist form → approved after review |
| 11 – 12 | Scenario 4a: Tasklist shows 503 → rejected after review |
| 13 – 14 | Scenario 4b: Tasklist shows 408 timeout → approved after review |
| 15 – 17 | Scenario 5: incident → monthlyIncome added → retried and completed |
| 18 | Scenario 6: invalid application (age 16) |

## Limitations

* Rules and scoring are simplified and educational. Interest is ignored.
* The mock API is deterministic and has no authentication.
* The manual review is assigned to the single `demo` user. There are no candidate groups.
* Variables passed via `bpmnError(...)` are local to the error event; an output mapping is needed to expose them (found and fixed during testing, see v3).
* Desktop Modeler keeps its own copy of open files; editing a file outside Modeler and then saving in Modeler overwrites the change.

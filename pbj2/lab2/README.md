# Lab 2 – E-Commerce Order Processing and Fulfilment (Camunda 8)

All data is synthetic. Payment tokens are fake strings for the mock gateway; no real card data is used.

## Contents

| Path | What it is |
|---|---|
| `models/order-processing.bpmn` | Process `order-processing` |
| `models/order-priority.dmn` | Decision table `order-priority` |
| `models/order-verification.form` | Tasklist form for high-value verification |
| `src/main/java/.../ValidateOrderWorker.java` | Job worker `validate-order`: validates the order, checks stock and calculates the total |
| `src/main/java/.../InventoryService.java` | Simulated inventory (catalog plus an outage switch) |
| `src/main/java/.../MockApis.java` | Mock payment gateway `POST /api/payments` and the outage switch |
| `test-data/*.json` | One request body per test scenario |

## Architecture

```
Start ─► Validate order & check stock (Java worker) ─► Valid and in stock?
            invalid ─► Invalid order
            out of stock ─► Out of stock
            in stock ─► Determine priority (DMN) ─► High value? (orderTotal >= 5000)
                 no  ───────────────────────────────┐
                 yes ─► Verify high-value order (Tasklist) ─► Verified?
                            rejected ─► Cancelled after review
                            approved ─┐             │
                                      ▼             ▼
                         Process payment (REST Connector ─► mock gateway :8091)
                                      ▼
                         Payment approved? ─► Sent to fulfilment / Payment declined
```

* **Camunda 8 Run (c8run) 8.8** provides Zeebe, Operate, Tasklist and the Connectors runtime.
* **This Spring Boot app** (port 8091) runs the job worker and hosts the mock payment gateway.

## Setup

1. Start c8run: `./start.sh` in its folder.
2. Start this app: `mvn spring-boot:run` (or run `OrderApplication` in IntelliJ).
3. In Desktop Modeler, deploy `order-priority.dmn`, then `order-verification.form`, then `order-processing.bpmn` (target: c8run (local), Camunda 8.8).
4. Start instances:

```bash
curl -u demo:demo -H "Content-Type: application/json" \
  -X POST http://localhost:8080/v2/process-instances -d @test-data/1-fulfilled.json
```

## Simulated inventory

| productId | Product | Price | Stock |
|---|---|---|---|
| P-100 | Wireless Mouse | 25 | 50 |
| P-200 | Mechanical Keyboard | 80 | 3 |
| P-300 | Laptop | 1200 | 10 |

`orderTotal = price × quantity` (+ 15 for EXPRESS delivery).

## DMN rules (`order-priority`, hit policy FIRST)

| # | deliveryPreference | customerCategory | orderTotal | deliveryPriority |
|---|---|---|---|---|
| R1 | "EXPRESS" | – | – | HIGH |
| R2 | – | "GOLD" | >= 1000 | HIGH |
| R3 | – | "GOLD" | – | MEDIUM |
| R4 | – | – | >= 1000 | MEDIUM |
| R5 | – | – | – | LOW |

These are simplified educational rules. The manual-verification condition is a separate gateway rule: **orderTotal >= 5000**.

## Gateway routing

| Gateway | Condition | Route |
|---|---|---|
| Valid and in stock? | `orderValid = false` | Invalid order |
| | `orderValid and not(inStock)` | Out of stock (not sent to fulfilment) |
| | `orderValid and inStock` | Determine priority |
| High value? | `orderTotal >= 5000` | Verify high-value order (Tasklist) |
| | default | Process payment |
| Verified? | `verificationDecision = "APPROVED"` / `"REJECTED"` | Process payment / Cancelled after review |
| Payment approved? | `paymentStatus = "APPROVED"` | Sent to fulfilment |
| | default | Payment declined |

## REST Connector (Process payment)

* Template: REST Outbound Connector (`io.camunda.connectors.HttpJson.v2`, version 12)
* `POST http://localhost:8091/api/payments`, no auth, with retries 3 and backoff PT5S
* Body: `={paymentToken: paymentToken, amount: orderTotal, orderRef: customerRef + "-" + productId}`
* Result expression: `={paymentStatus: response.body.status, transactionId: response.body.transactionId, paymentReason: response.body.reason}`

Mock behaviour: `tok_declined` (or anything not starting with `tok_`) returns DECLINED. Any other `tok_...` returns APPROVED with a transaction id.

```
POST /api/payments {"paymentToken":"tok_ok_visa","amount":50.0,"orderRef":"C-100-P-100"}
200 {"status":"APPROVED","transactionId":"TX-1d61b47c","amount":50.0}

POST /api/payments {"paymentToken":"tok_declined","amount":100.0,"orderRef":"C-300-P-100"}
200 {"status":"DECLINED","reason":"Card declined by mock gateway","amount":100.0}
```

## Process variables

| Variable | Type | Set by | Purpose |
|---|---|---|---|
| customerRef | String | start | Synthetic customer reference |
| customerCategory | String | start | REGULAR / GOLD, used as a DMN input |
| productId | String | start | Product to order |
| quantity | Number | start | Units ordered (1–100) |
| deliveryPreference | String | start | STANDARD / EXPRESS, used as a DMN input |
| paymentToken | String | start | Mock payment token (no real card data) |
| orderValid | Boolean | worker | Validation result |
| validationErrors | List | worker | Reasons the order is invalid |
| productName, unitPrice | String, Number | worker | From the catalog |
| availableStock | Number | worker | Stock on hand |
| inStock | Boolean | worker | `availableStock >= quantity` |
| orderTotal | Number | worker | Price × quantity + delivery fee |
| deliveryPriority | String | DMN | HIGH / MEDIUM / LOW |
| verificationDecision, verificationComment | String | Tasklist form | Reviewer outcome |
| paymentStatus | String | REST connector | APPROVED / DECLINED |
| transactionId, paymentReason | String | REST connector | Gateway reference or decline reason |

## Test cases

All runs on 31 Oct 2026 against local Camunda 8 Run 8.8.32, process version 2. All 5 passed.

| # | File | Instance key | Key inputs | Expected route | Actual | Status |
|---|---|---|---|---|---|---|
| 1 | 1-fulfilled | 2251799819242346 | P-100 ×2, REGULAR, STANDARD, tok_ok_visa | Payment APPROVED → Sent to fulfilment | orderTotal 50, priority LOW (R5), APPROVED (TX-ec324684) → **Sent to fulfilment** | ✅ Pass |
| 2 | 2-out-of-stock | 2251799819242405 | P-200 ×5 (stock 3) | Out of stock | inStock=false, availableStock 3 → **Out of stock** | ✅ Pass |
| 3 | 3-payment-declined | 2251799819242458 | P-100 ×4, GOLD, tok_declined | Payment declined | orderTotal 100, priority MEDIUM (R3), DECLINED "Card declined by mock gateway" → **Payment declined** | ✅ Pass |
| 4 | 4-high-value-review | 2251799819242508 | P-300 ×5, GOLD, EXPRESS | Verify in Tasklist → approved → Sent to fulfilment | orderTotal 6015, priority HIGH (R1) → Tasklist: APPROVED "Order is approved" → payment APPROVED → **Sent to fulfilment** | ✅ Pass |
| 5 | 5-inventory-failure | 2251799819242579 | P-200 ×2, EXPRESS, inventory down | Incident → restore → Retry → Sent to fulfilment | Incident "Inventory service unavailable" (no retries left) → outage switched off → Retry → total 175, HIGH → **Sent to fulfilment** (10:07:47) | ✅ Pass |

## Incident and recovery (scenario 5)

1. Simulate the outage: `curl -X POST "http://localhost:8091/mock/inventory/outage?down=true"`
2. Start `5-inventory-failure.json`. The worker throws *"Inventory service unavailable"* 3 times, and Operate shows an incident on *Validate order & check stock*.
3. Fix the cause: `curl -X POST "http://localhost:8091/mock/inventory/outage?down=false"`
4. In Operate, click **Retry** on the incident. The order continues to *Sent to fulfilment*.

## Screenshots (`screenshots/`)

| File | Shows |
|---|---|
| 01-payment-api-curl | Mock payment gateway answering APPROVED |
| 02 / 03 / 04 | BPMN, DMN and form in Desktop Modeler (c8run local, Camunda 8.8) |
| 05-start-instances-curl | The 5 scenario instances started through the REST API, plus the outage switch |
| 06-operate-instance-list | Operate instance list |
| 07 / 08 / 09 | Scenarios 1–3: fulfilled, out of stock, payment declined |
| 10 – 12 | Scenario 4: waiting for verification → Tasklist form → fulfilled after approval |
| 13 – 16 | Scenario 5: incident → error details → inventory restored → retried and fulfilled |

## Limitations

* The catalog, prices and stock are in memory. Stock is not decremented after an order.
* The payment gateway is a mock that decides only by token; no real payment provider or credentials are involved.
* Payment errors such as timeouts or 5xx responses are not modelled as a separate path. They retry 3 times and then raise an incident.
* High-value verification is assigned to the single `demo` user.

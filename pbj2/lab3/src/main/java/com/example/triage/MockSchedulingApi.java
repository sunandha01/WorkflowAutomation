package com.example.triage;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock hospital scheduling API (called by the REST Connector). Synthetic data only.
 * 2026-11-06 is fully booked; the outage switch makes the API return 503 for the incident test.
 */
@RestController
public class MockSchedulingApi {

  static final String FULLY_BOOKED_DATE = "2026-11-06";
  private static final Map<String, List<String>> SESSION_SLOTS = Map.of(
      "MORNING", List.of("09:00", "09:30", "10:00"),
      "AFTERNOON", List.of("14:00", "14:30"));

  private final AtomicBoolean down = new AtomicBoolean(false);

  @GetMapping("/api/slots")
  public ResponseEntity<Map<String, Object>> slots(
      @RequestParam String department, @RequestParam String date, @RequestParam String session) {
    if (down.get()) {
      return ResponseEntity.status(503).body(Map.of("error", "Scheduling service unavailable"));
    }
    List<String> slots = date.equals(FULLY_BOOKED_DATE) ? List.of() : SESSION_SLOTS.getOrDefault(session, List.of());
    return ResponseEntity.ok(Map.of("department", department, "date", date, "session", session, "slots", slots));
  }

  @PostMapping("/mock/scheduling/outage")
  public Map<String, Object> setOutage(@RequestParam boolean down) {
    this.down.set(down);
    return status();
  }

  @GetMapping("/mock/scheduling/outage")
  public Map<String, Object> status() {
    return Map.of("schedulingDown", down.get());
  }
}

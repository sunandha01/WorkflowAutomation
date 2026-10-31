package com.example.triage;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RegistrationWorkerTest {

  private final RegistrationWorker worker = new RegistrationWorker();

  @Test
  void validRegistrationGoesToGp() {
    Map<String, Object> out = worker.validate("PT-1001", "2026-11-03", "MORNING", "Mild allergy", false, false, 2, 3);
    assertEquals(true, out.get("registrationValid"));
    assertEquals("GP", out.get("department"));
  }

  @Test
  void badDateAndPainScoreAreInvalid() {
    Map<String, Object> out = worker.validate("PT-1001", "03/11/2026", "MORNING", "Mild allergy", false, false, 12, 3);
    assertEquals(false, out.get("registrationValid"));
    assertEquals(2, ((List<?>) out.get("validationErrors")).size());
  }

  @Test
  void bookingTakesFirstSlot() {
    Map<String, Object> out = worker.book("PT-1001", List.of("09:00", "09:30"));
    assertEquals("09:00", out.get("bookedSlot"));
    assertEquals("BK-1001-0900", out.get("bookingRef"));
  }
}

package com.example.triage;

import io.camunda.client.annotation.JobWorker;
import io.camunda.client.annotation.Variable;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RegistrationWorker {

  private static final Logger LOG = LoggerFactory.getLogger(RegistrationWorker.class);

  /** Validates the registration and prepares the availability request (department, date, session). */
  @JobWorker(type = "validate-registration")
  public Map<String, Object> validate(
      @Variable String patientRef,
      @Variable String preferredDate,
      @Variable String preferredSession,
      @Variable String symptomsSummary,
      @Variable Boolean chestPain,
      @Variable Boolean breathingDifficulty,
      @Variable Integer painScore,
      @Variable Integer symptomDays) {

    LOG.info("Validating registration {}", patientRef);
    List<String> errors = new ArrayList<>();
    if (patientRef == null || !patientRef.matches("PT-\\d{4}")) errors.add("patientRef must look like PT-1234 (synthetic)");
    if (symptomsSummary == null || symptomsSummary.isBlank()) errors.add("symptomsSummary is required");
    if (!List.of("MORNING", "AFTERNOON").contains(preferredSession)) errors.add("preferredSession must be MORNING or AFTERNOON");
    try {
      LocalDate.parse(String.valueOf(preferredDate));
    } catch (DateTimeParseException e) {
      errors.add("preferredDate must be YYYY-MM-DD");
    }
    if (chestPain == null || breathingDifficulty == null) errors.add("urgency indicators chestPain and breathingDifficulty are required");
    if (painScore == null || painScore < 0 || painScore > 10) errors.add("painScore must be 0-10");
    if (symptomDays == null || symptomDays < 0) errors.add("symptomDays must be 0 or more");

    if (!errors.isEmpty()) {
      return Map.of("registrationValid", false, "validationErrors", errors);
    }
    // simplification: every request goes to the GP clinic
    return Map.of("registrationValid", true, "validationErrors", errors, "department", "GP");
  }

  /** Books the first available slot returned by the scheduling API. */
  @JobWorker(type = "book-appointment")
  public Map<String, Object> book(@Variable String patientRef, @Variable List<String> availableSlots) {
    String slot = availableSlots.get(0);
    LOG.info("Booking {} for {}", slot, patientRef);
    return Map.of("bookingStatus", "BOOKED", "bookedSlot", slot,
        "bookingRef", "BK-" + patientRef.substring(3) + "-" + slot.replace(":", ""));
  }
}

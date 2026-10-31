package com.example.order;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Mock payment gateway (called by the REST Connector) and a switch to simulate an inventory outage. */
@RestController
public class MockApis {

  private final InventoryService inventory;

  public MockApis(InventoryService inventory) {
    this.inventory = inventory;
  }

  /** Synthetic tokens only: "tok_declined" is declined, any other "tok_..." is approved. */
  @PostMapping("/api/payments")
  public Map<String, Object> pay(@RequestBody Map<String, Object> request) {
    String token = String.valueOf(request.get("paymentToken"));
    if (!token.startsWith("tok_") || token.equals("tok_declined")) {
      return Map.of("status", "DECLINED", "reason", "Card declined by mock gateway", "amount", request.get("amount"));
    }
    return Map.of("status", "APPROVED", "transactionId", "TX-" + UUID.randomUUID().toString().substring(0, 8),
        "amount", request.get("amount"));
  }

  @PostMapping("/mock/inventory/outage")
  public Map<String, Object> setOutage(@RequestParam boolean down) {
    inventory.setDown(down);
    return status();
  }

  @GetMapping("/mock/inventory/outage")
  public Map<String, Object> status() {
    return Map.of("inventoryDown", inventory.isDown());
  }
}

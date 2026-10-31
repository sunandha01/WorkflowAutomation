package com.example.order;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ValidateOrderWorkerTest {

  private final InventoryService inventory = new InventoryService();
  private final ValidateOrderWorker worker = new ValidateOrderWorker(inventory);

  @Test
  void expressOrderInStockGetsTotalWithFee() {
    Map<String, Object> out = worker.validate("C-400", "GOLD", "P-300", 5, "EXPRESS", "tok_ok");
    assertEquals(true, out.get("orderValid"));
    assertEquals(true, out.get("inStock"));
    assertEquals(6015.0, out.get("orderTotal"));
  }

  @Test
  void quantityAboveStockIsOutOfStock() {
    Map<String, Object> out = worker.validate("C-200", "REGULAR", "P-200", 5, "STANDARD", "tok_ok");
    assertEquals(false, out.get("inStock"));
  }

  @Test
  void inventoryOutageFailsTheJob() {
    inventory.setDown(true);
    assertThrows(IllegalStateException.class,
        () -> worker.validate("C-500", "REGULAR", "P-100", 1, "STANDARD", "tok_ok"));
  }
}

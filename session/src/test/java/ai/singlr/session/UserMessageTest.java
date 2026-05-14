/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class UserMessageTest {

  @Test
  void textAccessorReturnsConstructedValue() {
    var msg = new UserMessage("hello there");
    assertEquals("hello there", msg.text());
  }

  @Test
  void factoryProducesEqualRecord() {
    assertEquals(new UserMessage("hi"), UserMessage.text("hi"));
  }

  @Test
  void recordsWithSameTextAreEqual() {
    var a = new UserMessage("same");
    var b = UserMessage.text("same");
    assertEquals(a, b);
    assertEquals(a.hashCode(), b.hashCode());
  }

  @Test
  void nullTextThrowsNullPointerException() {
    var ex = assertThrows(NullPointerException.class, () -> new UserMessage(null));
    assertEquals("text must not be null", ex.getMessage());
  }

  @Test
  void emptyTextThrowsIllegalArgumentException() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new UserMessage(""));
    assertEquals("text must not be blank", ex.getMessage());
  }

  @Test
  void whitespaceOnlyTextThrowsIllegalArgumentException() {
    var ex = assertThrows(IllegalArgumentException.class, () -> new UserMessage("   \t\n"));
    assertEquals("text must not be blank", ex.getMessage());
  }

  @Test
  void factoryRejectsNullToo() {
    assertThrows(NullPointerException.class, () -> UserMessage.text(null));
  }

  @Test
  void factoryRejectsBlankToo() {
    assertThrows(IllegalArgumentException.class, () -> UserMessage.text(" "));
  }
}

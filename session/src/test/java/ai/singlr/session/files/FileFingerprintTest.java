/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */
package ai.singlr.session.files;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FileFingerprintTest {

  private static final String SAMPLE_SHA = "a".repeat(64);

  @Test
  void canonicalConstructorAccepts64HexChars() {
    var fp = new FileFingerprint(Instant.EPOCH, 0, SAMPLE_SHA);
    assertEquals(SAMPLE_SHA, fp.sha256());
    assertEquals(0, fp.size());
    assertEquals(Instant.EPOCH, fp.mtime());
  }

  @Test
  void rejectsNullMtime() {
    assertThrows(NullPointerException.class, () -> new FileFingerprint(null, 0, SAMPLE_SHA));
  }

  @Test
  void rejectsNullSha() {
    assertThrows(NullPointerException.class, () -> new FileFingerprint(Instant.EPOCH, 0, null));
  }

  @Test
  void rejectsNegativeSize() {
    assertThrows(
        IllegalArgumentException.class, () -> new FileFingerprint(Instant.EPOCH, -1, SAMPLE_SHA));
  }

  @Test
  void rejectsShortSha() {
    assertThrows(
        IllegalArgumentException.class, () -> new FileFingerprint(Instant.EPOCH, 0, "abc"));
  }

  @Test
  void rejectsLongSha() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileFingerprint(Instant.EPOCH, 0, "a".repeat(65)));
  }

  @Test
  void rejectsUppercaseSha() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new FileFingerprint(Instant.EPOCH, 0, "A".repeat(64)));
  }

  @Test
  void rejectsNonHexChar() {
    var bad = "g".repeat(64);
    assertThrows(IllegalArgumentException.class, () -> new FileFingerprint(Instant.EPOCH, 0, bad));
  }

  @Test
  void ofReadsFileBytes(@TempDir Path tmp) throws IOException {
    var file = tmp.resolve("hello.txt");
    Files.writeString(file, "hello", StandardCharsets.UTF_8);
    var fp = FileFingerprint.of(file);
    assertNotNull(fp);
    assertEquals(5, fp.size());
    // sha256("hello") = 2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824
    assertEquals("2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824", fp.sha256());
  }

  @Test
  void ofRejectsNullPath() {
    assertThrows(NullPointerException.class, () -> FileFingerprint.of(null));
  }

  @Test
  void ofPropagatesIoErrorForMissingFile(@TempDir Path tmp) {
    var missing = tmp.resolve("nope.txt");
    assertThrows(IOException.class, () -> FileFingerprint.of(missing));
  }

  @Test
  void distinctContentsProduceDifferentHashes(@TempDir Path tmp) throws IOException {
    var a = tmp.resolve("a.txt");
    var b = tmp.resolve("b.txt");
    Files.writeString(a, "one", StandardCharsets.UTF_8);
    Files.writeString(b, "two", StandardCharsets.UTF_8);
    assertNotEquals(FileFingerprint.of(a).sha256(), FileFingerprint.of(b).sha256());
  }
}

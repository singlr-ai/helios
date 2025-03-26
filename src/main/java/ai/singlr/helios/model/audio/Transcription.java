/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.audio;

import java.util.List;

public record Transcription(
    String text,
    String language,
    Float duration,
    List<TranscriptionSegment> segments
) {
}

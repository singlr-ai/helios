/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.gemini.api;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * A {@code Content} union member from the Interactions API ({@code Api-Revision: 2026-05-20}).
 *
 * <p>Goes inside {@code step.content[]} on {@code user_input} and {@code model_output} steps, and
 * inside {@code step.summary[]} on {@code thought} steps. Doubles as the carrier for {@code
 * step.delta} payloads on the streaming side, where the delta union includes inline media plus the
 * lightweight {@code thought_signature} delta that ships only a {@link #signature()} byte string.
 *
 * <p>Spec discriminator values: {@code text}, {@code image}, {@code audio}, {@code document},
 * {@code video}, and (delta-only) {@code thought_signature}.
 *
 * @param type the content discriminator
 * @param text the text body (for {@code text})
 * @param mimeType the MIME type for inline or URI-referenced media (e.g. {@code image/png}, {@code
 *     application/pdf}, {@code audio/wav}, {@code video/mp4})
 * @param data Base64-encoded inline bytes (for inline {@code image}/{@code audio}/{@code
 *     document}/{@code video})
 * @param uri remote URI to fetch the media from instead of inlining (for {@code image}/{@code
 *     audio}/{@code document}/{@code video})
 * @param signature byte signature for the {@code thought_signature} streaming delta
 * @param annotations grounding citations attached to a {@code text} item; populated on the response
 *     side for {@code model_output} text content
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ContentItem(
    String type,
    String text,
    @JsonProperty("mime_type") String mimeType,
    String data,
    String uri,
    String signature,
    List<OutputAnnotation> annotations) {

  public static ContentItem text(String text) {
    return new ContentItem("text", text, null, null, null, null, null);
  }

  public static ContentItem inlineData(String type, String mimeType, String base64Data) {
    return new ContentItem(type, null, mimeType, base64Data, null, null, null);
  }

  public static ContentItem fileUri(String type, String mimeType, String uri) {
    return new ContentItem(type, null, mimeType, null, uri, null, null);
  }

  public static ContentItem image(String mimeType, String base64Data) {
    return inlineData("image", mimeType, base64Data);
  }

  public static ContentItem imageUri(String mimeType, String uri) {
    return fileUri("image", mimeType, uri);
  }

  public static ContentItem document(String mimeType, String base64Data) {
    return inlineData("document", mimeType, base64Data);
  }

  public static ContentItem documentUri(String mimeType, String uri) {
    return fileUri("document", mimeType, uri);
  }

  public static ContentItem audio(String mimeType, String base64Data) {
    return inlineData("audio", mimeType, base64Data);
  }

  public static ContentItem audioUri(String mimeType, String uri) {
    return fileUri("audio", mimeType, uri);
  }

  public static ContentItem video(String mimeType, String base64Data) {
    return inlineData("video", mimeType, base64Data);
  }

  public static ContentItem videoUri(String mimeType, String uri) {
    return fileUri("video", mimeType, uri);
  }

  public boolean hasTypeText() {
    return "text".equals(type);
  }

  public boolean hasTypeImage() {
    return "image".equals(type);
  }

  public boolean hasTypeAudio() {
    return "audio".equals(type);
  }

  public boolean hasTypeDocument() {
    return "document".equals(type);
  }

  public boolean hasTypeVideo() {
    return "video".equals(type);
  }

  public boolean hasTypeThoughtSignature() {
    return "thought_signature".equals(type);
  }

  public boolean hasAnnotations() {
    return annotations != null && !annotations.isEmpty();
  }
}

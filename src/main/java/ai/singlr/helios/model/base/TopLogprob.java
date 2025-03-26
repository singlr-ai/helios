/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.base;

import java.util.ArrayList;
import java.util.List;

public record TopLogprob(
    String token,
    Float logprob,
    List<Integer> bytes
) {

  public static Builder newBuilder() {
    return new Builder();
  }

  public static Builder newBuilder(TopLogprob topLogprob) {
    return new Builder(topLogprob);
  }

  public static class Builder {
    private String token;
    private Float logprob;
    private List<Integer> bytes;

    private Builder() {}

    private Builder(TopLogprob topLogprob) {
      this.token = topLogprob.token;
      this.logprob = topLogprob.logprob;
      this.bytes = topLogprob.bytes;
    }

    public Builder withToken(String token) {
      this.token = token;
      return this;
    }

    public Builder withLogprob(Float logprob) {
      this.logprob = logprob;
      return this;
    }

    public Builder withBytes(List<Integer> bytes) {
      this.bytes = bytes;
      return this;
    }

    public TopLogprob build() {
      if (bytes == null) {
        bytes = new ArrayList<>();
      }
      return new TopLogprob(token, logprob, bytes);
    }
  }
}

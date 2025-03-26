/*
 * Copyright (c) 2025 Singular™
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.helios.model.chat;

/**
 * Deprecated in favor of `tool_choice`.  Controls which (if any) function is called by the model. `none` means the model will not call a function and instead generates a message. `auto` means the model can pick between generating a message or calling a function. Specifying a particular function via `{\"name\": \"my_function\"}` forces the model to call that function.  `none` is the default when no functions are present. `auto` is the default if functions are present.
 **/

public class ChatCompletionFunctionChoice {

  /**
   * The name of the function to call.
   **/
  private String name;

  /**
   * The name of the function to call.
   * @return name
   **/
  public String getName() {
    return name;
  }

  /**
   * Set name
   **/
  public void setName(String name) {
    this.name = name;
  }

  public ChatCompletionFunctionChoice name(String name) {
    this.name = name;
    return this;
  }
}

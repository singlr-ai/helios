/*
 * Copyright (c) 2026 Singular
 * SPDX-License-Identifier: MIT
 */

package ai.singlr.repl;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Generates a JShell pre-eval snippet that exposes each top-level component of an input record as a
 * {@code var} in the sandbox. The model can write {@code numbers.size()} or {@code
 * operation.equals("sum")} directly without parsing the JSON itself.
 *
 * <p><b>Sandbox surface stays narrow.</b> The snippet calls {@link
 * ai.singlr.repl.sandbox.HostBridge#getInput()} to retrieve the input fields as a {@code
 * Map<String, Object>} and casts each field to its declared generic type rendered as Java source.
 * The user's input class never appears in JShell, and no third-party libraries (Jackson, etc.) need
 * to be visible to JShell's compiler. The harness handles JSON conversion host-side; the sandbox
 * sees only {@code java.*} types plus the {@link ai.singlr.repl.sandbox.HostBridge}.
 *
 * <p>For "simple" types — anything in the {@code java.*} package hierarchy plus primitives —
 * Jackson's default Map values match the casts and the binding succeeds with full static typing.
 * For complex/user-defined types the field is bound as {@code Object} and the model navigates the
 * underlying Map manually.
 *
 * <p>Internal harness-managed variables use a {@code __} prefix to signal "do not depend on this
 * name". Only records are supported as input types; for non-record inputs the harness passes the
 * JSON as the user message and the prompt instructs the model accordingly.
 *
 * <p>Public API: shared between {@link RlmHarness} and {@code CodeActHarness} so both REPL-based
 * harnesses bind their input the same way.
 */
public final class InputBindings {

  private InputBindings() {}

  /** Names of the JShell variables this binding will introduce, in declaration order. */
  public static List<String> boundFieldNames(Class<?> inputType) {
    if (inputType == null || !inputType.isRecord()) {
      return List.of();
    }
    var components = inputType.getRecordComponents();
    if (components.length == 0) {
      return List.of();
    }
    var names = new ArrayList<String>(components.length);
    for (var component : components) {
      names.add(component.getName());
    }
    return Collections.unmodifiableList(names);
  }

  /**
   * Generate the JShell snippet that pre-binds the record components. Returns {@code null} when
   * binding is not applicable (non-record input or empty record), in which case the harness passes
   * the JSON as the user message and the prompt instructs the model to read it from there.
   *
   * @param inputType the user's input record class
   * @return the JShell snippet, or {@code null} if not applicable
   */
  public static String snippet(Class<?> inputType) {
    if (inputType == null || !inputType.isRecord()) {
      return null;
    }
    var components = inputType.getRecordComponents();
    if (components.length == 0) {
      return null;
    }
    var sb = new StringBuilder();
    sb.append("var __input = ai.singlr.repl.sandbox.HostBridge.getInput();\n");
    for (var component : components) {
      var type = component.getGenericType();
      sb.append("var ").append(component.getName()).append(" = ");
      if (isSimpleType(type)) {
        sb.append("(")
            .append(renderTypeAsJavaSource(type))
            .append(") __input.get(\"")
            .append(component.getName())
            .append("\");\n");
      } else {
        sb.append("__input.get(\"").append(component.getName()).append("\");\n");
      }
    }
    return sb.toString();
  }

  /** Components of the input record, or empty if not a record. Used by the prompt builder. */
  public static RecordComponent[] components(Class<?> inputType) {
    if (inputType == null || !inputType.isRecord()) {
      return new RecordComponent[0];
    }
    return inputType.getRecordComponents();
  }

  /**
   * A type is "simple" when every class involved in its definition is in the {@code java.*} package
   * hierarchy (plus primitives) — i.e., types where Jackson's default Map mapping produces a value
   * of exactly that type. For those, a direct JShell cast {@code (T) __input.get(name)} succeeds.
   */
  static boolean isSimpleType(Type type) {
    if (type instanceof Class<?> c) {
      if (c.isPrimitive()) {
        return true;
      }
      var pkg = c.getPackageName();
      return pkg.startsWith("java.");
    }
    if (type instanceof ParameterizedType pt) {
      if (!isSimpleType(pt.getRawType())) {
        return false;
      }
      for (var arg : pt.getActualTypeArguments()) {
        if (!isSimpleType(arg)) {
          return false;
        }
      }
      return true;
    }
    return false;
  }

  /**
   * Render a generic type as a Java source-level type expression suitable for use in a JShell cast
   * or {@code TypeReference}. Primitives are boxed (so {@code int} renders as {@code
   * java.lang.Integer}, since JShell can't cast {@code Object} to a primitive).
   */
  static String renderTypeAsJavaSource(Type type) {
    if (type instanceof Class<?> c) {
      if (c.isPrimitive()) {
        return boxedCanonicalName(c);
      }
      if (c.isArray()) {
        return renderTypeAsJavaSource(c.getComponentType()) + "[]";
      }
      return c.getCanonicalName();
    }
    if (type instanceof ParameterizedType pt) {
      var raw = ((Class<?>) pt.getRawType()).getCanonicalName();
      var args = new StringBuilder();
      for (var arg : pt.getActualTypeArguments()) {
        if (args.length() > 0) {
          args.append(", ");
        }
        args.append(renderTypeAsJavaSource(arg));
      }
      return raw + "<" + args + ">";
    }
    return "Object";
  }

  private static String boxedCanonicalName(Class<?> primitive) {
    if (primitive == int.class) return "java.lang.Integer";
    if (primitive == long.class) return "java.lang.Long";
    if (primitive == double.class) return "java.lang.Double";
    if (primitive == float.class) return "java.lang.Float";
    if (primitive == boolean.class) return "java.lang.Boolean";
    if (primitive == byte.class) return "java.lang.Byte";
    if (primitive == short.class) return "java.lang.Short";
    if (primitive == char.class) return "java.lang.Character";
    throw new IllegalArgumentException("Unknown primitive: " + primitive);
  }
}

package io.github.syucel1.tm.core.model;

import java.util.Objects;

/**
 * One frame of a stack trace.
 *
 * @param className  fully qualified class name, e.g. {@code java.util.HashMap}
 * @param methodName method name, e.g. {@code put}
 * @param descriptor JVM method descriptor, e.g. {@code (Ljava/lang/Object;)V}; may be empty
 * @param lineNumber source line, or {@code -1} when unknown
 * @param type       how the frame executed: {@code Interpreted}, {@code JIT compiled}, {@code Inlined}, {@code Native}
 */
public record Frame(String className, String methodName, String descriptor, int lineNumber, String type) {

    public Frame {
        Objects.requireNonNull(className, "className");
        Objects.requireNonNull(methodName, "methodName");
        descriptor = descriptor == null ? "" : descriptor;
        type = type == null ? "" : type;
    }

    /** {@code className.methodName}, the usual way to name a method in reports. */
    public String qualifiedName() {
        return className + "." + methodName;
    }

    @Override
    public String toString() {
        return lineNumber >= 0 ? qualifiedName() + ":" + lineNumber : qualifiedName();
    }
}

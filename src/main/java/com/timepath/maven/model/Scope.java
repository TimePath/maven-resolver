package com.timepath.maven.model;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.Nullable;

/**
 * TODO: 'import' scope
 *
 * @author TimePath
 */
public enum Scope {
    /**
     * Provided by platform
     */
    PROVIDED,
    /**
     * Testing only
     */
    TEST,
    /**
     * Uses systemPath
     */
    SYSTEM,
    /**
     * Compiled in, definitely required
     */
    COMPILE(true),
    /**
     * Used during execution
     */
    RUNTIME;

    Scope() {
        this(false);
    }

    Scope(boolean transitive) {
        this.transitive = transitive;
    }

    public static Scope from(@NonNls @Nullable String s) {
        if (s != null) {
            Scope scope = valueOf(s.toUpperCase());
            if (scope != null) return scope;
        }
        return COMPILE;
    }

    private final boolean transitive;

    public boolean isTransitive() {
        return transitive;
    }

}

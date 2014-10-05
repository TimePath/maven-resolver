// @checkstyle HeaderCheck (1 line)
package com.timepath.maven.model;

import java.util.Locale;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents package scope.
 *
 * @author TimePath
 * @version $Id$
 */
public enum Scope {
    // @checkstyle TodoCommentCheck (1 line)
    // TODO: 'import' scope
    /**
     * Provided by platform.
     */
    PROVIDED,
    /**
     * Testing only.
     */
    TEST,
    /**
     * Uses systemPath.
     */
    SYSTEM,
    /**
     * Compiled in, definitely required.
     */
    COMPILE(true),
    /**
     * Used during execution.
     */
    RUNTIME;
    /**
     * Whether the scope applies when depending on the package transitively.
     */
    private final boolean transitive;

    /**
     * Convenience.
     */
    Scope() {
        this(false);
    }

    /**
     * Construct a new scope with given transitivity.
     *
     * @param transitivity Whether this scope applies transitively
     */
    Scope(final boolean transitivity) {
        this.transitive = transitivity;
    }

    /**
     * Convert a String to a Scope.
     *
     * @param str The scope string
     * @return The appropriate scope, or {@link #COMPILE}
     */
    @SuppressWarnings({ "PMD.OnlyOneReturn", "PMD.ConfusingTernary" })
    @NotNull
    public static Scope from(@NonNls @Nullable final String str) {
        if (str != null) {
            try {
                return valueOf(str.toUpperCase(Locale.ROOT));
                // @checkstyle EmptyBlockCheck (1 line)
            } catch (final IllegalArgumentException empty) {
            }
        }
        return COMPILE;
    }

    /**
     * Get the transitivity; whether the scope applies when depending on the
     * package transitively.
     *
     * @return The transitivity
     */
    public boolean isTransitive() {
        return this.transitive;
    }

}

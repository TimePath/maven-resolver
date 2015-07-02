package com.timepath.maven.model

import org.jetbrains.annotations.NonNls
import java.util.Locale

/**
 * Represents package scope.
 */
public enum class Scope
/**
 * Construct a new scope with given transitivity.
 *
 * @param isTransitive Whether this scope applies transitively
 */
(
        /** Whether the scope applies when depending on the package transitively. */
        public val isTransitive: Boolean = false
) {
    // TODO: 'import' scope
    /**
     * Provided by platform.
     */
    PROVIDED(),
    /**
     * Testing only.
     */
    TEST(),
    /**
     * Uses systemPath.
     */
    SYSTEM(),
    /**
     * Compiled in, definitely required.
     */
    COMPILE(true),
    /**
     * Used during execution.
     */
    RUNTIME();

    companion object {
        /**
         * Convert a String to a Scope.
         *
         * @param str The scope string
         * @return The appropriate scope, or [COMPILE]
         */
        public fun get(NonNls str: String): Scope = valueOf(str.toUpperCase(Locale.ROOT))
    }

}

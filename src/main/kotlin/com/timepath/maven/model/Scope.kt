package com.timepath.maven.model

import java.util.Locale
import org.jetbrains.annotations.NonNls

/**
 * Represents package scope.
 *
 * @author TimePath
 * @version $Id$
 */
public enum class Scope
/**
 * Construct a new scope with given transitivity.
 *
 * @param transitivity Whether this scope applies transitively
 */
(
        /** Whether the scope applies when depending on the package transitively. */
        public val isTransitive: Boolean = false
) {
    // @checkstyle TodoCommentCheck (1 line)
    // TODO: 'import' scope
    /**
     * Provided by platform.
     */
    PROVIDED : Scope()
    /**
     * Testing only.
     */
    TEST : Scope()
    /**
     * Uses systemPath.
     */
    SYSTEM : Scope()
    /**
     * Compiled in, definitely required.
     */
    COMPILE : Scope(true)
    /**
     * Used during execution.
     */
    RUNTIME : Scope()

    class object {
        /**
         * Convert a String to a Scope.
         *
         * @param str The scope string
         * @return The appropriate scope, or {@link #COMPILE}
         * @checkstyle WhitespaceAroundCheck (2 lines)
         */
        public fun get(NonNls str: String): Scope = valueOf(str.toUpperCase(Locale.ROOT))
    }

}

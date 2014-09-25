package com.timepath.maven.model;

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
    COMPILE {{
        transitive = true;
    }},
    /**
     * Used during execution
     */
    RUNTIME {{
        transitive = true;
    }};

    boolean transitive;

    public static Scope from(String scope) {
        if (scope != null) {
            Scope s = Scope.valueOf(scope.toUpperCase());
            if (s != null) return s;
        }
        return COMPILE;
    }

    public boolean isTransitive() {
        return transitive;
    }

}

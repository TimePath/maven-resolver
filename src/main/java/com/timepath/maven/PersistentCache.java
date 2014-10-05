// @checkstyle HeaderCheck (1 line)
package com.timepath.maven;

import com.timepath.maven.model.Coordinate;
import java.util.concurrent.TimeUnit;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// @checkstyle JavadocTagsCheck (5 lines)

/**
 * Centralised cache class.
 *
 * @author TimePath
 * @version $Id$
 */
public final class PersistentCache {
    /**
     *
     */
    public static final Preferences PREFERENCES = Preferences.userNodeForPackage(MavenResolver.class);
    /**
     *
     */
    @NonNls
    private static final String CACHE_EXPIRES = "expires";
    /**
     *
     */
    @NonNls
    private static final String CACHE_URL = "url";
    /**
     *
     */
    private static final long META_LIFETIME = TimeUnit.DAYS.toMillis(7);
    /**
     *
     */
    private static final Pattern RE_COORD_SPLIT = Pattern.compile("[.:-]");

    /**
     * Private ctor.
     */
    private PersistentCache() {
    }

    /**
     * Check for cached coordinate.
     *
     * @param key The coordinate
     * @return The valid cached value, or null if expired
     * @checkstyle ReturnCountCheck (1 line)
     */
    @Nullable
    public static String get(@NotNull final Coordinate key) {
        final Preferences cached = getNode(key);
        final boolean expired =
                System.currentTimeMillis() >= cached.getLong(CACHE_EXPIRES, 0);
        String ret = null;
        if (!expired) {
            ret = cached.get(CACHE_URL, null);
        }
        return ret;
    }

    /**
     * Drops all cached lookups.
     *
     * @throws java.util.prefs.BackingStoreException If something went wrong
     */
    public static void drop() throws BackingStoreException {
        PREFERENCES.removeNode();
        PREFERENCES.flush();
    }

    /**
     * Save a URL to the persistent cache.
     *
     * @param key The key
     * @param url The URL
     */
    public static void set(@NotNull final Coordinate key,
                           @NotNull final String url) {
        final Preferences cachedNode = getNode(key);
        cachedNode.put(CACHE_URL, url);
        final long future = System.currentTimeMillis() + META_LIFETIME;
        cachedNode.putLong(CACHE_EXPIRES, future);
        try {
            cachedNode.flush();
            // @checkstyle EmptyBlockCheck (1 line)
        } catch (final BackingStoreException ignored) {
        }
    }

    /**
     * Get the {@link java.util.prefs.Preferences} node for a coordinate.
     *
     * @param coordinate The maven coordinate
     * @return The node
     */
    private static Preferences getNode(@NotNull final Coordinate coordinate) {
        Preferences cachedNode = PREFERENCES;
        for (final String nodeName
                : RE_COORD_SPLIT.split(coordinate.toString())) {
            cachedNode = cachedNode.node(nodeName);
        }
        return cachedNode;
    }
}

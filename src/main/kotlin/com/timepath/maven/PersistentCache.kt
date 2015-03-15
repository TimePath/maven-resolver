// @checkstyle HeaderCheck (1 line)
package com.timepath.maven

import com.timepath.maven.model.Coordinate
import java.util.concurrent.TimeUnit
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import java.util.regex.Pattern
import org.jetbrains.annotations.NonNls

// @checkstyle JavadocTagsCheck (5 lines)

/**
 * Centralised cache class.
 *
 * @author TimePath
 * @version $Id$
 */
public object PersistentCache {
    /**
     *
     */
    NonNls
    private val CACHE_EXPIRES = "expires"
    /**
     *
     */
    NonNls
    private val CACHE_URL = "url"
    /**
     *
     */
    private val META_LIFETIME = TimeUnit.DAYS.toMillis(7)
    /**
     *
     */
    private val PREFERENCES = Preferences.userNodeForPackage(javaClass<MavenResolver>())
    /**
     *
     */
    private val RE_COORD_SPLIT = Pattern.compile("[.:-]")

    /**
     * Drops all cached lookups.
     *
     * @throws java.util.prefs.BackingStoreException If something went wrong
     */
    throws(javaClass<BackingStoreException>())
    public fun drop() {
        for (key in PREFERENCES.keys()) {
            PREFERENCES.remove(key)
        }
        for (child in PREFERENCES.childrenNames()) {
            PREFERENCES.node(child).removeNode()
        }
        PREFERENCES.flush()
    }

    /**
     * Check for cached coordinate.
     *
     * @param key The coordinate
     * @return The valid cached value, or null if expired
     * @checkstyle ReturnCountCheck (1 line)
     */
    public fun get(key: Coordinate): String? {
        val cached = getNode(key)
        val expired = System.currentTimeMillis() >= cached.getLong(CACHE_EXPIRES, 0)
        var ret: String? = null
        if (!expired) {
            ret = cached[CACHE_URL, null]
        }
        return ret
    }

    /**
     * Save a URL to the persistent cache.
     *
     * @param key The key
     * @param url The URL
     */
    public fun set(key: Coordinate, url: String) {
        val cachedNode = getNode(key)
        cachedNode.put(CACHE_URL, url)
        val future = System.currentTimeMillis() + META_LIFETIME
        cachedNode.putLong(CACHE_EXPIRES, future)
        try {
            cachedNode.flush()
            // @checkstyle EmptyBlockCheck (1 line)
        } catch (ignored: BackingStoreException) {
        }

    }

    /**
     * Get the {@link java.util.prefs.Preferences} node for a coordinate.
     *
     * @param coordinate The maven coordinate
     * @return The node
     */
    private fun getNode(coordinate: Coordinate): Preferences {
        var cachedNode = PREFERENCES
        for (nodeName in RE_COORD_SPLIT.split(coordinate.toString())) {
            cachedNode = cachedNode.node(nodeName)
        }
        return cachedNode
    }
}

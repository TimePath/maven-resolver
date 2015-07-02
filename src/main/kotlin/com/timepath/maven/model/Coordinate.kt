package com.timepath.maven.model

import org.jetbrains.annotations.NonNls
import java.util.HashMap
import java.util.ResourceBundle
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Represents a set of maven coordinates.
 */
public data class Coordinate
/**
 * Creates a new coordinate object.
 *
 * @param group The group ID
 * @param artifact The artifact ID
 * @param version The version
 * @param classifier The classifier
 */
private constructor(
        /** The group. */
        public NonNls val group: String,
        /** The artifact. */
        public NonNls val artifact: String,
        /** The version. */
        public NonNls val version: String,
        /** The classifier. */
        public NonNls val classifier: String?) {

    companion object {

        /** The cache. */
        private val CACHE: MutableMap<String, Coordinate> = HashMap()
        /** The logger. */
        private val LOG = Logger.getLogger(javaClass<Coordinate>().getName())
        /** The resource bundle. */
        private val RESOURCE_BUNDLE = ResourceBundle.getBundle(javaClass<Coordinate>().getName())

        /**
         * Public constructor.
         *
         * @param group The group ID
         * @param artifact The artifact ID
         * @param version The version
         * @param classifier The classifier
         * @return A reference
         */
        public fun get(group: String, artifact: String, version: String, classifier: String?): Coordinate {
            val str = "$group:$artifact:$version:$classifier"
            synchronized (CACHE) {
                return CACHE.getOrPut(str) {
                    LOG.log(Level.FINE, RESOURCE_BUNDLE.getString("coordinate.new"), str)
                    Coordinate(group, artifact, version, classifier)
                }
            }
        }
    }
}

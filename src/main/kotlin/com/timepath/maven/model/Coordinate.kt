// @checkstyle HeaderCheck (1 line)
package com.timepath.maven.model

import java.util.HashMap
import java.util.ResourceBundle
import java.util.logging.Level
import java.util.logging.Logger
import org.jetbrains.annotations.NonNls

// @checkstyle JavadocTagsCheck (5 lines)

/**
 * Represents a set of maven coordinates.
 *
 * @author TimePath
 * @version $Id$
 */
public class Coordinate
/**
 * Creates a new coordinate object.
 *
 * @param group The group ID
 * @param artifact The artifact ID
 * @param version The version
 * @param classifier The classifier
 * @checkstyle HiddenFieldCheck (6 lines)
 * @checkstyle ParameterNumberCheck (2 lines)
 */
private(
        /**
         * The group.
         */
        NonNls
        public val group: String,
        /**
         * The artifact.
         */
        NonNls
        public val artifact: String,
        /**
         * The version.
         */
        NonNls
        public val version: String,
        /**
         * The classifier.
         */
        NonNls
        public val classifier: String?) {

    SuppressWarnings("PMD.OnlyOneReturn")
    override fun equals(obj: Any?): Boolean {
        if (this == obj) {
            return true
        }
        if ((obj == null) || (this.javaClass != obj.javaClass)) {
            return false
        }
        val other = obj as Coordinate
        return this.toString() == other.toString()
    }

    override fun hashCode(): Int {
        return this.toString().hashCode()
    }

    NonNls
    override fun toString(): String {
        return format(this.group, this.artifact, this.version, this.classifier)
    }

    class object {

        /**
         * The cache.
         */
        SuppressWarnings("PMD.UseConcurrentHashMap")
        private val CACHE: MutableMap<String, Coordinate> = HashMap<String, Coordinate>()
        /**
         * The logger.
         */
        private val LOG: Logger = Logger.getLogger(javaClass<Coordinate>().getName())
        /**
         * The resource bundle.
         */
        private val RESOURCE_BUNDLE: ResourceBundle = ResourceBundle.getBundle(javaClass<Coordinate>().getName())

        /**
         * Public constructor.
         *
         * @param group The group ID
         * @param artifact The artifact ID
         * @param version The version
         * @param classifier The classifier
         * @return A reference
         * @checkstyle ParameterNumberCheck (3 lines)
         */
        SuppressWarnings("PMD.UseObjectForClearerAPI")
        public fun from(group: String, artifact: String, version: String, classifier: String?): Coordinate {
            val str = format(group, artifact, version, classifier)
            synchronized (javaClass<Coordinate>()) {
                var coordinate: Coordinate? = CACHE[str]
                if (coordinate == null) {
                    LOG.log(Level.FINE, RESOURCE_BUNDLE.getString("coordinate.new"), str)
                    coordinate = Coordinate(group, artifact, version, classifier)
                    CACHE.put(str, coordinate!!)
                }
                return coordinate!!
            }
        }

        /**
         * Concatenates maven coordinates.
         *
         * @param group The group ID
         * @param artifact The artifact ID
         * @param version The version
         * @param classifier The classifier
         * @return The joined coordinates
         * @checkstyle ParameterNumberCheck (5 lines)
         */
        SuppressWarnings("PMD.UseObjectForClearerAPI")
        NonNls
        private fun format(group: String, artifact: String, version: String, classifier: String?): String {
            [NonNls] val sep = ':'
            return group + sep + artifact + sep + version + sep + classifier
        }
    }
}
/**
 * Artifact name.
 *
 * @return The artifact
 */
/**
 * Artifact classifier.
 *
 * @return The classifier
 */
/**
 * Artifact group ID.
 *
 * @return The group
 */
/**
 * Artifact version.
 *
 * @return The version
 */

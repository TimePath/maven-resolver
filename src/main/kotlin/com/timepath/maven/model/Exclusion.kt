// @checkstyle HeaderCheck (1 line)
package com.timepath.maven.model

import com.timepath.XMLUtils
import com.timepath.maven.Package
import org.w3c.dom.Node

// @checkstyle JavadocTagsCheck (5 lines)

/**
 * Represents an exclusion.
 *
 * @author TimePath
 * @version $Id$
 */
public class Exclusion
/**
 * Creates a new exclusion object from the given node.
 *
 * @param node The exclusion node
 * @checkstyle HiddenFieldCheck (2 lines)
 */
(
        /**
         * The exclusion node.
         */
        transient private val node: Node) {

    /**
     * Tests a package against this exclusion.
     *
     * @param other The other package
     * @return True if should exclude
     */
    public fun matches(other: Package): Boolean {
        // @checkstyle MethodBodyCommentsCheck (2 lines)
        // @checkstyle TodoCommentCheck (1 line)
        // TODO: Wildcards (which could also be properties)
        val group = XMLUtils.get(this.node, "groupId")
        val artifact = XMLUtils.get(this.node, "artifactId")
        val coordinate = other.coordinate
        return coordinate.group == group && coordinate.artifact == artifact
    }
}

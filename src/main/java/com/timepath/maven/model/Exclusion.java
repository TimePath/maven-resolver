// @checkstyle HeaderCheck (1 line)
package com.timepath.maven.model;

import com.timepath.XMLUtils;
import com.timepath.maven.Package;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Node;

/**
 * Represents an exclusion.
 *
 * @checkstyle JavadocTagsCheck (1 line)
 * @author TimePath
 * @version $Id$
 */
public class Exclusion {

    /**
     * The exclusion node.
     */
    private final transient Node node;

    /**
     * Creates a new exclusion object from the given node.
     *
     * @param node The exclusion node
     */
    @SuppressWarnings("ParameterHidesMemberVariable")
    // @checkstyle HiddenFieldCheck (1 line)
    public Exclusion(final Node node) {
        this.node = node;
    }

    /**
     * Tests a package against this exclusion.
     *
     * @param other The other package
     * @return True if should exclude
     */
    @SuppressWarnings("BooleanMethodNameMustStartWithQuestion")
    public final boolean matches(@NotNull final Package other) {
        // @checkstyle MethodBodyCommentsCheck (2 lines)
        // @checkstyle TodoCommentCheck (1 line)
        // TODO: Wildcards (which could also be properties)
        @Nullable final String group =
                XMLUtils.get(this.node, "groupId");
        @Nullable final String artifact =
                XMLUtils.get(this.node, "artifactId");
        final Coordinate coordinate = other.getCoordinate();
        return coordinate.getGroup().equals(group)
                && coordinate.getArtifact().equals(artifact);
    }
}

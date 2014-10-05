// @checkstyle HeaderCheck (1 line)
package com.timepath.maven.model;

import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents a set of maven coordinates.
 *
 * @checkstyle JavadocTagsCheck (1 line)
 * @author TimePath
 * @version $Id$
 */
public final class Coordinate {

    /**
     * The logger.
     */
    private static final Logger LOG = Logger
            .getLogger(Coordinate.class.getName());
    /**
     * The cache.
     */
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    private static final Map<String, Coordinate> CACHE = new HashMap<>();
    /**
     * The resource bundle.
     */
    private static final ResourceBundle RESOURCE_BUNDLE = ResourceBundle
            .getBundle(Coordinate.class.getName());
    /**
     * The group.
     */
    @NonNls
    @NotNull
    private final String group;
    /**
     * The artifact.
     */
    @NonNls
    @NotNull
    private final String artifact;
    /**
     * The version.
     */
    @NonNls
    @NotNull
    private final String version;
    /**
     * The classifier.
     */
    @NonNls
    @Nullable
    private final String classifier;

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
    private Coordinate(@NotNull final String group,
                       @NotNull final String artifact,
                       @NotNull final String version,
                       @Nullable final String classifier) {
        this.group = group;
        this.artifact = artifact;
        this.version = version;
        this.classifier = classifier;
    }

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
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public static Coordinate from(@NotNull final String group,
                                  @NotNull final String artifact,
                                  @NotNull final String version,
                                  @Nullable final String classifier) {
        final String str = format(group, artifact, version, classifier);
        synchronized (Coordinate.class) {
            Coordinate coordinate = CACHE.get(str);
            if (coordinate == null) {
                LOG.log(
                        Level.FINE, RESOURCE_BUNDLE.getString("coordinate.new"),
                        str
                );
                coordinate = new Coordinate(
                        group, artifact, version, classifier
                );
                CACHE.put(str, coordinate);
            }
            return coordinate;
        }
    }

    @SuppressWarnings("PMD.OnlyOneReturn")
    @Override
    public boolean equals(@Nullable final Object obj) {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (this.getClass() != obj.getClass())) {
            return false;
        }
        final Coordinate other = (Coordinate) obj;
        return this.toString().equals(other.toString());
    }

    @Override
    public int hashCode() {
        return this.toString().hashCode();
    }

    /**
     * Artifact name.
     *
     * @return The artifact
     */
    @NotNull
    public String getArtifact() {
        return this.artifact;
    }

    /**
     * Artifact classifier.
     *
     * @return The classifier
     */
    @Nullable
    public String getClassifier() {
        return this.classifier;
    }

    /**
     * Artifact group ID.
     *
     * @return The group
     */
    @NotNull
    public String getGroup() {
        return this.group;
    }

    /**
     * Artifact version.
     *
     * @return The version
     */
    @NotNull
    public String getVersion() {
        return this.version;
    }

    @NonNls
    @NotNull
    @Override
    public String toString() {
        return format(this.group, this.artifact, this.version, this.classifier);
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
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    @NonNls
    @NotNull
    private static String format(final String group,
                                 final String artifact,
                                 final String version,
                                 @Nullable final String classifier) {
        @NonNls final char sep = ':';
        return group + sep + artifact + sep + version + sep + classifier;
    }
}

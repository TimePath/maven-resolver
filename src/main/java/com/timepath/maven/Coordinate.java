package com.timepath.maven;

import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author TimePath
 */
public final class Coordinate {

    private static final Logger LOG = Logger.getLogger(Coordinate.class.getName());
    private static final Map<String, Coordinate> CACHE = new HashMap<>();
    private static final ResourceBundle RESOURCE_BUNDLE = ResourceBundle.getBundle(Coordinate.class.getName());
    @NonNls
    @NotNull
    public final String groupId;
    @NonNls
    @NotNull
    public final String artifactId;
    @NonNls
    @NotNull
    public final String version;
    @NonNls
    @Nullable
    public final String classifier;

    private Coordinate(@NotNull String groupId, @NotNull String artifactId, @NotNull String version, @Nullable String classifier) {
        this.groupId = groupId;
        this.artifactId = artifactId;
        this.version = version;
        this.classifier = classifier;
    }

    public static synchronized Coordinate from(@NotNull String groupId, @NotNull String artifactId, @NotNull String version, @Nullable String classifier) {
        String s = format(groupId, artifactId, version, classifier);
        Coordinate coordinate = CACHE.get(s);
        if (coordinate == null) {
            LOG.log(Level.FINE, RESOURCE_BUNDLE.getString("coordinate.new"), s);
            coordinate = new Coordinate(groupId, artifactId, version, classifier);
            CACHE.put(s, coordinate);
        }
        return coordinate;
    }

    @NonNls
    @NotNull
    private static String format(String groupId, String artifactId, String version, @Nullable String classifier) {
        @NonNls char c = ':';
        return groupId + c + artifactId + c + version + c + classifier;
    }

    @Override
    public int hashCode() {
        return toString().hashCode();
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if ((obj == null) || (getClass() != obj.getClass())) return false;
        Coordinate other = (Coordinate) obj;
        return toString().equals(other.toString());
    }

    @NonNls
    @NotNull
    @Override
    public String toString() {
        return format(groupId, artifactId, version, classifier);
    }
}

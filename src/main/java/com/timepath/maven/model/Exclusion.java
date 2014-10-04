package com.timepath.maven.model;

import com.timepath.XMLUtils;
import com.timepath.maven.Coordinate;
import com.timepath.maven.Package;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Node;

/**
 * @author TimePath
 */
public class Exclusion {

    private final Node exclusion;

    public Exclusion(Node exclusion) {
        this.exclusion = exclusion;
    }

    @SuppressWarnings("BooleanMethodNameMustStartWithQuestion")
    public boolean matches(@NotNull Package depDownload) {
        // TODO: wildcards
        // TODO: these could be properties
        @Nullable String groupId = XMLUtils.get(exclusion, "groupId");
        @Nullable String artifactId = XMLUtils.get(exclusion, "artifactId");
        Coordinate coordinate = depDownload.coordinate;
        return coordinate.groupId.equals(groupId) && coordinate.artifactId.equals(artifactId);
    }
}

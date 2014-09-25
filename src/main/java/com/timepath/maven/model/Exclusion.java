package com.timepath.maven.model;

import com.timepath.XMLUtils;
import com.timepath.maven.Coordinate;
import com.timepath.maven.Package;
import org.w3c.dom.Node;

/**
 * @author TimePath
 */
public class Exclusion {
    private Node exclusion;

    public Exclusion(Node exclusion) {
        this.exclusion = exclusion;
    }

    public boolean matches(Package depDownload) {
        // TODO: wildcards
        // TODO: these could be properties
        String groupId = XMLUtils.get(exclusion, "groupId");
        String artifactId = XMLUtils.get(exclusion, "artifactId");
        Coordinate c = depDownload.coordinate;
        return groupId.equals(c.groupId) && artifactId.matches(c.artifactId);
    }
}

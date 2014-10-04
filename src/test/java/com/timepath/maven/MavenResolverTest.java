package com.timepath.maven;

import org.junit.Test;

import java.io.FileNotFoundException;

import static org.junit.Assert.assertTrue;

public class MavenResolverTest {

    @Test
    public void testResolve() throws FileNotFoundException {
        String resolved = MavenResolver.resolve(Coordinate.from("com.timepath", "launcher", "1.0-SNAPSHOT", null));
        assertTrue("Failed to resolve", resolved.contains("/com/timepath/launcher/1.0-SNAPSHOT/launcher-1.0-"));
    }
}

// @checkstyle HeaderCheck (1 line)
package com.timepath.maven;

import com.timepath.maven.model.Coordinate;
import java.io.FileNotFoundException;
import java.util.prefs.BackingStoreException;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

/**
 * Test resolver functionality.
 *
 * @checkstyle JavadocTagsCheck (1 line)
 * @author TimePath
 * @version $Id$
 */
public class MavenResolverTest {

    /**
     * Drop the caches to avoid interference between tests.
     * @throws BackingStoreException If fails.
     */
    @BeforeClass
    public static final void init() throws BackingStoreException {
        PersistentCache.drop();
    }

    /**
     * Test resolving coordinates to a URL.
     *
     * @throws FileNotFoundException If resolving fails
     */
    @Test
    public final void testResolve() throws FileNotFoundException {
        final String resolved = MavenResolver.resolve(
                Coordinate
                        .from("com.timepath", "launcher", "1.0-SNAPSHOT", null)
        );
        final String str = "/com/timepath/launcher/1.0-SNAPSHOT/launcher-1.0-";
        Assert.assertTrue("Failed to resolve", resolved.contains(str));
    }
}

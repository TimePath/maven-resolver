package com.timepath.maven;

import com.timepath.maven.model.Coordinate
import org.junit.Assert
import org.junit.BeforeClass
import org.junit.Test
import kotlin.platform.platformStatic

/**
 * Test resolver functionality.
 */
public class MavenResolverTest {

    companion object {
        /**
         * Drop the caches to avoid interference between tests.
         */
        @BeforeClass
        platformStatic fun before(): Unit {
            PersistentCache.drop();
        }
    }

    /**
     * Test resolving coordinates to a URL.
     *
     * @throws FileNotFoundException If resolving fails
     */
    @Test
    fun testResolve() {
        val resolved = MavenResolver.resolve(
                Coordinate["com.timepath", "launcher", "1.0-SNAPSHOT", null]
        )
        val str = "/com/timepath/launcher/1.0-SNAPSHOT/launcher-1.0-";
        Assert.assertTrue("Failed to resolve", resolved.contains(str));
    }
}

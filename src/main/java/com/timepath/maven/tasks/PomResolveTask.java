// @checkstyle HeaderCheck (1 line)
package com.timepath.maven.tasks;

import com.timepath.IOUtils;
import com.timepath.maven.MavenResolver;
import com.timepath.maven.model.Coordinate;
import java.io.FileNotFoundException;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.Nullable;

// @checkstyle JavadocTagsCheck (5 lines)

/**
 * Task for resolving project files.
 *
 * @author TimePath
 * @version $Id$
 */
public class PomResolveTask implements Callable<String> {

    /**
     *
     */
    public static final Logger LOG =
            Logger.getLogger(PomResolveTask.class.getName());

    /**
     *
     */
    public static final ResourceBundle RESOURCE_BUNDLE =
            ResourceBundle.getBundle(MavenResolver.class.getName());

    /**
     *
     */
    private final transient Coordinate key;

    /**
     * Public ctor.
     * @param coordinate The key
     */
    public PomResolveTask(final Coordinate coordinate) {
        this.key = coordinate;
    }

    @Nullable
    @Override
    public final String call() throws FileNotFoundException {
        @Nullable final String resolved = MavenResolver.resolve(this.key, "pom");
        // @checkstyle AvoidInlineConditionalsCheck (1 line)
        @Nullable String pom = null;
        if (resolved != null) {
            pom = IOUtils.requestPage(resolved);
        }
        if (pom == null) {
            LOG.log(
                    Level.WARNING,
                    RESOURCE_BUNDLE.getString("resolve.pom.fail"), this.key
            );
        }
        return pom;
    }
}

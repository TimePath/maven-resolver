// @checkstyle HeaderCheck (1 line)
package com.timepath.maven.tasks;

import com.timepath.XMLUtils;
import com.timepath.maven.Package;
import com.timepath.maven.model.Exclusion;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.w3c.dom.Node;

// @checkstyle JavadocTagsCheck (5 lines)

/**
 * Download resolve task.
 *
 * @author TimePath
 */
public class DownloadResolveTask implements Callable<Set<Package>> {

    /**
     * The logger.
     */
    private static final Logger LOG;

    /**
     *
     */
    private final transient Node data;

    /**
     *
     */
    private final transient Package pkg;

    static {
        LOG = Logger.getLogger(DownloadResolveTask.class.getName());
    }

    /**
     * Public ctor.
     *
     * @param pkg The package
     * @param data A dependency node
     * @checkstyle HiddenFieldCheck (2 lines)
     */
    public DownloadResolveTask(final Package pkg, final Node data) {
        this.pkg = pkg;
        this.data = data;
    }

    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    @NotNull
    @Override
    public final Set<Package> call() throws Exception {
        @NotNull final Set<Package> depDownloads = new HashSet<>();
        try {
            // @checkstyle IndentationCheck (1 line)
        transitives:
            for (@NotNull final Package depDownload : this.pkg.getDownloads()) {
                for (final Node exNode : XMLUtils
                        .getElements(this.data, "exclusions/exclusion")) {
                    @NotNull final Exclusion exclusion = new Exclusion(exNode);
                    if (exclusion.matches(depDownload)) {
                        continue transitives;
                    }
                }
                depDownloads.add(depDownload);
            }
        } catch (final IllegalArgumentException
                | UnsupportedOperationException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
        return depDownloads;
    }
}

// @checkstyle HeaderCheck (1 line)
package com.timepath.maven.tasks;

import com.timepath.IOUtils;
import com.timepath.XMLUtils;
import com.timepath.maven.Constants;
import com.timepath.maven.MavenResolver;
import com.timepath.maven.PersistentCache;
import com.timepath.maven.model.Coordinate;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.text.MessageFormat;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RunnableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.xml.parsers.ParserConfigurationException;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

// @checkstyle JavadocTagsCheck (5 lines)

/**
 * Task for resolving addresses of artifacts.
 *
 * @author TimePath
 * @version $Id$
 */
public class UrlResolveTask implements Callable<String> {

    /**
     *
     */
    private static final Logger LOG;
    /**
     *
     */
    private static final ResourceBundle RESOURCE_BUNDLE;
    /**
     *
     */
    private final transient String classifier;
    /**
     *
     */
    private final transient String fragment;
    /**
     *
     */
    private final transient Coordinate key;

    static {
        LOG = Logger.getLogger(UrlResolveTask.class.getName());
        RESOURCE_BUNDLE =
                ResourceBundle.getBundle(MavenResolver.class.getName());
    }

    /**
     * Create a new URL resolve task.
     *
     * @param key The coordinate
     * @param fragment The fragment to append to the base URL
     * @param classifier The artifact classifier
     * @checkstyle HiddenFieldCheck (4 lines)
     */
    public UrlResolveTask(final Coordinate key,
                          final String fragment,
                          final String classifier) {
        this.key = key;
        this.fragment = fragment;
        this.classifier = classifier;
    }

    /**
     * Wrap a string in a {@link java.util.concurrent.Future}.
     *
     * @param document The document to store
     * @return A future representing that document
     */
    @NotNull
    public static Future<String> makeFuture(final String document) {
        @SuppressWarnings("PMD.DoNotUseThreads")
        @NotNull final RunnableFuture<String> future = new FutureTask<>(
                new Runnable() {
                    @SuppressWarnings("PMD.UncommentedEmptyMethod")
                    @Override
                    public void run() {
                    }
                }, document
        );
        future.run();
        return future;
    }

    @Nullable
    @Override
    public final String call() {
        @Nullable final String url = this.tryAll();
        if (url == null) {
            LOG.log(
                    Level.WARNING,
                    RESOURCE_BUNDLE.getString("resolve.url.fail"), this.key
            );
        } else {
            PersistentCache.set(this.key, url);
        }
        return url;
    }

    /**
     * Resolves a coordinate using a repository.
     *
     * @param repository The repository
     * @return The URL
     */
    @Nullable
    private String resolve(@NonNls final String repository) {
        final String base = repository + this.fragment;
        final boolean snapshot = this.key.getVersion()
                .endsWith(Constants.SUFFIX_SNAPSHOT);
        // @checkstyle AvoidInlineConditionalsCheck (2 lines)
        return snapshot
                ? this.resolveSnapshot(base)
                : this.resolveRelease(base);
    }

    /**
     * Resolve a non-snapshot build.
     *
     * @param base The base URL
     * @return The full URL, or null
     */
    @SuppressWarnings("PMD.OnlyOneReturn")
    @Nullable
    private String resolveRelease(@NonNls final String base) {
        @NonNls final String test = MessageFormat.format(
                "{0}{1}-{2}{3}",
                base,
                this.key.getArtifact(), this.key.getVersion(), this.classifier
        );
        if (!MavenResolver.POM_CACHE.containsKey(this.key)) {
            // @checkstyle MethodBodyCommentsCheck (1 line)
            // Test it with the pom
            final String pom = IOUtils.requestPage(
                    test + Constants.SUFFIX_POM
            );
            if (pom == null) {
                return null;
            }
            // @checkstyle MethodBodyCommentsCheck (1 line)
            // Cache the pom since we already have it
            MavenResolver.POM_CACHE.put(this.key, makeFuture(pom));
        }
        return test;
    }

    /**
     * Resolve a snapshot build.
     *
     * @param base The base URL
     * @return The full URL, or null
     * @checkstyle WhitespaceAroundCheck (3 lines)
     * @checkstyle ReturnCountCheck (2 lines)
     */
    @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.NPathComplexity"})
    @Nullable
    private String resolveSnapshot(@NonNls final String base) {
        try {
            if (base.startsWith("file:")) {
                // @checkstyle MethodBodyCommentsCheck (2 lines)
                // @checkstyle TodoCommentCheck (1 line)
                // TODO: Handle metadata when using REPO_LOCAL
                return null;
            }
            final Node metadata = XMLUtils.rootNode(
                    // @checkstyle StringLiteralsConcatenationCheck (1 line)
                    IOUtils.openStream(base + "maven-metadata.xml"),
                    "metadata"
            );
            // @checkstyle LineLengthCheck (1 line)
            @Nullable final Node snapshot = XMLUtils.last(XMLUtils.getElements(metadata, "versioning/snapshot"));
            if (snapshot == null) {
                return null;
            }
            @Nullable final String timestamp =
                    XMLUtils.get(snapshot, "timestamp");
            @Nullable final String buildNumber =
                    XMLUtils.get(snapshot, "buildNumber");
            final String version = this.key.getVersion();
            @NonNls final String versionNumber = version.substring(
                    0, version.lastIndexOf(Constants.SUFFIX_SNAPSHOT)
            );
            // @checkstyle AvoidInlineConditionalsCheck (2 lines)
            @NotNull final String versionSuffix = (buildNumber == null)
                    ? Constants.SUFFIX_SNAPSHOT
                    : "";
            return MessageFormat.format(
                    "{0}{1}-{2}{3}{4}{5}", base,
                    this.key.getArtifact(),
                    versionNumber + versionSuffix,
                    // @checkstyle AvoidInlineConditionalsCheck (2 lines)
                    (timestamp == null) ? "" : ('-' + timestamp),
                    (buildNumber == null) ? "" : ('-' + buildNumber),
                    this.classifier
            );
        } catch (final FileNotFoundException ignored) {
            LOG.log(
                    Level.WARNING,
                    RESOURCE_BUNDLE.getString("resolve.pom.fail.version"),
                    new Object[]{this.key, base}
            );
        } catch (final IOException
                | ParserConfigurationException
                | SAXException ex) {
            final String msg = MessageFormat.format(
                    RESOURCE_BUNDLE.getString("resolve.pom.fail"),
                    this.key
            );
            LOG.log(Level.WARNING, msg, ex);
        }
        return null;
    }

    /**
     * Try all repositories.
     *
     * @return A URL, or null
     */
    @Nullable
    private String tryAll() {
        String url = null;
        for (@NonNls @NotNull final String repository
                : MavenResolver.getRepositories()) {
            url = this.resolve(repository);
            if (url != null) {
                break;
            }
        }
        return url;
    }
}

// @checkstyle HeaderCheck (1 line)
package com.timepath.maven;

import com.timepath.IOUtils;
import com.timepath.Utils;
import com.timepath.maven.model.Coordinate;
import com.timepath.maven.tasks.UrlResolveTask;
import com.timepath.util.Cache;
import com.timepath.util.concurrent.DaemonThreadFactory;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.ResourceBundle;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

// @checkstyle LineLengthCheck (500 lines)

// @checkstyle JavadocTagsCheck (5 lines)

/**
 * Utility class for resolving coordinates to urls.
 *
 * @author TimePath
 * @version $Id$
 * @checkstyle ClassDataAbstractionCouplingCheck (2 lines)
 */
@SuppressWarnings("PMD")
public final class MavenResolver {

    /**
     * Cache of coordinates to pom documents.
     *
     * @checkstyle AnonInnerLengthCheck (2 lines)
     */
    public static final Map<Coordinate, Future<String>> POM_CACHE = new PomCache();
    /**
     *
     */
    @NonNls
    public static final String SUFFIX_POM = ".pom";
    /**
     *
     */
    @NonNls
    public static final String SUFFIX_SNAPSHOT = "-SNAPSHOT";
    /**
     *
     */
    public static final ExecutorService THREAD_POOL = Executors.newCachedThreadPool(new DaemonThreadFactory());
    /**
     *
     */
    private static final Logger LOG;
    /**
     *
     */
    @NotNull
    private static final Collection<String> REPOSITORIES;
    /**
     *
     */
    private static final ResourceBundle RESOURCE_BUNDLE;
    /**
     *
     */
    private static final Pattern RE_TRAILING_SLASH;

    /**
     * Cache of coordinates to base urls.
     *
     * @checkstyle AnonInnerLengthCheck (4 lines)
     */
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    @NonNls
    private static final Map<Coordinate, Future<String>> URL_CACHE = new UrlCache();

    static {
        final String name = MavenResolver.class.getName();
        RESOURCE_BUNDLE = ResourceBundle.getBundle(name);
        LOG = Logger.getLogger(name);
        RE_TRAILING_SLASH = Pattern.compile("/$");
        REPOSITORIES = new LinkedHashSet<>();
        addRepository("http://oss.jfrog.org/oss-snapshot-local");
        addRepository("http://repo.maven.apache.org/maven2");
        addRepository("http://repository.jetbrains.com/all");
        addRepository("https://dl.dropboxusercontent.com/u/42745598/maven2");
    }

    /**
     *
     */
    private MavenResolver() {
    }

    /**
     * Adds a repository.
     *
     * @param url The URL
     */
    public static void addRepository(@NonNls @NotNull final CharSequence url) {
        REPOSITORIES.add(sanitize(url));
    }

    /**
     * Get the local repository location.
     *
     * @return The location
     */
    public static String getLocal() {
        final String local = new File(Utils.currentFile(MavenResolver.class).getParentFile(), "bin").getPath();
        // @checkstyle MethodBodyCommentsCheck (1 line)
//        local = System.getProperty(key, new File(System.getProperty("user.home"), ".m2/repository").getPath());
        return sanitize(local);
    }

    /**
     * Iterate over repositories. To allow for changes at runtime, the local repository is not cached
     *
     * @return The list of repositories ordered by priority
     */
    public static Iterable<String> getRepositories() {
        final Collection<String> repositories =
                new LinkedHashSet<>(1 + REPOSITORIES.size());
        try {
            repositories.add(
                    new File(getLocal()).toURI().toURL().toExternalForm()
            );
        } catch (final MalformedURLException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
        repositories.addAll(REPOSITORIES);
        return Collections.unmodifiableCollection(repositories);
    }

    /**
     * Resolve an artifact with packaging.
     *
     * @param coordinate The maven coordinate
     * @param packaging The packaging
     * @return The artifact URL ready for download
     * @throws java.io.FileNotFoundException if unresolvable
     */
    @NonNls
    @Nullable
    public static String resolve(final Coordinate coordinate, @NonNls final String packaging) throws FileNotFoundException {
        return resolve(coordinate) + '.' + packaging;
    }

    /**
     * Resolve a coordinate to a URL.
     *
     * @param coordinate The maven coordinate
     * @return The absolute basename of the project coordinate (without the packaging element)
     * @throws java.io.FileNotFoundException if unresolvable
     */
    @NotNull
    public static String resolve(final Coordinate coordinate) throws FileNotFoundException {
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolve.url"), coordinate);
        try {
            final Future<String> future = URL_CACHE.get(coordinate);
            if (future != null) {
                final String base = future.get();
                if (base != null) {
                    return base;
                }
            }
        } catch (final InterruptedException | ExecutionException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
        final String msg = MessageFormat.format(
                RESOURCE_BUNDLE.getString("resolve.url.fail"),
                coordinate
        );
        throw new FileNotFoundException(msg);
    }

    /**
     * Resolve a pom to an InputStream.
     *
     * @param coordinate The maven coordinate
     * @return An InputStream for the given coordinates
     */
    @Nullable
    public static InputStream resolvePomStream(final Coordinate coordinate) {
        try {
            final byte[] bytes = resolvePom(coordinate);
            if (bytes.length != 0) {
                return new BufferedInputStream(new ByteArrayInputStream(bytes));
            }
        } catch (final ExecutionException | InterruptedException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
        return null;
    }

    /**
     * Download a pom.
     *
     * @param coordinate The maven coordinate
     * @return POM as byte[], could be empty
     * @throws ExecutionException Never
     * @throws InterruptedException Never
     * @checkstyle ThrowsCountCheck (3 lines)
     */
    @NotNull
    private static byte[] resolvePom(final Coordinate coordinate) throws ExecutionException, InterruptedException {
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolve.pom"), coordinate);
        final String pom = POM_CACHE.get(coordinate).get();
        // @checkstyle AvoidInlineConditionalsCheck (1 line)
        return (pom != null) ? pom.getBytes(StandardCharsets.UTF_8) : new byte[0];
    }

    /**
     * Sanitizes a repository URL.
     * <ul>
     * <li>Drops trailing slash</li>
     * </ul>
     *
     * @param url The URL
     * @return The sanitized URL
     */
    private static String sanitize(@NotNull final CharSequence url) {
        return RE_TRAILING_SLASH.matcher(url).replaceAll("");
    }

    private static class PomCache extends Cache<Coordinate, Future<String>> {
        @NotNull
        @Override
        protected Future<String> fill(final Coordinate key) {
            LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolve.pom.miss"), key);
            return THREAD_POOL.submit(
                    new Callable<String>() {
                        @Nullable
                        @Override
                        public String call() throws FileNotFoundException {
                            final String resolved = resolve(key, "pom");
                            // @checkstyle AvoidInlineConditionalsCheck (1 line)
                            final String pom = (resolved != null) ? IOUtils.requestPage(resolved) : null;
                            if (pom == null) {
                                LOG.log(Level.WARNING, RESOURCE_BUNDLE.getString("resolve.pom.fail"), key);
                            }
                            return pom;
                        }
                    });
        }
    }

    private static class UrlCache extends Cache<Coordinate, Future<String>> {
        @Nullable
        @Override
        protected Future<String> expire(@NotNull final Coordinate key, @Nullable final Future<String> value) {
            Future<String> ret = value;
            if (value == null) {
                final String str = PersistentCache.get(key);
                if (str != null) {
                    ret = UrlResolveTask.makeFuture(str);
                }
            }
            return ret;
        }

        @NotNull
        @Override
        protected Future<String> fill(@NotNull final Coordinate key) {
            LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolve.url.miss"), key);
            @SuppressWarnings("HardcodedFileSeparator") final char sep = '/';
            final String str = sep + key.getGroup().replace('.', sep) + sep + key.getArtifact() + sep + key.getVersion() + sep;
            String classifier = key.getClassifier();
            final boolean declassified = classifier == null || classifier.isEmpty();
            // @checkstyle AvoidInlineConditionalsCheck (1 line)
            classifier = declassified ? "" : ('-' + classifier);
            return THREAD_POOL.submit(new UrlResolveTask(key, str, classifier));
        }
    }
}

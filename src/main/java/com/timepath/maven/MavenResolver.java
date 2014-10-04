package com.timepath.maven;

import com.timepath.IOUtils;
import com.timepath.Utils;
import com.timepath.XMLUtils;
import com.timepath.util.Cache;
import com.timepath.util.concurrent.DaemonThreadFactory;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.BackingStoreException;
import java.util.prefs.Preferences;
import java.util.regex.Pattern;

/**
 * @author TimePath
 */
public class MavenResolver {

    public static final File CURRENT_FILE = Utils.currentFile(MavenResolver.class);
    public static final ExecutorService THREAD_POOL = Executors.newCachedThreadPool(new DaemonThreadFactory());
    public static final Preferences PREFERENCES = Preferences.userNodeForPackage(MavenResolver.class);
    @NonNls
    public static final String SUFFIX_SNAPSHOT = "-SNAPSHOT";
    @NonNls
    public static final String SUFFIX_POM = ".pom";
    @NonNls
    public static final String CACHE_URL = "url";
    @NonNls
    public static final String CACHE_EXPIRES = "expires";
    /**
     * Cache of coordinates to base urls
     */
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    @NonNls
    private static final Map<Coordinate, Future<String>> URL_CACHE = new Cache<Coordinate, Future<String>>() {
        @NotNull
        @Override
        protected Future<String> fill(@NotNull final Coordinate key) {
            LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolve.url.miss"), key);
            @SuppressWarnings("HardcodedFileSeparator") char c = '/';
            final String s = c + key.groupId.replace('.', c) + c + key.artifactId + c + key.version + c;
            final String classifier = ((key.classifier == null) || key.classifier.isEmpty()) ? "" : ('-' + key.classifier);
            return THREAD_POOL.submit(new Callable<String>() {
                @Nullable
                @Override
                public String call() {
                    @Nullable String url = null;
                    for (@NonNls @NotNull String repository : getRepositories()) {
                        url = resolve(repository);
                        if (url != null) break;
                    }
                    if (url != null) {
                        persist(key, url); // Update persistent cache
                    } else {
                        LOG.log(Level.WARNING, RESOURCE_BUNDLE.getString("resolve.url.fail"), key);
                    }
                    return url;
                }

                @Nullable
                private String resolve(@NonNls String repository) {
                    String base = repository + s;
                    // TODO: Check version ranges at `new URL(baseArtifact + "maven-metadata.xml")`
                    boolean snapshot = key.version.endsWith(SUFFIX_SNAPSHOT);
                    return snapshot ? resolveSnapshot(base) : resolveRelease(base);
                }

                @Nullable
                private String resolveSnapshot(@NonNls String base) {
                    try {
                        if (base.startsWith("file:"))
                            return null; // TODO: Handle metadata when using REPO_LOCAL
                        Node metadata = XMLUtils.rootNode(IOUtils.openStream(base + "maven-metadata.xml"), "metadata");
                        @Nullable Node snapshot = XMLUtils.last(XMLUtils.getElements(metadata, "versioning/snapshot"));
                        if (snapshot == null) return null;
                        @Nullable String timestamp = XMLUtils.get(snapshot, "timestamp");
                        @Nullable String buildNumber = XMLUtils.get(snapshot, "buildNumber");
                        @NonNls String versionNumber = key.version.substring(0, key.version.lastIndexOf(SUFFIX_SNAPSHOT));
                        @NotNull String versionSuffix = (buildNumber == null) ? SUFFIX_SNAPSHOT : "";
                        return MessageFormat.format("{0}{1}-{2}{3}{4}{5}",
                                base,
                                key.artifactId,
                                versionNumber + versionSuffix,
                                (timestamp == null) ? "" : ('-' + timestamp),
                                (buildNumber == null) ? "" : ('-' + buildNumber),
                                classifier);
                    } catch (FileNotFoundException ignored) {
                        LOG.log(Level.WARNING, RESOURCE_BUNDLE.getString("resolve.pom.fail.version"), new Object[]{key, base});
                    } catch (IOException | ParserConfigurationException | SAXException e) {
                        LOG.log(Level.WARNING, MessageFormat.format(RESOURCE_BUNDLE.getString("resolve.pom.fail"), key), e);
                    }
                    return null;
                }

                @Nullable
                private String resolveRelease(@NonNls String base) {
                    @NonNls String test = MessageFormat.format("{0}{1}-{2}{3}",
                            base,
                            key.artifactId,
                            key.version,
                            classifier);
                    if (!POM_CACHE.containsKey(key)) { // Test it with the pom
                        String pom = IOUtils.requestPage(test + SUFFIX_POM);
                        if (pom == null) return null;
                        // Cache the pom since we already have it
                        POM_CACHE.put(key, makeFuture(pom));
                    }
                    return test;
                }
            });
        }

        @Nullable
        @Override
        protected Future<String> expire(@NotNull Coordinate key, @Nullable Future<String> value) {
            Preferences cached = getCached(key);
            boolean expired = System.currentTimeMillis() >= cached.getLong(CACHE_EXPIRES, 0);
            if (expired) return null;
            if (value == null) {
                // Chance to initialize
                String url = cached.get(CACHE_URL, null);
                if (url != null) return makeFuture(url);
            }
            return value;
        }
    };
    @NonNls
    public static final String MAVEN_REPO_LOCAL = "maven.repo.local";
    private static final ResourceBundle RESOURCE_BUNDLE = ResourceBundle.getBundle(MavenResolver.class.getName());
    @NotNull
    private static final Collection<String> REPOSITORIES;
    private static final Pattern RE_VERSION = Pattern.compile("(\\d*)\\.(\\d*)\\.(\\d*)");
    private static final Logger LOG = Logger.getLogger(MavenResolver.class.getName());
    private static final long META_LIFETIME = TimeUnit.DAYS.toMillis(7);
    private static final Pattern RE_TRAILING_SLASH = Pattern.compile("/$");
    private static final Pattern RE_COORDINATE_SEPARATORS = Pattern.compile("[.:-]");

    static {
        REPOSITORIES = new LinkedHashSet<>(4);
        addRepository("http://oss.jfrog.org/oss-snapshot-local");
        addRepository("http://repo.maven.apache.org/maven2");
        addRepository("http://repository.jetbrains.com/all");
        addRepository("https://dl.dropboxusercontent.com/u/42745598/maven2");
    }

    /**
     * Cache of coordinates to pom documents
     */
    private static final Map<Coordinate, Future<String>> POM_CACHE = new Cache<Coordinate, Future<String>>() {
        @NotNull
        @Override
        protected Future<String> fill(final Coordinate key) {
            LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolve.pom.miss"), key);
            return THREAD_POOL.submit(new Callable<String>() {
                @Nullable
                @Override
                public String call() throws FileNotFoundException {
                    String resolved = resolve(key, "pom");
                    String pom = (resolved != null) ? IOUtils.requestPage(resolved) : null;
                    if (pom == null) LOG.log(Level.WARNING, RESOURCE_BUNDLE.getString("resolve.pom.fail"), key);
                    return pom;
                }
            });
        }
    };

    private MavenResolver() {
    }

    @NotNull
    private static Future<String> makeFuture(String s) {
        @NotNull RunnableFuture<String> future = new FutureTask<>(new Runnable() {
            @Override
            public void run() {
            }
        }, s);
        future.run();
        return future;
    }

    private static void persist(@NotNull Coordinate key, String url) {
        Preferences cachedNode = getCached(key);
        cachedNode.put(CACHE_URL, url);
        cachedNode.putLong(CACHE_EXPIRES, System.currentTimeMillis() + META_LIFETIME);
        try {
            cachedNode.flush();
        } catch (BackingStoreException ignored) {
        }
    }

    private static Preferences getCached(@NotNull Coordinate coordinate) {
        Preferences cachedNode = PREFERENCES;
        for (String nodeName : RE_COORDINATE_SEPARATORS.split(coordinate.toString())) {
            cachedNode = cachedNode.node(nodeName);
        }
        return cachedNode;
    }

    public static void invalidateCaches() throws BackingStoreException {
        PREFERENCES.removeNode();
        PREFERENCES.flush();
    }

    /**
     * Adds a repository
     *
     * @param url the URL
     */
    public static void addRepository(@NonNls @NotNull CharSequence url) {
        REPOSITORIES.add(sanitize(url));
    }

    /**
     * Sanitizes a repository URL:
     * <ul>
     * <li>Drops trailing slash</li>
     * </ul>
     *
     * @param url the URL
     * @return the sanitized URL
     */
    private static String sanitize(@NotNull CharSequence url) {
        return RE_TRAILING_SLASH.matcher(url).replaceAll("");
    }

    /**
     * Resolves a pom to an InputStream
     *
     * @param coordinate the maven coordinate
     * @return an InputStream for the given coordinates
     * @throws java.net.MalformedURLException
     */
    @Nullable
    public static InputStream resolvePomStream(Coordinate coordinate) throws MalformedURLException {
        try {
            @Nullable byte[] bytes = resolvePom(coordinate);
            if (bytes != null) return new BufferedInputStream(new ByteArrayInputStream(bytes));
        } catch (@NotNull ExecutionException | InterruptedException e) {
            LOG.log(Level.SEVERE, null, e);
        }
        return null;
    }

    @Nullable
    private static byte[] resolvePom(Coordinate coordinate) throws ExecutionException, InterruptedException {
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolve.pom"), coordinate);
        String pom = POM_CACHE.get(coordinate).get();
        return (pom != null) ? pom.getBytes(StandardCharsets.UTF_8) : null;
    }

    /**
     * Resolve an artifact with packaging
     *
     * @param coordinate the maven coordinate
     * @param packaging  the packaging
     * @return the artifact URL ready for download
     * @throws java.io.FileNotFoundException if unresolvable
     */
    @NonNls
    @Nullable
    public static String resolve(Coordinate coordinate, @NonNls String packaging) throws FileNotFoundException {
        String resolved = resolve(coordinate);
        if (resolved == null) return null;
        return resolved + '.' + packaging;
    }

    /**
     * @param coordinate the maven coordinate
     * @return the absolute basename of the project coordinate (without the packaging element)
     * @throws java.io.FileNotFoundException if unresolvable
     */
    public static String resolve(Coordinate coordinate) throws FileNotFoundException {
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolve.url"), coordinate);
        try {
            Future<String> future = URL_CACHE.get(coordinate);
            if (future != null) {
                String base = future.get();
                if (base != null) return base;
            }
        } catch (InterruptedException | ExecutionException e) {
            LOG.log(Level.SEVERE, null, e);
        }
        throw new FileNotFoundException(MessageFormat.format(RESOURCE_BUNDLE.getString("resolve.url.fail"), coordinate));
    }

    /**
     * @return the list of repositories ordered by priority
     */
    private static Iterable<String> getRepositories() {
        LinkedHashSet<String> repositories = new LinkedHashSet<>(1 + REPOSITORIES.size());
        // To allow for changes at runtime, the local repository is not cached
        try {
            repositories.add(new File(getLocal()).toURI().toURL().toExternalForm());
        } catch (MalformedURLException e) {
            LOG.log(Level.SEVERE, null, e);
        }
        repositories.addAll(REPOSITORIES);
        return Collections.unmodifiableCollection(repositories);
    }

    /**
     * @return the local repository location
     */
    public static String getLocal() {
        String local = PREFERENCES.get(MAVEN_REPO_LOCAL, new File(CURRENT_FILE.getParentFile(), "bin").getPath());
//        local = System.getProperty(key, new File(System.getProperty("user.home"), ".m2/repository").getPath());
        return sanitize(local);
    }
}

package com.timepath.maven;

import com.timepath.IOUtils;
import com.timepath.Utils;
import com.timepath.XMLUtils;
import com.timepath.util.Cache;
import com.timepath.util.concurrent.DaemonThreadFactory;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import java.io.*;
import java.net.MalformedURLException;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
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

    public static final Preferences SETTINGS = Preferences.userRoot().node("timepath");
    public static final File CURRENT_FILE = Utils.currentFile(MavenResolver.class);
    @NotNull
    private static final Collection<String> REPOSITORIES;
    private static final String REPO_CENTRAL = "http://repo.maven.apache.org/maven2";
    private static final String REPO_JFROG_SNAPSHOTS = "http://oss.jfrog.org/oss-snapshot-local";
    private static final String REPO_CUSTOM = "https://dl.dropboxusercontent.com/u/42745598/maven2";
    private static final String REPO_JETBRAINS = "http://repository.jetbrains.com/all";
    private static final Pattern RE_VERSION = Pattern.compile("(\\d*)\\.(\\d*)\\.(\\d*)");
    private static final Logger LOG = Logger.getLogger(MavenResolver.class.getName());
    private static final long META_LIFETIME = 7 * 24 * 60 * 60 * 1000; // 1 week

    static {
        REPOSITORIES = new LinkedHashSet<>();
        addRepository(REPO_JFROG_SNAPSHOTS);
        addRepository(REPO_CENTRAL);
        addRepository(REPO_JETBRAINS);
        addRepository(REPO_CUSTOM);
    }

    /**
     * Cache of coordinates to base urls
     */
    @Nullable
    @SuppressWarnings("MismatchedQueryAndUpdateOfCollection") // Happens behind the scenes
    private static final Cache<Coordinate, Future<String>> URL_CACHE = new Cache<Coordinate, Future<String>>() {
        @NotNull
        @Override
        protected Future<String> fill(@NotNull final Coordinate key) {
            LOG.log(Level.INFO, "Resolving baseURL (missed): {0}", key);
            @NotNull final String s = '/' + key.groupId.replace('.', '/') + '/' + key.artifactId + '/' + key.version + '/';
            @NotNull final String classifier = (key.classifier == null || key.classifier.isEmpty()) ? "" : '-' + key.classifier;
            return THREAD_POOL.submit(new Callable<String>() {
                @Nullable
                @Override
                public String call() throws Exception {
                    @Nullable String url = null;
                    for (@NotNull String repository : getRepositories()) {
                        @NotNull String base = repository + s;
                        // TODO: Check version ranges at `new URL(baseArtifact + "maven-metadata.xml")`
                        if (key.version.endsWith("-SNAPSHOT")) {
                            try {
                                if (repository.startsWith("file:"))
                                    continue; // TODO: Handle metadata when using REPO_LOCAL
                                Node metadata = XMLUtils.rootNode(IOUtils.openStream(base + "maven-metadata.xml"), "metadata");
                                @Nullable Node snapshot = XMLUtils.last(XMLUtils.getElements(metadata, "versioning/snapshot"));
                                @Nullable String timestamp = XMLUtils.get(snapshot, "timestamp");
                                @Nullable String buildNumber = XMLUtils.get(snapshot, "buildNumber");
                                @NotNull String versionNumber = key.version.substring(0, key.version.lastIndexOf("-SNAPSHOT"));
                                @NotNull String versionSuffix = (buildNumber == null) ? "-SNAPSHOT" : "";
                                //noinspection ConstantConditions
                                url = MessageFormat.format("{0}{1}-{2}{3}{4}{5}",
                                        base,
                                        key.artifactId,
                                        versionNumber + versionSuffix,
                                        (timestamp == null) ? "" : ("-" + timestamp),
                                        (buildNumber == null) ? "" : ("-" + buildNumber),
                                        classifier);
                            } catch (@NotNull IOException | ParserConfigurationException | SAXException e) {
                                if (e instanceof FileNotFoundException) {
                                    LOG.log(Level.WARNING,
                                            "Metadata not found for {0} in {1}",
                                            new Object[]{key, repository});
                                } else {
                                    LOG.log(Level.WARNING, "Unable to resolve " + key, e);
                                }
                            }
                        } else { // Simple string manipulation with a test
                            @NotNull String test = MessageFormat.format("{0}{1}-{2}{3}",
                                    base,
                                    key.artifactId,
                                    key.version,
                                    classifier);
                            if (!POM_CACHE.containsKey(key)) { // Test it with the pom
                                @Nullable String pom = IOUtils.requestPage(test + ".pom");
                                if (pom == null) continue;
                                // May as well cache the pom while we have it
                                POM_CACHE.put(key, makeFuture(pom));
                            }
                            url = test;
                        }
                        if (url != null) break;
                    }
                    if (url != null) {
                        persist(key, url); // Update persistent cache
                    } else {
                        LOG.log(Level.WARNING, "Resolving baseURL (failed): {0}", key);
                    }
                    return url;
                }
            });
        }

        @Nullable
        @Override
        protected Future<String> expire(@NotNull Coordinate key, @Nullable Future<String> value) {
            Preferences cached = getCached(key);
            boolean expired = System.currentTimeMillis() >= cached.getLong("expires", 0);
            if (expired) return null;
            if (value == null) {
                // Chance to initialize
                String url = cached.get("url", null);
                if (url != null) return makeFuture(url);
            }
            return value;
        }
    };
    public static final ExecutorService THREAD_POOL = Executors.newCachedThreadPool(new DaemonThreadFactory());
    /**
     * Cache of coordinates to pom documents
     */
    private static final Map<Coordinate, Future<String>> POM_CACHE = new Cache<Coordinate, Future<String>>() {
        @NotNull
        @Override
        protected Future<String> fill(final Coordinate key) {
            LOG.log(Level.INFO, "Resolving POM (missed): {0}", key);
            return THREAD_POOL.submit(new Callable<String>() {
                @Nullable
                @Override
                public String call() throws Exception {
                    @Nullable String pom = IOUtils.requestPage(resolve(key, "pom"));
                    if (pom == null) LOG.log(Level.WARNING, "Resolving POM (failed): {0}", key);
                    return pom;
                }
            });
        }
    };
    public static final Preferences PREFERENCES = Preferences.userNodeForPackage(MavenResolver.class);

    private MavenResolver() {
    }

    @NotNull
    private static FutureTask<String> makeFuture(String s) {
        @NotNull FutureTask<String> future = new FutureTask<>(new Runnable() {
            @Override
            public void run() {
            }
        }, s);
        future.run();
        return future;
    }

    private static void persist(@NotNull Coordinate key, String url) {
        Preferences cachedNode = getCached(key);
        cachedNode.put("url", url);
        cachedNode.putLong("expires", System.currentTimeMillis() + META_LIFETIME);
        try {
            cachedNode.flush();
        } catch (BackingStoreException ignored) {
        }
    }

    private static Preferences getCached(@NotNull Coordinate c) {
        Preferences cachedNode = PREFERENCES;
        for (String nodeName : c.toString().replaceAll("[.:-]", "/").split("/")) {
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
    public static void addRepository(@NotNull String url) {
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
    private static String sanitize(@NotNull String url) {
        return url.replaceAll("/$", "");
    }

    /**
     * Resolves a pom to an InputStream
     *
     * @param c the maven coordinate
     * @return an InputStream for the given coordinates
     * @throws MalformedURLException
     */
    @Nullable
    public static InputStream resolvePomStream(Coordinate c) throws MalformedURLException {
        try {
            @Nullable byte[] bytes = resolvePom(c);
            if (bytes != null) return new BufferedInputStream(new ByteArrayInputStream(bytes));
        } catch (@NotNull ExecutionException | InterruptedException e) {
            LOG.log(Level.SEVERE, null, e);
        }
        return null;
    }

    @Nullable
    private static byte[] resolvePom(Coordinate c) throws MalformedURLException, ExecutionException, InterruptedException {
        LOG.log(Level.INFO, "Resolving POM: {0}", c);
        String pom = POM_CACHE.get(c).get();
        if (pom != null) return pom.getBytes(StandardCharsets.UTF_8);
        return null;
    }

    /**
     * Resolve an artifact with packaging
     *
     * @param c         the maven coordinate
     * @param packaging the packaging
     * @return the artifact URL ready for download
     * @throws FileNotFoundException if unresolvable
     */
    @Nullable
    public static String resolve(Coordinate c, String packaging) throws FileNotFoundException {
        String resolved = resolve(c);
        if (resolved == null) return null;
        return resolved + '.' + packaging;
    }

    /**
     * @param c the maven coordinate
     * @return the absolute basename of the project coordinate (without the packaging element)
     * @throws FileNotFoundException if unresolvable
     */
    public static String resolve(Coordinate c) throws FileNotFoundException {
        LOG.log(Level.INFO, "Resolving baseURL: {0}", c);
        try {
            @Nullable Future<String> future = URL_CACHE.get(c);
            String base = future.get();
            if (base != null) return base;
        } catch (@NotNull InterruptedException | ExecutionException e) {
            LOG.log(Level.SEVERE, null, e);
        }
        throw new FileNotFoundException("Could not resolve " + c);
    }

    /**
     * @return the list of repositories ordered by priority
     */
    private static Collection<String> getRepositories() {
        @NotNull LinkedHashSet<String> repositories = new LinkedHashSet<>();
        // To allow for changes at runtime, the local repository is not cached
        try {
            repositories.add(new File(getLocal()).toURI().toURL().toExternalForm());
        } catch (MalformedURLException e) {
            LOG.log(Level.SEVERE, null, e);
        }
        repositories.addAll(MavenResolver.REPOSITORIES);
        return Collections.unmodifiableCollection(repositories);
    }

    /**
     * @return the local repository location
     */
    public static String getLocal() {
        String local;
        local = SETTINGS.get("progStoreDir", new File(CURRENT_FILE.getParentFile(), "bin").getPath());
        // local = System.getProperty("maven.repo.local", System.getProperty("user.home") + "/.m2/repository");
        return sanitize(local);
    }
}

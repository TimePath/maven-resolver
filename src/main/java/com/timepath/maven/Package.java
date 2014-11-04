// @checkstyle HeaderCheck (1 line)
package com.timepath.maven;

import com.timepath.FileUtils;
import com.timepath.IOUtils;
import com.timepath.XMLUtils;
import com.timepath.maven.model.Coordinate;
import com.timepath.maven.model.Scope;
import com.timepath.maven.tasks.DownloadResolveTask;
import com.timepath.util.Cache;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.text.MessageFormat;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

// @checkstyle LineLengthCheck (500 lines)
// @checkstyle DesignForExtensionCheck (500 lines)
// @checkstyle BracketsStructureCheck (500 lines)

// @checkstyle JavadocTagsCheck (5 lines)

/**
 * Handles maven packages.
 *
 * @author TimePath
 * @version $Id$
 */
@SuppressWarnings("PMD")
public class Package {

    /**
     * The logger.
     */
    private static final Logger LOG = Logger.getLogger(Package.class.getName());
    /**
     * The resource bundle.
     */
    private static final ResourceBundle RESOURCE_BUNDLE = ResourceBundle.getBundle(Package.class.getName());
    /**
     * A map of currently executing tasks.
     */
    private static final Map<Coordinate, Future<Set<Package>>> FUTURES = new HashMap<>();
    /**
     * Maven coordinates.
     */
    private final Coordinate coordinate;
    /**
     * Base URL in maven repo.
     */
    @NonNls
    private final String baseurl;
    /**
     * Artifact checksums.
     */
    @NotNull
    private final Map<String, String> checksums = new Cache<String, String>() {
        /**
         * Fills in a missing value by making a request, or using disk cache.
         * @param key The algorithm
         * @return The checksum
         */
        @Nullable
        @Override
        protected String fill(@NotNull final String key) {
            @NotNull final File existing = UpdateChecker.getFile(Package.this);
            @NotNull final File checksum = new File(existing.getParent(), existing.getName() + '.' + key);
            if (checksum.exists()) {
                // @checkstyle MethodBodyCommentsCheck (1 line)
                // Avoid network
                return IOUtils.requestPage(checksum.toURI().toString());
            }
            return IOUtils.requestPage(UpdateChecker.getChecksumURL(Package.this, key));
        }
    };
    /**
     * Download status.
     */
    private long progress;
    /**
     * Download status.
     */
    private long size;
    /**
     *
     */
    private Set<Package> downloads;
    /**
     *
     */
    @Nullable
    private String name;
    /**
     *
     */
    private boolean self;
    /**
     *
     */
    private Node pom;

    /**
     * Instantiates a new Package.
     *
     * @param baseurl The base URL
     * @param coordinate The coordinate object
     * @checkstyle HiddenFieldCheck (2 lines)
     */
    public Package(final String baseurl, final Coordinate coordinate) {
        this.baseurl = baseurl;
        this.coordinate = coordinate;
    }

    /**
     * Instantiate a Package instance from an XML node.
     *
     * @param root The root node
     * @param context The parent package
     * @return A new instance
     * @checkstyle ExecutableStatementCountCheck (2 lines)
     */
    @Nullable
    public static Package parse(@NotNull final Node root, @Nullable final Package context) {
        final String pprint = XMLUtils.pprint(new DOMSource(root), 2);
        LOG.log(Level.FINER, RESOURCE_BUNDLE.getString("parse"), pprint);
        @NonNls @Nullable String gid = inherit(root, "groupId");
        @NonNls @Nullable final String aid = XMLUtils.get(root, "artifactId");
        @NonNls @Nullable String ver = inherit(root, "version");
        if (gid == null) {
            throw new IllegalArgumentException("group cannot be null");
        }
        if (aid == null) {
            throw new IllegalArgumentException("artifact cannot be null");
        }
        if (ver == null) {
            // @checkstyle MethodBodyCommentsCheck (2 lines)
            // @checkstyle TodoCommentCheck (1 line)
            // TODO: dependencyManagement/dependencies/dependency/version
            throw new UnsupportedOperationException(MessageFormat.format("Null version: {0}:{1}", gid, aid));
        }
        if (context != null) {
            gid = context.expand(gid.replace("${project.groupId}", context.coordinate.getGroup()));
            ver = context.expand(ver.replace("${project.version}", context.coordinate.getVersion()));
        }
        final Coordinate coordinate = Coordinate.from(gid, aid, ver, null);
        final String base;
        try {
            base = MavenResolver.resolve(coordinate);
            LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolved"), new Object[]{coordinate, base});
        } catch (final FileNotFoundException ex) {
            LOG.log(Level.SEVERE, null, ex);
            return null;
        }
        final Package pkg = new Package(base, coordinate);
        pkg.name = XMLUtils.get(root, "name");
        return pkg;
    }

    /**
     * A `self` package has updates downloaded into a different directory.
     *
     * @return Whether the package requires special update treatment
     */
    public boolean isSelf() {
        return this.self
                || ("launcher".equals(this.coordinate.getArtifact())
                && "com.timepath".equals(this.coordinate.getGroup()));
    }

    /**
     * A `self` package has updates downloaded into a different directory.
     *
     * @param self Requires special treatment
     * @checkstyle HiddenFieldCheck (2 lines)
     */
    public void setSelf(final boolean self) {
        this.self = self;
    }

    /**
     * Get the checksum of a package.
     *
     * @param algorithm The algorithm
     * @return The checksum of this artifact with the given algorithm
     */
    @Nullable
    public String getChecksum(final String algorithm) {
        return this.checksums.get(algorithm);
    }

    /**
     * Get the name of this package. If a custom name is available, it will be used. Falls back to formatted coordinates.
     *
     * @return The package name
     */
    @Nullable
    public String getName() {
        if (this.name != null) {
            return this.name;
        }
        return this.coordinate.toString();
    }

    /**
     * Associate a package with a connection to its jar. Might be able to store extra checksum data if present.
     *
     * @param connection The connection
     */
    public void associate(@NotNull final URLConnection connection) {
        @NonNls final String prefix = "x-checksum-";
        for (@NonNls @NotNull final Entry<String, List<String>> field : connection.getHeaderFields().entrySet()) {
            // @checkstyle MethodBodyCommentsCheck (1 line)
            // Null keys! Using String.valueOf
            @NonNls String key = String.valueOf(field.getKey());
            key = key.toLowerCase(Locale.ROOT);
            if (key.startsWith(prefix)) {
                @NotNull final String algorithm = key.substring(prefix.length());
                LOG.log(Level.FINE, RESOURCE_BUNDLE.getString("checksum.associate"), new Object[]{algorithm, this});
                this.checksums.put(algorithm, field.getValue().get(0));
            }
        }
    }

    /**
     * Get a set of all downloads.
     *
     * @return All transitive packages, flattened. Includes self
     */
    public Set<Package> getDownloads() {
        // @checkstyle MethodBodyCommentsCheck (2 lines)
        // @checkstyle TodoCommentCheck (1 line)
        // TODO: eager loading.
        if (this.downloads == null) {
            this.downloads = Collections.unmodifiableSet(this.initDownloads());
        }
        return this.downloads;
    }

    @Override
    public int hashCode() {
        if (this.baseurl != null) {
            return this.baseurl.hashCode();
        }
        LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("null.url"), this);
        return 0;
    }

    @Override
    public boolean equals(@Nullable final Object obj) {
        if (this == obj) {
            return true;
        }
        if ((obj == null) || (this.getClass() != obj.getClass())) {
            return false;
        }
        return this.baseurl.equals(((Package) obj).baseurl);
    }

    // @checkstyle ReturnCountCheck (2 lines)
    @Override
    public String toString() {
        if (this.name != null) {
            return this.name;
        } else if (this.baseurl != null) {
            return FileUtils.name(this.baseurl);
        } else {
            return this.coordinate.toString();
        }
    }

    /**
     * Get the maven coordinates.
     *
     * @return The coordinates
     */
    public Coordinate getCoordinate() {
        return this.coordinate;
    }

    /**
     * Get the base URL of the package in a maven repository.
     *
     * @return The base URL
     */
    public String getBaseurl() {
        return this.baseurl;
    }

    /**
     * Get the download progress.
     *
     * @return The progress
     */
    public long getProgress() {
        return this.progress;
    }

    /**
     * Set the download progress.
     *
     * @param progress The progress
     * @checkstyle HiddenFieldCheck (2 lines)
     */
    public void setProgress(final long progress) {
        this.progress = progress;
    }

    /**
     * Get the download size.
     *
     * @return The size
     */
    public long getSize() {
        return this.size;
    }

    /**
     * Set the download size.
     *
     * @param size The size
     * @checkstyle HiddenFieldCheck (2 lines)
     */
    public void setSize(final long size) {
        this.size = size;
    }

    /**
     * Inherit a property from the parent.
     *
     * @param root The root node
     * @param name The property to inherit
     * @return The final value
     * @checkstyle ReturnCountCheck (3 lines)
     */
    @Nullable
    private static String inherit(final Node root, @NonNls @NotNull final String name) {
        @Nullable final String ret = XMLUtils.get(root, name);
        if (ret == null) {
            // @checkstyle MethodBodyCommentsCheck (1 line)
            // Must be defined by a parent pom
            @Nullable Node parent = null;
            try {
                parent = XMLUtils.last(XMLUtils.getElements(root, "parent"));
                // @checkstyle EmptyBlockCheck (1 line)
            } catch (final NullPointerException ignored) {
            }
            if (parent == null) {
                // @checkstyle MethodBodyCommentsCheck (2 lines)
                // @checkstyle TodoCommentCheck (1 line)
                // TODO: transitive parent poms
                return null;
            }
            return XMLUtils.get(parent, name);
        }
        return ret;
    }

    /**
     * Expands properties.
     *
     * @param raw The raw text
     * @return The expanded property
     */
    @NotNull
    private String expand(@NotNull final String raw) {
        // @checkstyle MethodBodyCommentsCheck (2 lines)
        // @checkstyle TodoCommentCheck (1 line)
        // TODO: recursion
        String str = raw;
        @NotNull final Matcher matcher = Pattern.compile("\\$\\{(.*?)}").matcher(str);
        while (matcher.find()) {
            @NonNls final String property = matcher.group(1);
            @NotNull final List<Node> properties = XMLUtils.getElements(this.pom, "properties");
            final Node propertyNodes = properties.get(0);
            for (@NotNull final Node node : XMLUtils.get(propertyNodes, Node.ELEMENT_NODE)) {
                final String value = node.getFirstChild().getNodeValue();
                @NonNls final String target = "${" + property + '}';
                str = str.replace(target, value);
            }
        }
        return str;
    }

    /**
     * Instantiates all dependencies.
     *
     * @return All transitive packages. Includes self
     */
    @NotNull
    private Set<Package> initDownloads() {
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("downloads.init"), this);
        final Set<Package> set = new HashSet<>();
        set.add(this);
        try (InputStream is = MavenResolver.resolvePomStream(this.coordinate)) {
            if (is == null) {
                LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("null.pom"), this.coordinate);
                return set;
            }
            this.pom = XMLUtils.rootNode(is, "project");
            final Map<Coordinate, Future<Set<Package>>> locals = this.parseDepsTrans();
            for (final Entry<Coordinate, Future<Set<Package>>> entry : locals.entrySet()) {
                try {
                    final Set<Package> result = entry.getValue().get();
                    if (result != null) {
                        set.addAll(result);
                    } else {
                        LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("downloads.fail.single"), entry.getKey());
                    }
                } catch (final InterruptedException | ExecutionException ex) {
                    LOG.log(Level.SEVERE, null, ex);
                }
            }
        } catch (final IOException | ParserConfigurationException | SAXException | IllegalArgumentException ex) {
            LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("downloads.fail.all"), ex);
        }
        return set;
    }

    /**
     * Parse all dependencies.
     *
     * @return All local packages. Includes self
     */
    @NotNull
    private Map<Coordinate, Future<Set<Package>>> parseDeps() {
        final Map<Coordinate, Future<Set<Package>>> locals = new HashMap<>();
        for (final Node depNode : XMLUtils.getElements(this.pom, "dependencies/dependency")) {
            // @checkstyle MethodBodyCommentsCheck (2 lines)
            // @checkstyle TodoCommentCheck (1 line)
            // TODO: thread this potentially long call
            final Package dep = parse(depNode, this);
            if (dep == null) {
                continue;
            }
            if (Boolean.parseBoolean(XMLUtils.get(depNode, "optional"))) {
                continue;
            }
            if (!Scope.from(XMLUtils.get(depNode, "scope")).isTransitive()) {
                continue;
            }
            synchronized (FUTURES) {
                Future<Set<Package>> future = FUTURES.get(dep.coordinate);
                if (future == null) {
                    // @checkstyle InnerAssignmentCheck (1 line)
                    FUTURES.put(dep.coordinate, future = MavenResolver.THREAD_POOL.submit(new DownloadResolveTask(dep, depNode)));
                }
                locals.put(dep.coordinate, future);
            }
        }
        return locals;
    }

    /**
     * Parse all transitive dependencies recursively.
     *
     * @return All transitive packages. Includes self
     */
    @NotNull
    private Map<Coordinate, Future<Set<Package>>> parseDepsTrans() {
        final Map<Coordinate, Future<Set<Package>>> trans = this.parseDeps();
        for (final Future<Set<Package>> entry : new LinkedList<>(trans.values())) {
            try {
                for (final Package aPackage : entry.get()) {
                    trans.putAll(aPackage.parseDepsTrans());
                }
            } catch (final InterruptedException | ExecutionException ignored) {
                LOG.severe("INTERRUPT");
            }
        }
        return trans;
    }

}

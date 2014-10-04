package com.timepath.maven;

import com.timepath.FileUtils;
import com.timepath.IOUtils;
import com.timepath.XMLUtils;
import com.timepath.maven.model.Exclusion;
import com.timepath.maven.model.Scope;
import com.timepath.util.Cache;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLConnection;
import java.text.MessageFormat;
import java.util.*;
import java.util.Map.Entry;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles maven packages
 *
 * @author TimePath
 */
public class Package {

    private static final Logger LOG = Logger.getLogger(Package.class.getName());
    private static final ResourceBundle RESOURCE_BUNDLE = ResourceBundle.getBundle(Package.class.getName());
    private static final Map<Coordinate, Future<Set<Package>>> FUTURES = new HashMap<>();
    /**
     * Maven coordinates
     */
    public final Coordinate coordinate;
    /**
     * Base URL in maven repo
     */
    @NonNls
    final String baseURL;
    /**
     * Artifact checksums
     */
    @NotNull
    private final Map<String, String> checksums = new Cache<String, String>() {
        /**
         *
         * @param key the algorithm
         * @return the checksum
         */
        @Nullable
        @Override
        protected String fill(@NotNull String key) {
            @NotNull File existing = UpdateChecker.getFile(Package.this);
            @NotNull File checksum = new File(existing.getParent(), existing.getName() + '.' + key);
            if (checksum.exists()) { // Avoid network
                return IOUtils.requestPage(checksum.toURI().toString());
            }
            return IOUtils.requestPage(UpdateChecker.getChecksumURL(Package.this, key));
        }
    };
    /**
     * Download status
     */
    public long progress, size;
    private Set<Package> downloads;
    @Nullable
    private String name;
    private boolean self;
    private Node pom;

    public Package(String baseURL, Coordinate coordinate) {
        this.baseURL = baseURL;
        this.coordinate = coordinate;
    }

    /**
     * Instantiate a Package instance from an XML node
     *
     * @param root    the root node
     * @param context the parent package
     * @throws java.lang.IllegalArgumentException if root is null
     */
    @Nullable
    public static Package parse(@Nullable Node root, @Nullable Package context) {
        if (root == null) throw new IllegalArgumentException("The root node cannot be null");

        String pprint = XMLUtils.pprint(new DOMSource(root), 2);
        LOG.log(Level.FINER, RESOURCE_BUNDLE.getString("parse"), pprint);

        @NonNls @Nullable String gid = inherit(root, "groupId");
        @NonNls @Nullable String aid = XMLUtils.get(root, "artifactId");
        @NonNls @Nullable String ver = inherit(root, "version");
        if (gid == null)
            throw new IllegalArgumentException("groupId cannot be null");
        if (aid == null)
            throw new IllegalArgumentException("artifactId cannot be null");
        if (ver == null) { // TODO: dependencyManagement/dependencies/dependency/version
            throw new UnsupportedOperationException(MessageFormat.format("Null version: {0}:{1}", gid, aid));
        }
        if (context != null) {
            gid = context.expand(gid.replace("${project.groupId}", context.coordinate.groupId));
            ver = context.expand(ver.replace("${project.version}", context.coordinate.version));
        }
        Coordinate coordinate = Coordinate.from(gid, aid, ver, null);
        String base;
        try {
            base = MavenResolver.resolve(coordinate);
            LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolved"), new Object[]{coordinate, base});
        } catch (FileNotFoundException e) {
            LOG.log(Level.SEVERE, null, e);
            return null;
        }
        Package p = new Package(base, coordinate);
        p.name = XMLUtils.get(root, "name");
        return p;
    }

    @Nullable
    private static String inherit(Node root, @NonNls @NotNull String name) {
        @Nullable String ret = XMLUtils.get(root, name);
        if (ret == null) { // Must be defined by a parent pom
            @Nullable Node parent = null;
            try {
                parent = XMLUtils.last(XMLUtils.getElements(root, "parent"));
            } catch (NullPointerException ignored) {
            }
            if (parent == null) return null; // TODO: transitive parent poms
            return XMLUtils.get(parent, name);
        }
        return ret;
    }

    /**
     * Expands properties
     * TODO: recursion
     */
    @NotNull
    private String expand(@NotNull String s) {
        @NotNull Matcher matcher = Pattern.compile("\\$\\{(.*?)}").matcher(s);
        while (matcher.find()) {
            @NonNls String property = matcher.group(1);
            @NotNull List<Node> properties = XMLUtils.getElements(pom, "properties");
            Node propertyNodes = properties.get(0);
            for (@NotNull Node node : XMLUtils.get(propertyNodes, Node.ELEMENT_NODE)) {
                String value = node.getFirstChild().getNodeValue();
                @NonNls String target = "${" + property + '}';
                s = s.replace(target, value);
            }
        }
        return s;
    }

    public boolean isSelf() {
        return self
                || ("launcher".equals(coordinate.artifactId)
                && "com.timepath".equals(coordinate.groupId));
    }

    public void setSelf(boolean self) {
        this.self = self;
    }

    @Nullable
    public String getChecksum(String algorithm) {
        return checksums.get(algorithm);
    }

    @Nullable
    public String getName() {
        if (name != null) return name;
        // Fallback
        return coordinate.toString();
    }

    /**
     * Associate a package with a connection to its jar. Might be able to store extra checksum data if present.
     *
     * @param connection
     */
    public void associate(@NotNull URLConnection connection) {
        @NonNls String prefix = "x-checksum-";
        for (@NonNls @NotNull Entry<String, List<String>> field : connection.getHeaderFields().entrySet()) {
            @NonNls String key = String.valueOf(field.getKey()); // Null keys!
            key = key.toLowerCase();
            if (key.startsWith(prefix)) {
                @NotNull String algorithm = key.substring(prefix.length());
                LOG.log(Level.FINE, RESOURCE_BUNDLE.getString("checksum.associate"), new Object[]{algorithm, this});
                checksums.put(algorithm, field.getValue().get(0));
            }
        }
    }

    /**
     * TODO: eager loading
     *
     * @return all transitive packages, flattened. Includes self.
     */
    public Set<Package> getDownloads() {
        return (downloads == null) ? (downloads = Collections.unmodifiableSet(initDownloads())) : downloads;
    }

    @Override
    public int hashCode() {
        if (baseURL != null) return baseURL.hashCode();
        LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("null.url"), this);
        return 0;
    }

    @Override
    public boolean equals(@Nullable Object obj) {
        if (this == obj) return true;
        if ((obj == null) || (getClass() != obj.getClass())) return false;
        return baseURL.equals(((Package) obj).baseURL);
    }

    @Nullable
    @Override
    public String toString() {
        if (name != null) return name;
        else return ((baseURL != null) ? FileUtils.name(baseURL) : coordinate.toString());
    }

    /**
     * Fetches all transitive packages. Includes self.
     */
    @NotNull
    private Set<Package> initDownloads() {
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("downloads.init"), this);
        Set<Package> set = new HashSet<>();
        set.add(this);
        try (InputStream is = MavenResolver.resolvePomStream(coordinate)) {
            if (is == null) {
                LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("null.pom"), coordinate);
                return set;
            }
            pom = XMLUtils.rootNode(is, "project");
            Map<Coordinate, Future<Set<Package>>> locals = new HashMap<>();
            for (final Node depNode : XMLUtils.getElements(pom, "dependencies/dependency")) {
                if (Boolean.parseBoolean(XMLUtils.get(depNode, "optional"))) continue;
                if (!Scope.from(XMLUtils.get(depNode, "scope")).isTransitive()) continue;
                final Package dep = parse(depNode, this); // TODO: thread this potentially long call
                synchronized (FUTURES) {
                    Future<Set<Package>> future = FUTURES.get(dep.coordinate);
                    if (future == null) {
                        FUTURES.put(dep.coordinate, future = MavenResolver.THREAD_POOL.submit(new Callable<Set<Package>>() {

                            @NotNull
                            @Override
                            public Set<Package> call() throws Exception {
                                @NotNull Set<Package> depDownloads = new HashSet<>();
                                try {
                                    transitives:
                                    for (@NotNull Package depDownload : dep.getDownloads()) {
                                        for (Node exNode : XMLUtils.getElements(depNode, "exclusions/exclusion")) {
                                            @NotNull Exclusion exclusion = new Exclusion(exNode);
                                            if (exclusion.matches(depDownload)) continue transitives;
                                        }
                                        depDownloads.add(depDownload);
                                    }
                                } catch (@NotNull IllegalArgumentException | UnsupportedOperationException e) {
                                    LOG.log(Level.SEVERE, null, e);
                                }
                                return depDownloads;
                            }
                        }));
                    }
                    locals.put(dep.coordinate, future);
                }
            }
            for (Entry<Coordinate, Future<Set<Package>>> entry : locals.entrySet()) {
                try {
                    Set<Package> result = entry.getValue().get();
                    if (result != null) {
                        set.addAll(result);
                    } else {
                        LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("downloads.fail.single"), entry.getKey());
                    }
                } catch (InterruptedException | ExecutionException e) {
                    LOG.log(Level.SEVERE, null, e);
                }
            }
        } catch (IOException | ParserConfigurationException | SAXException | IllegalArgumentException e) {
            LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("downloads.fail.all"), e);
        }
        return set;
    }
}

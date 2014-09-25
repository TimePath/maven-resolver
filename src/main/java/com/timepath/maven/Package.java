package com.timepath.maven;

import com.timepath.FileUtils;
import com.timepath.IOUtils;
import com.timepath.XMLUtils;
import com.timepath.maven.model.Exclusion;
import com.timepath.maven.model.Scope;
import com.timepath.util.Cache;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;
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
    private static final Map<Coordinate, Future<Set<Package>>> FUTURES = new HashMap<>();
    /**
     * Maven coordinates
     */
    public Coordinate coordinate;
    /**
     * Download status
     */
    public long progress, size;
    /**
     * Base URL in maven repo
     */
    String baseURL;
    /**
     * Artifact checksums
     */
    private Cache<String, String> checksums = new Cache<String, String>() {
        @Override
        protected String fill(String algorithm) {
            return IOUtils.requestPage(UpdateChecker.getChecksumURL(Package.this, algorithm));
        }
    };
    private Set<Package> downloads;
    private String name;
    private boolean self;
    private Node pom;

    public Package(String gid, String aid, String ver) {
        this.coordinate = Coordinate.from(gid, aid, ver, null);
    }

    /**
     * Instantiate a Package instance from an XML node
     *
     * @param root    the root node
     * @param context the parent package
     * @throws java.lang.IllegalArgumentException if root is null
     */
    public static Package parse(Node root, Package context) {
        if (root == null) throw new IllegalArgumentException("The root node cannot be null");

        String pprint = XMLUtils.pprint(new DOMSource(root), 2);
        LOG.log(Level.FINER, "Constructing Package from node:\n{0}", pprint);

        String gid = inherit(root, "groupId");
        String aid = XMLUtils.get(root, "artifactId");
        String ver = inherit(root, "version");
        if (gid == null) { // Invalid pom
            LOG.log(Level.WARNING, "Invalid POM, no groupId");
            return null;
        }
        if (ver == null) { // TODO: dependencyManagement/dependencies/dependency/version
            throw new UnsupportedOperationException("Null version: " + String.format("%s:%s", gid, aid));
        }
        if (context != null) {
            gid = expand(context, gid.replace("${project.groupId}", context.coordinate.groupId));
            ver = expand(context, ver.replace("${project.version}", context.coordinate.version));
        }
        Package p = new Package(gid, aid, ver);
        p.name = XMLUtils.get(root, "name");
        try {
            p.baseURL = MavenResolver.resolve(p.coordinate);
            LOG.log(Level.INFO, "Resolved to {0}", p.baseURL);
        } catch (FileNotFoundException e) {
            LOG.log(Level.SEVERE, null, e);
        }
        return p;
    }


    public static boolean isSelf(Package aPackage) {
        return aPackage.self
                || ("launcher".equals(aPackage.coordinate.artifactId)
                && "com.timepath".equals(aPackage.coordinate.groupId));
    }

    public static void setSelf(Package aPackage, final boolean self) {
        aPackage.self = self;
    }

    private static String inherit(Node root, String name) {
        String ret = XMLUtils.get(root, name);
        if (ret == null) { // Must be defined by a parent pom
            Node parent = null;
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
    private static String expand(Package context, String string) {
        Matcher matcher = Pattern.compile("\\$\\{(.*?)}").matcher(string);
        while (matcher.find()) {
            String property = matcher.group(1);
            List<Node> properties = XMLUtils.getElements(context.pom, "properties");
            Node propertyNodes = properties.get(0);
            for (Node n : XMLUtils.get(propertyNodes, Node.ELEMENT_NODE)) {
                String value = n.getFirstChild().getNodeValue();
                string = string.replace("${" + property + "}", value);
            }
        }
        return string;
    }

    public String getChecksum(String algorithm) {
        return checksums.get(algorithm);
    }

    public String getName() {
        return name;
    }

    /**
     * Associate a package with a connection to its jar. Might be able to store extra checksum data if present.
     *
     * @param connection
     */
    public void associate(URLConnection connection) {
        String prefix = "x-checksum-";
        for (Map.Entry<String, List<String>> field : connection.getHeaderFields().entrySet()) {
            String key = String.valueOf(field.getKey()).toLowerCase(); // Null keys!
            if (key.startsWith(prefix)) {
                String algorithm = key.substring(prefix.length());
                LOG.log(Level.FINE, "Associating checksum: {0} {1}", new Object[]{algorithm, this});
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
        return downloads == null ? downloads = Collections.unmodifiableSet(initDownloads()) : downloads;
    }

    @Override
    public int hashCode() {
        if (baseURL != null) {
            return baseURL.hashCode();
        }
        LOG.log(Level.SEVERE, "baseURL not set: {0}", this);
        return 0;
    }

    @Override
    public boolean equals(final Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        return baseURL.equals(((Package) o).baseURL);
    }

    @Override
    public String toString() {
        return name != null ? name : baseURL != null ? FileUtils.name(baseURL) : coordinate.toString();
    }

    /**
     * Fetches all transitive packages. Includes self.
     */
    private Set<Package> initDownloads() {
        LOG.log(Level.INFO, "initDownloads: {0}", this);
        Set<Package> set = new HashSet<>();
        set.add(this);
        try {
            // Pull in the pom
            pom = XMLUtils.rootNode(MavenResolver.resolvePomStream(coordinate), "project");
            Map<Coordinate, Future<Set<Package>>> locals = new HashMap<>();
            for (final Node depNode : XMLUtils.getElements(pom, "dependencies/dependency")) {
                if (Boolean.parseBoolean(XMLUtils.get(depNode, "optional"))) continue;
                if (!Scope.from(XMLUtils.get(depNode, "scope")).isTransitive()) continue;
                final Package dep = Package.parse(depNode, Package.this); // TODO: thread this potentially long call
                synchronized (FUTURES) {
                    Future<Set<Package>> future = FUTURES.get(dep.coordinate);
                    if (future == null) {
                        FUTURES.put(dep.coordinate, future = MavenResolver.THREAD_POOL.submit(new Callable<Set<Package>>() {

                            @Override
                            public Set<Package> call() throws Exception {
                                Set<Package> depDownloads = new HashSet<>();
                                try {
                                    transitives:
                                    for (Package depDownload : dep.getDownloads()) {
                                        for (Node exNode : XMLUtils.getElements(depNode, "exclusions/exclusion")) {
                                            Exclusion exclusion = new Exclusion(exNode);
                                            if (exclusion.matches(depDownload)) continue transitives;
                                        }
                                        depDownloads.add(depDownload);
                                    }
                                } catch (IllegalArgumentException | UnsupportedOperationException e) {
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
                        LOG.log(Level.SEVERE, "Download enumeration failed:\n{0}", entry.getKey());
                    }
                } catch (InterruptedException | ExecutionException e) {
                    LOG.log(Level.SEVERE, null, e);
                }
            }
        } catch (IOException | ParserConfigurationException | SAXException | IllegalArgumentException e) {
            LOG.log(Level.SEVERE, "initDownloads", e);
        }
        return set;
    }
}

package com.timepath.maven;

import com.timepath.XMLUtils;
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
    private static final Map<Node, Future<Set<Package>>> FUTURES = new HashMap<>();
    /**
     * Download status
     */
    public long progress, size;
    /**
     * Maven coordinates
     */
    String gid, aid, ver;
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
            return Utils.requestPage(UpdateChecker.getChecksumURL(Package.this, algorithm));
        }
    };
    private Set<Package> downloads;
    private String name;
    private boolean self;
    private Node pom;

    /**
     * Instantiate a Package instance from an XML node
     *
     * @param root    the root node
     * @param context the parent package
     */
    public static Package parse(Node root, Package context) {
        if (root == null) throw new IllegalArgumentException("The root node cannot be null");
        Package p = new Package();
        LOG.log(Level.FINE, "Constructing Package from node");
        String pprint = Utils.pprint(new DOMSource(root), 2);
        LOG.log(Level.FINER, "{0}", pprint);
        p.name = XMLUtils.get(root, "name");
        p.gid = inherit(root, "groupId");
        if (p.gid == null) { // invalid pom
            LOG.log(Level.WARNING, "Invalid POM, no groupId");
            return null;
        }
        p.aid = XMLUtils.get(root, "artifactId");
        p.ver = inherit(root, "version"); // TODO: dependencyManagement/dependencies/dependency/version
        if (p.ver == null) {
            throw new UnsupportedOperationException("Null version: " + p);
        }
        if (context != null) {
            p.expand(context);
        }
        try {
            p.baseURL = MavenResolver.resolve(Coordinate.from(p.gid, p.aid, p.ver, null));
            LOG.log(Level.INFO, "Resolved to {0}", p.baseURL);
        } catch (FileNotFoundException e) {
            LOG.log(Level.SEVERE, null, e);
        }
        return p;
    }


    public static boolean isSelf(Package aPackage) {
        return aPackage.self || ("launcher".equals(aPackage.aid) && "com.timepath".equals(aPackage.gid));
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
        return name != null ? name : baseURL != null ? Utils.name(baseURL) : String.format("%s:%s", gid, aid);
    }

    /**
     * Expands properties
     * TODO: recursion
     */
    private void expand(Package context) {
        gid = expand(context, gid.replace("${project.groupId}", context.gid));
        ver = expand(context, ver.replace("${project.version}", context.ver));
    }

    private String expand(Package context, String string) {
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

    /**
     * Fetches all transitive packages. Includes self.
     */
    private Set<Package> initDownloads() {
        LOG.log(Level.INFO, "initDownloads: {0}", this);
        Set<Package> set = Collections.synchronizedSet(new HashSet<Package>());
        set.add(this);
        try {
            // Pull in the pom
            pom = XMLUtils.rootNode(MavenResolver.resolvePomStream(Coordinate.from(gid, aid, ver, null)), "project");
            Map<Node, Future<Set<Package>>> locals = new HashMap<>();
            for (final Node d : XMLUtils.getElements(pom, "dependencies/dependency")) {
                // Check scope
                String type = XMLUtils.get(d, "scope");
                if (type == null) type = "compile";
                // TODO: 'import' scope
                switch (type.toLowerCase()) {
                    case "provided":
                    case "test":
                    case "system":
                        continue;
                }
                synchronized (FUTURES) {
                    Future<Set<Package>> future = FUTURES.get(d);
                    if (future == null) {
                        FUTURES.put(d, future = MavenResolver.THREAD_POOL.submit(new Callable<Set<Package>>() {
                            @Override
                            public Set<Package> call() throws Exception {
                                try {
                                    Package pkg = Package.parse(d, Package.this);
                                    // FIXME: pkg should not be null
                                    if (pkg != null) return pkg.getDownloads();
                                } catch (IllegalArgumentException e) {
                                    LOG.log(Level.SEVERE, null, e);
                                }
                                return null;
                            }
                        }));
                    }
                    locals.put(d, future);
                }
            }
            for (Entry<Node, Future<Set<Package>>> entry : locals.entrySet()) {
                try {
                    Set<Package> result = entry.getValue().get();
                    if (result != null) {
                        set.addAll(result);
                    } else {
                        LOG.log(Level.SEVERE, "Download enumeration failed:\n{0}", XMLUtils.pprint(entry.getKey()));
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

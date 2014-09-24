package com.timepath.maven;

import com.timepath.XMLUtils;
import com.timepath.util.Cache;
import org.w3c.dom.Node;
import org.w3c.dom.bootstrap.DOMImplementationRegistry;
import org.w3c.dom.ls.DOMImplementationLS;
import org.w3c.dom.ls.LSSerializer;
import org.xml.sax.SAXException;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.dom.DOMSource;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.URLConnection;
import java.security.NoSuchAlgorithmException;
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
    private final Set<Package> downloads = Collections.synchronizedSet(new HashSet<Package>());
    /**
     * Download status
     */
    public long progress = -1, size = -1;
    private String name;
    /**
     * Maven coordinates
     */
    private String gid, aid, ver;
    /**
     * Base URL in maven repo
     */
    private String baseURL;
    private boolean locked;
    private boolean self;
    private Node pom;

    /**
     * Instantiate a Package instance from an XML node
     *
     * @param root    the root node
     * @param context the parent package
     */
    public static Package parse(Node root, Package context) {
        if (root == null) {
            throw new IllegalArgumentException("The root node cannot be null");
        }
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
            throw new UnsupportedOperationException("Null version: " + MessageFormat.format("{0}:{1}", p.gid, p.aid));
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
    public void expand(Package context) {
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
        return name == null ? Utils.name(baseURL) : name;
    }

    /**
     * Check a <b>single package</b> for updates
     *
     * @return false if completely up to date, true if up to date or working offline
     */
    public boolean isLatest() {
        LOG.log(Level.INFO, "Checking {0} for updates...", this);
        try {
            File existing = getFile();
            if (!existing.exists()) {
                LOG.log(Level.INFO, "Don''t have {0}, not latest", existing);
                return false;
            }
            String expected = getChecksum("SHA1");
            String actual = Utils.checksum(existing, "SHA1");
            LOG.log(Level.INFO, "Checksum: {0} {1}", new Object[]{expected, existing});
            if (!actual.equals(expected)) {
                LOG.log(Level.INFO,
                        "Checksum mismatch for {0}, not latest. {1} vs {2}",
                        new Object[]{existing, expected, actual});
                return false;
            }
        } catch (IOException | NoSuchAlgorithmException ex) {
            LOG.log(Level.SEVERE, null, ex);
            return false;
        }
        LOG.log(Level.INFO, "{0} is up to date", this);
        return true;
    }

    private Cache<String, String> checksums = new Cache<String, String>() {
        @Override
        protected String fill(String algorithm) {
            return Utils.requestPage(getChecksumURL(algorithm));
        }
    };

    public synchronized String getChecksum(String algorithm) {
        return checksums.get(algorithm.toLowerCase());
    }

    public File getFile() {
        if (this.isSelf()) return Utils.CURRENT_FILE;
        return new File(getProgramDirectory(), getFileName());
    }

    public String getFileName() {
        return Utils.name(getDownloadURL());
    }

    /**
     * TODO: other package types
     *
     * @return
     */
    public String getDownloadURL() {
        return baseURL + ".jar";
    }

    public String getProgramDirectory() {
        return MessageFormat.format("{0}/{1}/{2}/{3}", MavenResolver.getLocal(), gid.replace('.', '/'), aid, ver);
    }

    public boolean isSelf() {
        return self || ("launcher".equals(aid) && "com.timepath".equals(gid));
    }

    public void setSelf(final boolean self) {
        this.self = self;
    }

    /**
     * TODO: other package types
     *
     * @return
     */
    public String getChecksumURL(String algorithm) {
        return baseURL + ".jar." + algorithm.toLowerCase();
    }

    /**
     * Check the integrity of a single package
     *
     * @return true if matches SHA1 checksum
     */
    public boolean verify() {
        LOG.log(Level.INFO, "Checking integrity of {0}", this);
        try {
            File existing = getFile();
            File checksum = new File(existing.getParent(), existing.getName() + ".sha1");
            if (!checksum.exists() || !existing.exists()) {
                LOG.log(Level.INFO, "Don''t have {0}, reacquire", existing);
                return false;
            }
            String expected = Utils.requestPage(checksum.toURI().toString());
            String actual = Utils.checksum(existing, "SHA1");
            if (!actual.equals(expected)) {
                LOG.log(Level.INFO,
                        "Checksum mismatch for {0}, reacquire. {1} vs {2}",
                        new Object[]{existing, expected, actual});
                return false;
            }
        } catch (IOException | NoSuchAlgorithmException ex) {
            LOG.log(Level.SEVERE, null, ex);
            return false;
        }
        LOG.log(Level.INFO, "Verified {0}", this);
        return true;
    }

    /**
     * @return all updates, flattened
     */
    public Set<Package> getUpdates() {
        Set<Package> downloads = getDownloads();
        Set<Package> outdated = new HashSet<>();
        LOG.log(Level.INFO, "Download list: {0}", downloads.toString());
        for (Package p : downloads) {
            if (!p.verify()) {
                LOG.log(Level.INFO, "{0} is outdated", p);
                outdated.add(p);
            }
        }
        return outdated;
    }

    /**
     * @return all package files, flattened
     */
    public Set<Package> getDownloads() {
        return downloads.isEmpty() ? initDownloads() : Collections.unmodifiableSet(downloads);
    }

    private static final Map<Node, Future<Set<Package>>> FUTURES = new HashMap<>();

    /**
     * Fetches all dependency information recursively
     * TODO: eager loading
     */
    private Set<Package> initDownloads() {
        LOG.log(Level.INFO, "initDownloads: {0}", this);
        downloads.add(this);
        try {
            // pull the pom
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
                        future = MavenResolver.THREAD_POOL.submit(new Callable<Set<Package>>() {
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
                        });
                        FUTURES.put(d, future);
                    }
                    locals.put(d, future);
                }
            }
            for (Entry<Node, Future<Set<Package>>> entry : locals.entrySet()) {
                try {
                    Set<Package> result = entry.getValue().get();
                    if (result != null) {
                        downloads.addAll(result);
                    } else {
                        LOG.log(Level.SEVERE, "Download enumeration failed:\n{0}", pprint(entry.getKey()));
                    }
                } catch (InterruptedException | ExecutionException e) {
                    LOG.log(Level.SEVERE, null, e);
                }
            }
        } catch (IOException | ParserConfigurationException | SAXException | IllegalArgumentException e) {
            LOG.log(Level.SEVERE, "initDownloads", e);
        }
        return downloads;
    }

    private String pprint(Node n) {
        try {
            DOMImplementationRegistry registry = DOMImplementationRegistry.newInstance();
            DOMImplementationLS impl = (DOMImplementationLS) registry.getDOMImplementation("LS");
            LSSerializer writer = impl.createLSSerializer();
            writer.getDomConfig().setParameter("format-pretty-print", true);
            writer.getDomConfig().setParameter("xml-declaration", false);
            return writer.writeToString(n);
        } catch (ClassNotFoundException | InstantiationException | IllegalAccessException e) {
            return String.valueOf(n);
        }
    }

    public File getChecksumFile(String algorithm) {
        return new File(getProgramDirectory(), Utils.name(getChecksumURL(algorithm)));
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(final boolean locked) {
        if (locked) {
            LOG.log(Level.INFO, "Locking {0}", this);
        } else {
            LOG.log(Level.INFO, "unlocking {0}", this);
        }
        this.locked = locked;
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
}

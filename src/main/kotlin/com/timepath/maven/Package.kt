// @checkstyle HeaderCheck (1 line)
package com.timepath.maven

import com.timepath.FileUtils
import com.timepath.XMLUtils
import com.timepath.maven.model.Coordinate
import com.timepath.maven.model.Scope
import com.timepath.maven.tasks.DownloadResolveTask
import com.timepath.util.Cache
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URLConnection
import java.util.Collections
import java.util.HashMap
import java.util.HashSet
import java.util.LinkedList
import java.util.Locale
import java.util.ResourceBundle
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.logging.Level
import java.util.logging.Logger
import javax.xml.parsers.ParserConfigurationException
import javax.xml.transform.dom.DOMSource
import org.jetbrains.annotations.NonNls
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.net.URI
import java.util.regex.MatchResult

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
SuppressWarnings("PMD")
public class Package
/**
 * Instantiates a new Package.
 *
 * @param baseurl The base URL
 * @param coordinate The coordinate object
 * @checkstyle HiddenFieldCheck (2 lines)
 */
(
        /**
         * Base URL in maven repo.
         */
        NonNls
        public val baseurl: String?,
        /**
         * Maven coordinates.
         */
        public val coordinate: Coordinate) {
    /**
     * Artifact checksums.
     */
    private val checksums = object : Cache<String, String>() {
        /**
         * Fills in a missing value by making a request, or using disk cache.
         * @param key The algorithm
         * @return The checksum
         */
        override fun fill(key: String): String? {
            val existing = UpdateChecker.getFile(this@Package)
            val checksum = File(existing.getParent(), "${existing.getName()}.$key")
            if (checksum.exists()) {
                // Avoid network
                try {
                    return checksum.toURI().toURL().readText()
                } catch(ignored: IOException) {

                }
            }
            return try {
                URI(UpdateChecker.getChecksumURL(this@Package, key)).toURL().readText()
            } catch (ignored: IOException) {
                null
            }
        }
    }
    /**
     * Download status.
     */
    public var progress: Long = 0
    /**
     * Download status.
     */
    public var size: Long = 0
    /**
     *
     */
    private var downloads: Set<Package>? = null
    /**
     * Get the name of this package.
     * If a custom name is available, it will be used.
     * Falls back to formatted coordinates.
     */
    var name: String? = null
        get() = $name ?: coordinate.toString()
        private set
    /**
     *
     */
    private var self: Boolean = false
    /**
     *
     */
    private var pom: Node? = null

    /**
     * A `self` package has updates downloaded into a different directory.
     *
     * @return Whether the package requires special update treatment
     */
    public fun isSelf(): Boolean {
        return this.self || ("launcher" == this.coordinate.artifact && "com.timepath" == this.coordinate.group)
    }

    /**
     * A `self` package has updates downloaded into a different directory.
     *
     * @param self Requires special treatment
     * @checkstyle HiddenFieldCheck (2 lines)
     */
    public fun setSelf(self: Boolean) {
        this.self = self
    }

    /**
     * Get the checksum of a package.
     *
     * @param algorithm The algorithm
     * @return The checksum of this artifact with the given algorithm
     */
    public fun getChecksum(algorithm: String): String? = this.checksums[algorithm]

    /**
     * Associate a package with a connection to its jar. Might be able to store extra checksum data if present.
     *
     * @param connection The connection
     */
    public fun associate(connection: URLConnection) {
        [NonNls] val prefix = "x-checksum-"
        for (field in connection.getHeaderFields().entrySet()) {
            // @checkstyle MethodBodyCommentsCheck (1 line)
            // Null keys! Using String.valueOf
            [NonNls] var key = field.getKey().toString()
            key = key.toLowerCase(Locale.ROOT)
            if (key.startsWith(prefix)) {
                val algorithm = key.substring(prefix.length())
                LOG.log(Level.FINE, RESOURCE_BUNDLE.getString("checksum.associate"), array(algorithm, this))
                this.checksums.put(algorithm, field.getValue()[0])
            }
        }
    }

    /**
     * Get a set of all downloads.
     *
     * @return All transitive packages, flattened. Includes self
     */
    public fun getDownloads(): Set<Package> {
        // @checkstyle MethodBodyCommentsCheck (2 lines)
        // @checkstyle TodoCommentCheck (1 line)
        // TODO: eager loading.
        if (this.downloads == null) {
            this.downloads = Collections.unmodifiableSet<Package>(this.initDownloads())
        }
        return this.downloads!!
    }

    override fun hashCode(): Int {
        if (this.baseurl != null) {
            return this.baseurl!!.hashCode()
        }
        LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("null.url"), this)
        return 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) {
            return true
        }
        if ((other == null) || (this.javaClass != other.javaClass)) {
            return false
        }
        return this.baseurl == (other as Package).baseurl
    }

    // @checkstyle ReturnCountCheck (2 lines)
    override fun toString(): String {
        if (this.name != null) {
            return this.name!!
        } else if (this.baseurl != null) {
            return this.baseurl!!.substringAfterLast('/')
        } else {
            return this.coordinate.toString()
        }
    }

    /**
     * Expands properties.
     * @param raw The raw text
     * @return The expanded property
     */
    private fun expand(raw: String): String = raw.replaceAll("\\$\\{([^}]+)}") { expand(it) }

    private fun expand(it: MatchResult): String {
        val property = it.group(1)
        val propertyNodes = XMLUtils.getElements(this.pom!!, "properties").first()
        XMLUtils.get(propertyNodes, Node.ELEMENT_NODE).forEach {
            val s = it.getFirstChild().getNodeValue()
            if (s == property) {
                return s
            }
        }
        // TODO: recursion
        LOG.warning("Cannot find pom property `$property`")
        return "\${$property}"
    }

    /**
     * Instantiates all dependencies.
     *
     * @return All transitive packages. Includes self
     */
    private fun initDownloads(): Set<Package> {
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("downloads.init"), this)
        val set = HashSet<Package>()
        set.add(this)
        try {
            val stream = MavenResolver.resolvePomStream(this.coordinate)
            if (stream == null) {
                LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("null.pom"), this.coordinate)
                return set
            }
            stream.use {
                this.pom = XMLUtils.rootNode(it, "project")
                val locals = this.parseDepsTrans()
                for (entry in locals.entrySet()) {
                    try {
                        val result = entry.getValue().get()
                        if (result != null) {
                            set.addAll(result)
                        } else {
                            LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("downloads.fail.single"), entry.getKey())
                        }
                    } catch (ex: InterruptedException) {
                        LOG.log(Level.SEVERE, null, ex)
                    } catch (ex: ExecutionException) {
                        LOG.log(Level.SEVERE, null, ex)
                    }

                }
            }
        } catch (ex: IOException) {
            LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("downloads.fail.all"), ex)
        } catch (ex: ParserConfigurationException) {
            LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("downloads.fail.all"), ex)
        } catch (ex: SAXException) {
            LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("downloads.fail.all"), ex)
        } catch (ex: IllegalArgumentException) {
            LOG.log(Level.SEVERE, RESOURCE_BUNDLE.getString("downloads.fail.all"), ex)
        }

        return set
    }

    /**
     * Parse all dependencies.
     *
     * @return All local packages. Includes self
     */
    private fun parseDeps(): MutableMap<Coordinate, Future<Set<Package>>> {
        val locals = HashMap<Coordinate, Future<Set<Package>>>()
        for (depNode in XMLUtils.getElements(this.pom!!, "dependencies/dependency")) {
            // @checkstyle MethodBodyCommentsCheck (2 lines)
            // @checkstyle TodoCommentCheck (1 line)
            // TODO: thread this potentially long call
            val dep = parse(depNode, this)
            if (dep == null) {
                continue
            }
            if (XMLUtils.get(depNode, "optional")?.toBoolean() ?: true) {
                continue
            }
            if (!(XMLUtils.get(depNode, "scope")?.let { Scope[it] } ?: Scope.COMPILE).isTransitive) {
                continue
            }
            synchronized (FUTURES) {
                locals.put(dep.coordinate, FUTURES.getOrPut(dep.coordinate) {
                    MavenResolver.THREAD_POOL.submit(DownloadResolveTask(dep, depNode))
                })
            }
        }
        return locals
    }

    /**
     * Parse all transitive dependencies recursively.
     *
     * @return All transitive packages. Includes self
     */
    private fun parseDepsTrans(): Map<Coordinate, Future<Set<Package>>> {
        val trans = this.parseDeps()
        for (entry in LinkedList(trans.values())) {
            try {
                entry.get().forEach { trans.putAll(it.parseDepsTrans()) }
            } catch (ignored: InterruptedException) {
                LOG.severe("INTERRUPT")
            } catch (ignored: ExecutionException) {
                LOG.severe("INTERRUPT")
            }
        }
        return trans
    }

    companion object {

        /**
         * The logger.
         */
        private val LOG = Logger.getLogger(javaClass<Package>().getName())
        /**
         * The resource bundle.
         */
        private val RESOURCE_BUNDLE = ResourceBundle.getBundle(javaClass<Package>().getName())
        /**
         * A map of currently executing tasks.
         */
        private val FUTURES = HashMap<Coordinate, Future<Set<Package>>>()

        /**
         * Instantiate a Package instance from an XML node.
         *
         * @param root The root node
         * @param context The parent package
         * @return A new instance
         * @checkstyle ExecutableStatementCountCheck (2 lines)
         */
        public fun parse(root: Node, context: Package?): Package? {
            val pprint = XMLUtils.pprint(DOMSource(root), 2)
            LOG.log(Level.FINER, RESOURCE_BUNDLE.getString("parse"), pprint)
            [NonNls] var gid = inherit(root, "groupId")!!
            [NonNls] val aid = XMLUtils.get(root, "artifactId")!!
            // TODO: dependencyManagement/dependencies/dependency/version
            [NonNls] var ver = inherit(root, "version")!!
            if (context != null) {
                gid = context.expand(gid.replace("\${project.groupId}", context.coordinate.group))
                ver = context.expand(ver.replace("\${project.version}", context.coordinate.version))
            }
            val coordinate = Coordinate[gid, aid, ver, null]
            val base: String
            try {
                base = MavenResolver.resolve(coordinate)
                LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolved"), array(coordinate, base))
            } catch (ex: FileNotFoundException) {
                LOG.log(Level.SEVERE, null, ex)
                return null
            }

            val pkg = Package(base, coordinate)
            pkg.name = XMLUtils.get(root, "name")
            return pkg
        }

        /**
         * Inherit a property from the parent.
         *
         * @param root The root node
         * @param name The property to inherit
         * @return The final value
         * @checkstyle ReturnCountCheck (3 lines)
         */
        private fun inherit(root: Node, NonNls name: String): String? {
            val ret = XMLUtils.get(root, name)
            ret?.let { return it }
            // Must be defined by a parent pom
            var parent: Node? = try {
                XMLUtils.last<Node>(XMLUtils.getElements(root, "parent"))
            } catch (ignored: NullPointerException) {
                // TODO: transitive parent poms
                return null
            }
            return XMLUtils.get(parent!!, name)
        }
    }

}

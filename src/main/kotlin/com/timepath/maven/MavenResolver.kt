// @checkstyle HeaderCheck (1 line)
package com.timepath.maven

import com.timepath.Utils
import com.timepath.maven.model.Coordinate
import com.timepath.maven.tasks.PomResolveTask
import com.timepath.maven.tasks.UrlResolveTask
import com.timepath.util.Cache
import com.timepath.util.concurrent.DaemonThreadFactory
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.net.MalformedURLException
import java.nio.charset.StandardCharsets
import java.text.MessageFormat
import java.util.Collections
import java.util.LinkedHashSet
import java.util.ResourceBundle
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.logging.Level
import java.util.logging.Logger
import java.util.regex.Pattern
import org.jetbrains.annotations.NonNls

// @checkstyle LineLengthCheck (500 lines)

// @checkstyle JavadocTagsCheck (5 lines)

/**
 * Utility class for resolving coordinates to urls.
 *
 * @author TimePath
 * @version $Id$
 * @checkstyle ClassDataAbstractionCouplingCheck (2 lines)
 */
SuppressWarnings("PMD")

val LOG = Logger.getLogger(javaClass<MavenResolver>().getName())

private class PomCache : Cache<Coordinate, Future<String>>() {
    override fun fill(key: Coordinate): Future<String> {
        LOG.log(Level.INFO, MavenResolver.RESOURCE_BUNDLE.getString("resolve.pom.miss"), key)
        return MavenResolver.THREAD_POOL.submit<String>(PomResolveTask(key))
    }
}

private class UrlCache : Cache<Coordinate, Future<String>>() {
    override fun expire(key: Coordinate, value: Future<String>?): Future<String>? {
        var ret = value
        if (value == null) {
            val str = PersistentCache[key]
            if (str != null) {
                ret = UrlResolveTask.makeFuture(str)
            }
        }
        return ret
    }

    override fun fill(key: Coordinate): Future<String> {
        LOG.log(Level.INFO, MavenResolver.RESOURCE_BUNDLE.getString("resolve.url.miss"), key)
        [SuppressWarnings("HardcodedFileSeparator")] val sep = '/'
        val str = "$sep${key.group.replace('.', sep)}$sep${key.artifact}$sep${key.version}$sep"
        var classifier = key.classifier
        val declassified = classifier == null || classifier!!.isEmpty()
        // @checkstyle AvoidInlineConditionalsCheck (1 line)
        classifier = if (declassified) "" else ("${'-'.toString()}$classifier")
        return MavenResolver.THREAD_POOL.submit<String>(UrlResolveTask(key, str, classifier!!))
    }
}

public object MavenResolver {

    /**
     * Cache of coordinates to pom documents.
     *
     * @checkstyle AnonInnerLengthCheck (2 lines)
     */
    public val POM_CACHE: Cache<Coordinate, Future<String>> = PomCache()
    /**
     *
     */
    public val THREAD_POOL: ExecutorService = Executors.newCachedThreadPool(DaemonThreadFactory())
    /**
     *
     */
    public val RESPONSE_EMPTY: ByteArray = ByteArray(0)
    /**
     *
     */
    private var REPOSITORIES: MutableCollection<String>
    /**
     *
     */
    var RESOURCE_BUNDLE: ResourceBundle
    /**
     *
     */
    private var RE_TRAILING_SLASH: Pattern

    /**
     * Cache of coordinates to base urls.
     *
     * @checkstyle AnonInnerLengthCheck (4 lines)
     */
    SuppressWarnings("MismatchedQueryAndUpdateOfCollection")
    NonNls
    private val URL_CACHE = UrlCache()

    init {
        val name = javaClass<MavenResolver>().getName()
        RESOURCE_BUNDLE = ResourceBundle.getBundle(name)
        RE_TRAILING_SLASH = Pattern.compile("/$")
        REPOSITORIES = LinkedHashSet<String>()
        addRepository("http://oss.jfrog.org/oss-snapshot-local")
        addRepository("http://repo.maven.apache.org/maven2")
        addRepository("http://repository.jetbrains.com/all")
        addRepository("https://dl.dropboxusercontent.com/u/42745598/maven2")
    }

    /**
     * Adds a repository.
     *
     * @param url The URL
     */
    public fun addRepository(NonNls url: CharSequence) {
        REPOSITORIES.add(sanitize(url))
    }

    /**
     * Get the local repository location.
     *
     * @return The location
     */
    public fun getLocal(): String {
        val local = File(Utils.currentFile(javaClass<MavenResolver>()).getParentFile(), "bin").getPath()
        // @checkstyle MethodBodyCommentsCheck (1 line)
        //        local = System.getProperty(key, new File(System.getProperty("user.home"), ".m2/repository").getPath());
        return sanitize(local)
    }

    /**
     * Iterate over repositories. To allow for changes at runtime, the local repository is not cached
     *
     * @return The list of repositories ordered by priority
     */
    public fun getRepositories(): Iterable<String> {
        val repositories = LinkedHashSet<String>(1 + REPOSITORIES.size())
        try {
            repositories.add(File(getLocal()).toURI().toURL().toExternalForm())
        } catch (ex: MalformedURLException) {
            LOG.log(Level.SEVERE, null, ex)
        }

        repositories.addAll(REPOSITORIES)
        return Collections.unmodifiableCollection<String>(repositories)
    }

    /**
     * Resolve an artifact with packaging.
     *
     * @param coordinate The maven coordinate
     * @param packaging The packaging
     * @return The artifact URL ready for download
     * @throws java.io.FileNotFoundException if unresolvable
     */
    NonNls
    throws(javaClass<FileNotFoundException>())
    public fun resolve(coordinate: Coordinate, NonNls packaging: String): String? {
        return "${resolve(coordinate)}.$packaging"
    }

    /**
     * Resolve a coordinate to a URL.
     *
     * @param coordinate The maven coordinate
     * @return The absolute basename of the project coordinate (without the packaging element)
     * @throws java.io.FileNotFoundException if unresolvable
     */
    throws(javaClass<FileNotFoundException>())
    public fun resolve(coordinate: Coordinate): String {
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolve.url"), coordinate)
        try {
            val future = URL_CACHE[coordinate]
            if (future != null) {
                val base = future.get()
                if (base != null) {
                    return base
                }
            }
        } catch (ex: InterruptedException) {
            LOG.log(Level.SEVERE, null, ex)
        } catch (ex: ExecutionException) {
            LOG.log(Level.SEVERE, null, ex)
        }

        val msg = MessageFormat.format(RESOURCE_BUNDLE.getString("resolve.url.fail"), coordinate)
        throw FileNotFoundException(msg)
    }

    /**
     * Resolve a pom to an InputStream.
     *
     * @param coordinate The maven coordinate
     * @return An InputStream for the given coordinates
     */
    public fun resolvePomStream(coordinate: Coordinate): InputStream? {
        try {
            val bytes = resolvePom(coordinate)
            if (bytes.size != 0) {
                return BufferedInputStream(ByteArrayInputStream(bytes))
            }
        } catch (ex: ExecutionException) {
            LOG.log(Level.SEVERE, null, ex)
        } catch (ex: InterruptedException) {
            LOG.log(Level.SEVERE, null, ex)
        }

        return null
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
    throws(javaClass<ExecutionException>(), javaClass<InterruptedException>())
    private fun resolvePom(coordinate: Coordinate): ByteArray {
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("resolve.pom"), coordinate)
        val pomFuture = POM_CACHE[coordinate]
        if (pomFuture != null) {
            val pom = pomFuture.get()
            if (pom != null) {
                return pom.toByteArray()
            }
        }
        return RESPONSE_EMPTY
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
    private fun sanitize(url: CharSequence): String {
        return RE_TRAILING_SLASH.matcher(url).replaceAll("")
    }
}

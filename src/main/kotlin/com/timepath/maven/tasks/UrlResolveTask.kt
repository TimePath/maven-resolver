package com.timepath.maven.tasks

import com.timepath.IOUtils
import com.timepath.XMLUtils
import com.timepath.maven.Constants
import com.timepath.maven.MavenResolver
import com.timepath.maven.PersistentCache
import com.timepath.maven.model.Coordinate
import java.io.FileNotFoundException
import java.io.IOException
import java.text.MessageFormat
import java.util.ResourceBundle
import java.util.concurrent.Callable
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.logging.Level
import java.util.logging.Logger
import javax.xml.parsers.ParserConfigurationException
import org.w3c.dom.Node
import org.xml.sax.SAXException
import java.net.URI
import org.jetbrains.annotations.NonNls

/**
 * Task for resolving addresses of artifacts.
 *
 * @author TimePath
 * @version $Id$
 */
public class UrlResolveTask
/**
 * Create a new URL resolve task.
 *
 * @param key The coordinate
 * @param fragment The fragment to append to the base URL
 * @param classifier The artifact classifier
 * @checkstyle HiddenFieldCheck (4 lines)
 */
(
        /**
         *
         */
        transient private val key: Coordinate,
        /**
         *
         */
        transient private val fragment: String,
        /**
         *
         */
        transient private val classifier: String) : Callable<String> {

    override fun call(): String? {
        val url = this.tryAll()
        if (url == null) {
            LOG.log(Level.WARNING, RESOURCE_BUNDLE.getString("resolve.url.fail"), this.key)
        } else {
            PersistentCache.set(this.key, url)
        }
        return url
    }

    /**
     * Resolves a coordinate using a repository.
     *
     * @param repository The repository
     * @return The URL
     */
    private fun resolve(NonNls repository: String): String? {
        val base = repository + this.fragment
        val snapshot = this.key.version.endsWith(Constants.SUFFIX_SNAPSHOT)
        // @checkstyle AvoidInlineConditionalsCheck (2 lines)
        return if (snapshot)
            this.resolveSnapshot(base)
        else
            this.resolveRelease(base)
    }

    /**
     * Resolve a non-snapshot build.
     *
     * @param base The base URL
     * @return The full URL, or null
     */
    SuppressWarnings("PMD.OnlyOneReturn")
    private fun resolveRelease(NonNls base: String): String? {
        [NonNls] val test = MessageFormat.format("{0}{1}-{2}{3}", base, this.key.artifact, this.key.version, this.classifier)
        if (!MavenResolver.POM_CACHE.getBackingMap().containsKey(this.key)) {
            // @checkstyle MethodBodyCommentsCheck (1 line)
            // Test it with the pom
            val pom = try {
                URI(test + Constants.SUFFIX_POM).toURL().readText()
            } catch(ignored: IOException) {
                return null
            }
            // @checkstyle MethodBodyCommentsCheck (1 line)
            // Cache the pom since we already have it
            MavenResolver.POM_CACHE.put(this.key, makeFuture(pom))
        }
        return test
    }

    /**
     * Resolve a snapshot build.
     *
     * @param base The base URL
     * @return The full URL, or null
     * @checkstyle WhitespaceAroundCheck (3 lines)
     * @checkstyle ReturnCountCheck (2 lines)
     */
    SuppressWarnings("PMD.OnlyOneReturn", "PMD.NPathComplexity")
    private fun resolveSnapshot(NonNls base: String): String? {
        try {
            if (base.startsWith("file:")) {
                // TODO: Handle metadata when using REPO_LOCAL
                return null
            }
            val metadata = XMLUtils.rootNode(IOUtils.openStream("${base}maven-metadata.xml"), "metadata")
            val snapshot = XMLUtils.last<Node>(XMLUtils.getElements(metadata, "versioning/snapshot"))
            if (snapshot == null) {
                return null
            }
            val timestamp = XMLUtils.get(snapshot, "timestamp")
            val buildNumber = XMLUtils.get(snapshot, "buildNumber")
            val version = this.key.version
            [NonNls] val versionNumber = version.substring(0, version.lastIndexOf(Constants.SUFFIX_SNAPSHOT))
            // @checkstyle AvoidInlineConditionalsCheck (2 lines)
            val versionSuffix = if ((buildNumber == null))
                Constants.SUFFIX_SNAPSHOT
            else
                ""
            return MessageFormat.format("{0}{1}-{2}{3}{4}{5}", base, this.key.artifact, versionNumber + versionSuffix, // @checkstyle AvoidInlineConditionalsCheck (2 lines)
                    if ((timestamp == null)) "" else ('-' + timestamp), if ((buildNumber == null)) "" else ('-' + buildNumber), this.classifier)
        } catch (ignored: FileNotFoundException) {
            LOG.log(Level.WARNING, RESOURCE_BUNDLE.getString("resolve.pom.fail.version"), array<Any>(this.key, base))
        } catch (ex: IOException) {
            val msg = MessageFormat.format(RESOURCE_BUNDLE.getString("resolve.pom.fail"), this.key)
            LOG.log(Level.WARNING, msg, ex)
        } catch (ex: ParserConfigurationException) {
            val msg = MessageFormat.format(RESOURCE_BUNDLE.getString("resolve.pom.fail"), this.key)
            LOG.log(Level.WARNING, msg, ex)
        } catch (ex: SAXException) {
            val msg = MessageFormat.format(RESOURCE_BUNDLE.getString("resolve.pom.fail"), this.key)
            LOG.log(Level.WARNING, msg, ex)
        }

        return null
    }

    /**
     * Try all repositories.
     *
     * @return A URL, or null
     */
    private fun tryAll(): String? {
        var url: String? = null
        for (repository in MavenResolver.getRepositories()) {
            url = this.resolve(repository)
            if (url != null) {
                break
            }
        }
        return url
    }

    companion object {

        /**
         *
         */
        private val LOG: Logger = Logger.getLogger(javaClass<UrlResolveTask>().getName())
        /**
         *
         */
        private val RESOURCE_BUNDLE: ResourceBundle = ResourceBundle.getBundle(javaClass<MavenResolver>().getName())

        /**
         * Wrap a string in a {@link java.util.concurrent.Future}.
         *
         * @param document The document to store
         * @return A future representing that document
         */
        public fun makeFuture(document: String): Future<String> {
            val future = FutureTask(Runnable { }, document)
            future.run()
            return future
        }
    }
}

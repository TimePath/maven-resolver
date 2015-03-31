// @checkstyle HeaderCheck (1 line)
package com.timepath.maven.tasks

import com.timepath.maven.MavenResolver
import com.timepath.maven.model.Coordinate
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URI
import java.util.ResourceBundle
import java.util.concurrent.Callable
import java.util.logging.Level
import java.util.logging.Logger

// @checkstyle JavadocTagsCheck (5 lines)

/**
 * Task for resolving project files.
 *
 * @author TimePath
 * @version $Id$
 */
public class PomResolveTask
/**
 * Public ctor.
 * @param coordinate The key
 */
(
        /**
         *
         */
        transient private val key: Coordinate) : Callable<String> {

    throws(javaClass<FileNotFoundException>())
    override fun call() = try {
        MavenResolver.resolve(this.key, "pom")!!.let { URI(it).toURL().readText() }
    } catch(ignored: IOException) {
        LOG.log(Level.WARNING, RESOURCE_BUNDLE.getString("resolve.pom.fail"), this.key)
        null
    }

    companion object {

        /**
         *
         */
        public val LOG: Logger = Logger.getLogger(javaClass<PomResolveTask>().getName())

        /**
         *
         */
        public val RESOURCE_BUNDLE: ResourceBundle = ResourceBundle.getBundle(javaClass<MavenResolver>().getName())
    }
}

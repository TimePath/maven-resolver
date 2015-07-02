package com.timepath.maven.tasks

import com.timepath.XMLUtils
import com.timepath.maven.Package
import com.timepath.maven.model.Exclusion
import org.w3c.dom.Node
import java.util.HashSet
import java.util.concurrent.Callable
import java.util.logging.Level
import java.util.logging.Logger

/**
 * Download resolve task.
 */
public class DownloadResolveTask
/**
 * Public ctor.
 *
 * @param pkg The package
 * @param data A dependency node
 */
(
        /**
         *
         */
        transient private val pkg: Package,
        /**
         *
         */
        transient private val data: Node) : Callable<Set<Package>> {

    throws(Exception::class)
    override fun call(): Set<Package> {
        val depDownloads = HashSet<Package>()
        try {
            transitives@ for (depDownload in this.pkg.getDownloads()) {
                for (exNode in XMLUtils.getElements(this.data, "exclusions/exclusion")) {
                    val exclusion = Exclusion(exNode)
                    if (exclusion.matches(depDownload)) {
                        continue@transitives
                    }
                }
                depDownloads.add(depDownload)
            }
        } catch (ex: IllegalArgumentException) {
            LOG.log(Level.SEVERE, null, ex)
        } catch (ex: UnsupportedOperationException) {
            LOG.log(Level.SEVERE, null, ex)
        }

        return depDownloads
    }

    companion object {

        /**
         * The logger.
         */
        private val LOG: Logger = Logger.getLogger(javaClass<DownloadResolveTask>().getName())

    }
}

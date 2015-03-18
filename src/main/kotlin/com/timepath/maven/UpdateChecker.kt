// @checkstyle HeaderCheck (1 line)
package com.timepath.maven

import com.timepath.FileUtils
import com.timepath.maven.model.Coordinate
import java.io.File
import java.io.IOException
import java.security.NoSuchAlgorithmException
import java.text.MessageFormat
import java.util.HashSet
import java.util.Locale
import java.util.ResourceBundle
import java.util.logging.Level
import java.util.logging.Logger
import org.jetbrains.annotations.NonNls

/**
 * Utility for keeping artifacts in sync.
 *
 * @checkstyle JavadocTagsCheck (1 line)
 * @author TimePath
 * @version $Id$
 */
public class UpdateChecker
/**
 * Private ctor.
 */
private() {
    class object {
        public val RESOURCE_BUNDLE: ResourceBundle = ResourceBundle.getBundle(javaClass<UpdateChecker>().getName())
        private val LOG = Logger.getLogger(javaClass<UpdateChecker>().getName())

        /**
         * Check the integrity of a single package.
         *
         * @param pkg The package
         * @return True if matches SHA1 checksum
         */
        public fun verify(pkg: Package): Boolean = verify(pkg, getFile(pkg))

        /**
         * Check the integrity of a single package.
         *
         * @param pkg The package
         * @param existing The existing file to check
         * @return True if matches SHA1 checksum
         * @checkstyle ReturnCountCheck (3 lines)
         */
        SuppressWarnings("PMD.OnlyOneReturn")
        public fun verify(pkg: Package, existing: File): Boolean {
            LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("integrity.check"), pkg)
            try {
                if (!existing.exists()) {
                    LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("integrity.missing"), existing)
                    return false
                }
                [NonNls] val expected = getChecksum(pkg, Constants.ALGORITHM)
                [NonNls] val actual = FileUtils.checksum(existing, Constants.ALGORITHM)
                if (actual != expected) {
                    LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("integrity.mismatch"), array(existing, expected, actual))
                    return false
                }
            } catch (ex: IOException) {
                LOG.log(Level.SEVERE, null, ex)
                return false
            } catch (ex: NoSuchAlgorithmException) {
                LOG.log(Level.SEVERE, null, ex)
                return false
            }

            LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("integrity.match"), pkg)
            return true
        }

        /**
         * Checks for updates.
         *
         * @param parent The package
         * @return All transitive updates, flattened. Includes self
         */
        public fun getUpdates(parent: Package): Set<Package> {
            val downloads = parent.getDownloads()
            LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("depends.all"), array(parent, downloads.toString()))
            val outdated = HashSet<Package>()
            for (child in downloads) {
                if (verify(child)) {
                    continue
                }
                LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("depends.outdated"), child)
                outdated.add(child)
            }
            return outdated
        }

        /**
         * Get the download URL of an artifact.
         *
         * @param pkg The package
         * @return A URL pointing to the jar artifact
         */
        NonNls
        public fun getDownloadURL(pkg: Package): String {
            // @checkstyle MethodBodyCommentsCheck (2 line)
            // @checkstyle TodoCommentCheck (1 line)
            // TODO: other package types
            // @checkstyle StringLiteralsConcatenationCheck (1 line)
            return "${pkg.baseurl}.jar"
        }

        /**
         * Get the local path to store artifacts for the given package.
         *
         * @param pkg The package
         * @return The local path
         */
        public fun getProgramDirectory(pkg: Package): String {
            val coordinate = pkg.coordinate
            return MessageFormat.format("{0}/{1}/{2}/{3}",
                    MavenResolver.getLocal(),
                    coordinate.group.replace('.', '/'),
                    coordinate.artifact,
                    coordinate.version)
        }

        /**
         * Get the download URL of the checksum for an artifact.
         *
         * @param pkg The package
         * @param algorithm The algorithm to use
         * @return The URL containing the requested package checksum
         */
        NonNls
        public fun getChecksumURL(pkg: Package, NonNls algorithm: String): String {
            // @checkstyle MethodBodyCommentsCheck (2 lines)
            // @checkstyle TodoCommentCheck (1 line)
            // TODO: other package types
            return "${pkg.baseurl}.jar.${algorithm.toLowerCase(Locale.ROOT)}"
        }

        /**
         * Get the file name of the artifact.
         *
         * @param pkg The package
         * @return The local {@link java.io.File} name of the package
         */
        public fun getFileName(pkg: Package): String {
            // TODO: avoid network where possible
            return FileUtils.name(getDownloadURL(pkg))
        }

        /**
         * Get the checksum of the artifact.
         *
         * @param pkg The package
         * @param algorithm The algorithm to use
         * @return The checksum for the requested checksum
         */
        public fun getChecksum(pkg: Package, NonNls algorithm: String): String? {
            return pkg.getChecksum(algorithm.toLowerCase(Locale.ROOT))
        }

        /**
         * Convenience.
         *
         * @param pkg The package
         * @return The local {@link java.io.File} for the given package
         */
        public fun getFile(pkg: Package): File = File(getProgramDirectory(pkg), getFileName(pkg))

        /**
         * Convenience.
         *
         * @param pkg The package
         * @param algorithm The algorithm to use
         * @return The local {@link java.io.File} containing the requested checksum
         */
        public fun getChecksumFile(pkg: Package, algorithm: String): File {
            return File(getProgramDirectory(pkg), FileUtils.name(getChecksumURL(pkg, algorithm)))
        }
    }

}

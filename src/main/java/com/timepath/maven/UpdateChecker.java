// @checkstyle HeaderCheck (1 line)
package com.timepath.maven;

import com.timepath.FileUtils;
import com.timepath.maven.model.Coordinate;
import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility for keeping artifacts in sync.
 *
 * @checkstyle JavadocTagsCheck (1 line)
 * @author TimePath
 * @version $Id$
 */
public final class UpdateChecker {

    /**
     *
     */
    public static final ResourceBundle RESOURCE_BUNDLE = ResourceBundle
            .getBundle(UpdateChecker.class.getName());
    /**
     *
     */
    private static final Logger LOG = Logger
            .getLogger(UpdateChecker.class.getName());

    /**
     * Private ctor.
     */
    private UpdateChecker() {
    }

    /**
     * Check the integrity of a single package.
     *
     * @param pkg The package
     * @return True if matches SHA1 checksum
     */
    public static boolean verify(@NotNull final Package pkg) {
        return verify(pkg, getFile(pkg));
    }

    /**
     * Check the integrity of a single package.
     *
     * @param pkg The package
     * @param existing The existing file to check
     * @return True if matches SHA1 checksum
     * @checkstyle ReturnCountCheck (3 lines)
     */
    @SuppressWarnings("PMD.OnlyOneReturn")
    public static boolean verify(@NotNull final Package pkg,
                                 @NotNull final File existing) {
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("integrity.check"), pkg);
        try {
            if (!existing.exists()) {
                LOG.log(
                        Level.INFO,
                        RESOURCE_BUNDLE.getString("integrity.missing"),
                        existing
                );
                return false;
            }
            @NonNls @Nullable final String expected =
                    getChecksum(pkg, Constants.ALGORITHM);
            @NonNls @NotNull final String actual =
                    FileUtils.checksum(existing, Constants.ALGORITHM);
            if (!actual.equals(expected)) {
                LOG.log(
                        Level.INFO,
                        RESOURCE_BUNDLE.getString("integrity.mismatch"),
                        new Object[]{existing, expected, actual}
                );
                return false;
            }
        } catch (final IOException | NoSuchAlgorithmException ex) {
            LOG.log(Level.SEVERE, null, ex);
            return false;
        }
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("integrity.match"), pkg);
        return true;
    }

    /**
     * Checks for updates.
     *
     * @param parent The package
     * @return All transitive updates, flattened. Includes self
     */
    @NotNull
    public static Set<Package> getUpdates(@NotNull final Package parent) {
        final Set<Package> downloads = parent.getDownloads();
        LOG.log(
                Level.INFO, RESOURCE_BUNDLE.getString("depends.all"),
                new Object[]{parent, downloads.toString()}
        );
        @NotNull final Set<Package> outdated = new HashSet<>();
        for (@NotNull final Package child : downloads) {
            if (verify(child)) {
                continue;
            }
            LOG.log(
                    Level.INFO, RESOURCE_BUNDLE.getString("depends.outdated"),
                    child
            );
            outdated.add(child);
        }
        return outdated;
    }

    /**
     * Get the download URL of an artifact.
     *
     * @param pkg The package
     * @return A URL pointing to the jar artifact
     */
    @NonNls
    @NotNull
    public static String getDownloadURL(@NotNull final Package pkg) {
        // @checkstyle MethodBodyCommentsCheck (2 line)
        // @checkstyle TodoCommentCheck (1 line)
        // TODO: other package types
        // @checkstyle StringLiteralsConcatenationCheck (1 line)
        return pkg.getBaseurl() + ".jar";
    }

    /**
     * Get the local path to store artifacts for the given package.
     *
     * @param pkg The package
     * @return The local path
     */
    @NotNull
    public static String getProgramDirectory(@NotNull final Package pkg) {
        final Coordinate coordinate = pkg.getCoordinate();
        return MessageFormat.format(
                "{0}/{1}/{2}/{3}",
                MavenResolver.getLocal(),
                coordinate.getGroup().replace('.', '/'),
                coordinate.getArtifact(),
                coordinate.getVersion()
        );
    }

    /**
     * Get the download URL of the checksum for an artifact.
     *
     * @param pkg The package
     * @param algorithm The algorithm to use
     * @return The URL containing the requested package checksum
     */
    @NonNls
    @NotNull
    public static String getChecksumURL(
            @NotNull final Package pkg,
            @NonNls @NotNull final String algorithm) {
        // @checkstyle MethodBodyCommentsCheck (2 lines)
        // @checkstyle TodoCommentCheck (1 line)
        // TODO: other package types
        return pkg.getBaseurl() + ".jar." + algorithm.toLowerCase(Locale.ROOT);
    }

    /**
     * Get the file name of the artifact.
     *
     * @param pkg The package
     * @return The local {@link java.io.File} name of the package
     */
    @NotNull
    public static String getFileName(@NotNull final Package pkg) {
        // @checkstyle MethodBodyCommentsCheck (2 lines)
        // @checkstyle TodoCommentCheck (1 line)
        // TODO: avoid network where possible
        return FileUtils.name(getDownloadURL(pkg));
    }

    /**
     * Get the checksum of the artifact.
     *
     * @param pkg The package
     * @param algorithm The algorithm to use
     * @return The checksum for the requested checksum
     */
    @Nullable
    public static String getChecksum(@NotNull final Package pkg,
                                     @NonNls @NotNull final String algorithm) {
        return pkg.getChecksum(algorithm.toLowerCase(Locale.ROOT));
    }

    /**
     * Convenience.
     *
     * @param pkg The package
     * @return The local {@link java.io.File} for the given package
     */
    @NotNull
    public static File getFile(@NotNull final Package pkg) {
        return new File(getProgramDirectory(pkg), getFileName(pkg));
    }

    /**
     * Convenience.
     *
     * @param pkg The package
     * @param algorithm The algorithm to use
     * @return The local {@link java.io.File} containing the requested checksum
     */
    @NotNull
    public static File getChecksumFile(@NotNull final Package pkg,
                                       @NotNull final String algorithm) {
        return new File(
                getProgramDirectory(pkg),
                FileUtils.name(getChecksumURL(pkg, algorithm))
        );
    }

}

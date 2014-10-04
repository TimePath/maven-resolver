package com.timepath.maven;

import com.timepath.FileUtils;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author TimePath
 */
public class UpdateChecker {

    public static final ResourceBundle RESOURCE_BUNDLE = ResourceBundle.getBundle(UpdateChecker.class.getName());
    @NonNls
    public static final String ALGORITHM = "sha1";

    private static final Logger LOG = Logger.getLogger(UpdateChecker.class.getName());

    /**
     * Check the integrity of a single package
     *
     * @param aPackage
     * @return true if matches SHA1 checksum
     */
    public static boolean verify(@NotNull Package aPackage) {
        return verify(aPackage, getFile(aPackage));
    }

    /**
     * Check the integrity of a single package
     *
     * @param aPackage
     * @return true if matches SHA1 checksum
     */
    public static boolean verify(@NotNull Package aPackage, @NotNull File existing) {
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("integrity.check"), aPackage);
        try {
            if (!existing.exists()) {
                LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("integrity.missing"), existing);
                return false;
            }
            @NonNls @Nullable String expected = getChecksum(aPackage, ALGORITHM);
            @NonNls @NotNull String actual = FileUtils.checksum(existing, ALGORITHM);
            if (!actual.equals(expected)) {
                LOG.log(Level.INFO,
                        RESOURCE_BUNDLE.getString("integrity.mismatch"),
                        new Object[]{existing, expected, actual});
                return false;
            }
        } catch (@NotNull IOException | NoSuchAlgorithmException ex) {
            LOG.log(Level.SEVERE, null, ex);
            return false;
        }
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("integrity.match"), aPackage);
        return true;
    }

    /**
     * @param aPackage
     * @return all transitive updates, flattened. Includes self.
     */
    @NotNull
    public static Set<Package> getUpdates(@NotNull Package aPackage) {
        Set<Package> downloads = aPackage.getDownloads();
        LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("depends.all"), new Object[]{aPackage, downloads.toString()});
        @NotNull Set<Package> outdated = new HashSet<>();
        for (@NotNull Package p : downloads) {
            if (verify(p)) continue;
            LOG.log(Level.INFO, RESOURCE_BUNDLE.getString("depends.outdated"), p);
            outdated.add(p);
        }
        return outdated;
    }

    /**
     * TODO: other package types
     *
     * @param aPackage
     * @return
     */
    @NonNls
    @NotNull
    public static String getDownloadURL(@NotNull Package aPackage) {
        return aPackage.baseURL + ".jar";
    }

    @NotNull
    public static String getProgramDirectory(@NotNull Package aPackage) {
        Coordinate coordinate = aPackage.coordinate;
        return MessageFormat.format("{0}/{1}/{2}/{3}",
                MavenResolver.getLocal(), coordinate.groupId.replace('.', '/'), coordinate.artifactId, coordinate.version);
    }

    /**
     * TODO: other package types
     *
     * @return
     */
    @NonNls
    @NotNull
    public static String getChecksumURL(@NotNull Package aPackage, @NonNls @NotNull String algorithm) {
        return aPackage.baseURL + ".jar." + algorithm.toLowerCase();
    }

    @NotNull
    public static String getFileName(@NotNull Package aPackage) {
        return FileUtils.name(getDownloadURL(aPackage)); // ??? TODO: avoid network
    }

    @Nullable
    public static String getChecksum(@NotNull Package aPackage, @NonNls @NotNull String algorithm) {
        return aPackage.getChecksum(algorithm.toLowerCase());
    }

    @NotNull
    public static File getFile(@NotNull Package aPackage) {
        return new File(getProgramDirectory(aPackage), getFileName(aPackage));
    }

    @NotNull
    public static File getChecksumFile(@NotNull Package aPackage, @NotNull String algorithm) {
        return new File(getProgramDirectory(aPackage), FileUtils.name(getChecksumURL(aPackage, algorithm)));
    }

}

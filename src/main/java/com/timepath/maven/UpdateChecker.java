package com.timepath.maven;

import com.timepath.FileUtils;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author TimePath
 */
public class UpdateChecker {

    public static final String ALGORITHM = "sha1";

    private static final Logger LOG = Logger.getLogger(UpdateChecker.class.getName());

    /**
     * Check the integrity of a single package
     *
     * @param aPackage
     * @return true if matches SHA1 checksum
     */
    public static boolean verify(Package aPackage) {
        return verify(aPackage, getFile(aPackage));
    }

    /**
     * Check the integrity of a single package
     *
     * @param aPackage
     * @return true if matches SHA1 checksum
     */
    public static boolean verify(Package aPackage, File existing) {
        LOG.log(Level.INFO, "Checking integrity of {0}", aPackage);
        try {
            if (!existing.exists()) {
                LOG.log(Level.INFO, "Don''t have {0}, reacquire", existing);
                return false;
            }
            String expected = getChecksum(aPackage, ALGORITHM);
            String actual = FileUtils.checksum(existing, ALGORITHM);
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
        LOG.log(Level.INFO, "Verified {0}", aPackage);
        return true;
    }

    /**
     * @param aPackage
     * @return all transitive updates, flattened. Includes self.
     */
    public static Set<Package> getUpdates(Package aPackage) {
        Set<Package> downloads = aPackage.getDownloads();
        LOG.log(Level.INFO, "Depends: {0} on {1}", new Object[]{aPackage, downloads.toString()});
        Set<Package> outdated = new HashSet<>();
        for (Package p : downloads) {
            if (verify(p)) continue;
            LOG.log(Level.INFO, "{0} is outdated", p);
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
    public static String getDownloadURL(Package aPackage) {
        return aPackage.baseURL + ".jar";
    }

    public static String getProgramDirectory(Package aPackage) {
        Coordinate c = aPackage.coordinate;
        return MessageFormat.format("{0}/{1}/{2}/{3}",
                MavenResolver.getLocal(), c.groupId.replace('.', '/'), c.artifactId, c.version);
    }

    /**
     * TODO: other package types
     *
     * @return
     */
    public static String getChecksumURL(Package aPackage, String algorithm) {
        return aPackage.baseURL + ".jar." + algorithm.toLowerCase();
    }

    public static String getFileName(Package aPackage) {
        return FileUtils.name(getDownloadURL(aPackage)); // ??? TODO: avoid network
    }

    public static String getChecksum(Package aPackage, String algorithm) {
        return aPackage.getChecksum(algorithm.toLowerCase());
    }

    public static File getFile(Package aPackage) {
        return new File(getProgramDirectory(aPackage), getFileName(aPackage));
    }

    public static File getChecksumFile(Package aPackage, String algorithm) {
        return new File(getProgramDirectory(aPackage), FileUtils.name(getChecksumURL(aPackage, algorithm)));
    }

}

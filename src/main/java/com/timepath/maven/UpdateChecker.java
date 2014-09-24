package com.timepath.maven;

import com.timepath.util.Cache;

import java.io.File;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.HashSet;
import java.util.Map;
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
        LOG.log(Level.INFO, "Checking integrity of {0}", aPackage);
        try {
            File existing = getFile(aPackage);
            File checksum = new File(existing.getParent(), existing.getName() + '.' + ALGORITHM);
            if (!checksum.exists() || !existing.exists()) {
                LOG.log(Level.INFO, "Don''t have {0}, reacquire", existing);
                return false;
            }
            String expected = Utils.requestPage(checksum.toURI().toString());
            String actual = Utils.checksum(existing, ALGORITHM);
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
     * Check a <b>single package</b> for updates
     *
     * @param aPackage
     * @return false if completely up to date, true if up to date or working offline
     */
    public static boolean isLatest(Package aPackage) {
        LOG.log(Level.INFO, "Checking {0} for updates...", aPackage);
        try {
            File existing = getFile(aPackage);
            if (!existing.exists()) {
                LOG.log(Level.INFO, "Don''t have {0}, not latest", existing);
                return false;
            }
            String expected = getChecksum(aPackage, ALGORITHM);
            String actual = Utils.checksum(existing, ALGORITHM);
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
        LOG.log(Level.INFO, "{0} is up to date", aPackage);
        return true;
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
        return MessageFormat.format("{0}/{1}/{2}/{3}", MavenResolver.getLocal(), aPackage.gid.replace('.', '/'), aPackage.aid, aPackage.ver);
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
        return Utils.name(getDownloadURL(aPackage));
    }

    public static String getChecksum(Package aPackage, String algorithm) {
        return aPackage.getChecksum(algorithm.toLowerCase());
    }

    public static File getFile(Package aPackage) {
        return new File(getProgramDirectory(aPackage), getFileName(aPackage));
    }

    public static File getChecksumFile(Package aPackage, String algorithm) {
        return new File(getProgramDirectory(aPackage), Utils.name(getChecksumURL(aPackage, algorithm)));
    }

}

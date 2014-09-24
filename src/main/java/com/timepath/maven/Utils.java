package com.timepath.maven;

import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;
import java.util.zip.GZIPInputStream;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;

public class Utils {

    public static final Preferences SETTINGS = Preferences.userRoot().node("timepath");
    public static final File CURRENT_FILE = locate(Utils.class);
    private static final Logger LOG = Logger.getLogger(Utils.class.getName());

    private Utils() {
    }

    public static String pprint(Source xmlInput, int indent) {
        try {
            StringWriter stringWriter = new StringWriter();
            StreamResult xmlOutput = new StreamResult(stringWriter);
            TransformerFactory transformerFactory = TransformerFactory.newInstance();
            transformerFactory.setAttribute("indent-number", indent);
            Transformer transformer = transformerFactory.newTransformer();
            transformer.setOutputProperty(OutputKeys.INDENT, "yes");
            transformer.transform(xmlInput, xmlOutput);
            return xmlOutput.getWriter().toString();
        } catch (IllegalArgumentException | TransformerException ex) {
            LOG.log(Level.SEVERE, null, ex);
        }
        return null;
    }

    public static String checksum(File file, String algorithm) throws IOException, NoSuchAlgorithmException {
        FileChannel fileChannel = new RandomAccessFile(file, "r").getChannel();
        MappedByteBuffer buf = fileChannel.map(FileChannel.MapMode.READ_ONLY, 0, fileChannel.size());
        return checksum(buf, algorithm);
    }

    public static String checksum(ByteBuffer buf, String algorithm) throws NoSuchAlgorithmException {
        MessageDigest md = MessageDigest.getInstance(algorithm);
        md.update(buf);
        byte[] cksum = md.digest();
        StringBuilder sb = new StringBuilder(cksum.length * 2);
        for (byte aCksum : cksum) {
            sb.append(Integer.toString((aCksum & 0xFF) + 256, 16).substring(1));
        }
        return sb.toString();
    }

    private static final ConnectionSettings CONNECTION_SETTINGS_IDENTITY = new ConnectionSettings() {
        @Override
        public void apply(URLConnection u) {

        }
    };

    public static interface ConnectionSettings {
        void apply(URLConnection u);
    }

    /**
     * @param s the URL
     * @return a URLConnection for s
     * @throws java.io.IOException
     */
    public static URLConnection requestConnection(String s) throws IOException {
        return requestConnection(s, CONNECTION_SETTINGS_IDENTITY);
    }

    /**
     * @param s the URL
     * @return a URLConnection for s
     * @throws IOException
     */
    public static URLConnection requestConnection(String s, ConnectionSettings settings) throws IOException {
        LOG.log(Level.INFO, "Requesting: {0}", s);
        URL url;
        try {
            url = new URI(s).toURL();
        } catch (URISyntaxException | MalformedURLException e) {
            throw new IOException("Malformed URI: " + s);
        }
        int redirectLimit = 5;
        int retryLimit = 2;
        redirect:
        for (int i = 0; i < redirectLimit + 1; i++) {
            for (int j = 0; j < retryLimit + 1; j++) {
                try {
                    URLConnection connection = url.openConnection();
                    connection.setUseCaches(true);
                    connection.setConnectTimeout(10 * 1000); // Initial
                    connection.setReadTimeout(10 * 1000); // During transfer
                    if (connection instanceof HttpURLConnection) { // Includes HttpsURLConnection
                        HttpURLConnection conn = ((HttpURLConnection) connection);
                        conn.setRequestProperty("Accept-Encoding", "gzip,deflate");
                        conn.setInstanceFollowRedirects(true);
                        settings.apply(conn);
                        int status = conn.getResponseCode();
                        int range = status / 100;
                        if (status == HttpURLConnection.HTTP_MOVED_TEMP
                                || status == HttpURLConnection.HTTP_MOVED_PERM
                                || status == HttpURLConnection.HTTP_SEE_OTHER) {
                            s = conn.getHeaderField("Location");
                            conn.disconnect();
                            continue redirect;
                        } else if (range == 4) { // TODO: 409 artifactory messages: snapshot/release handling policy
                            j = retryLimit; // Stop
                            throw new FileNotFoundException("HTTP 4xx: " + s);
                        } else if (range != 2 && range != 5) {
                            LOG.log(Level.WARNING, "Unexpected response from {0}: {1}", new Object[]{s, status});
                        }
                    } else {
                        settings.apply(connection);
                    }
                    return connection;
                } catch (IOException e) {
                    if (j == retryLimit) throw e;
                }
            }
        }
        throw new IOException("Too many redirects");
    }

    public static InputStream openStream(URLConnection conn) throws IOException {
        String encoding = conn.getHeaderField("Content-Encoding");
        InputStream stream = conn.getInputStream();
        if (encoding != null) {
            LOG.log(Level.FINE, "Decompressing: {0} ({1})", new Object[]{conn.getURL(), encoding});
            switch (encoding.toLowerCase()) {
                case "gzip":
                    return new GZIPInputStream(stream);
                case "deflate":
                    return new InflaterInputStream(stream, new Inflater(true));
            }
        }
        return stream;
    }

    public static InputStream openStream(String s) throws IOException {
        return openStream(requestConnection(s));
    }

    /**
     * @param s the URL
     * @return the page text, or null
     */
    public static String requestPage(String s) {
        URLConnection connection;
        try {
            connection = requestConnection(s);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "Unable to resolve: {0}", s);
            return null;
        }
        try (BufferedReader br = new BufferedReader(new InputStreamReader(openStream(connection), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder(Math.max(connection.getContentLength(), 0));
            for (String line; (line = br.readLine()) != null; ) sb.append('\n').append(line);
            return sb.substring(Math.min(1, sb.length()));
        } catch (IOException ignored) {
            return null;
        }
    }

    public static String name(String s) {
        return s.substring(s.lastIndexOf('/') + 1);
    }

    private static File locate(Class<?> clazz) {
        String encoded = clazz.getProtectionDomain().getCodeSource().getLocation().getPath();
        try {
            return new File(URLDecoder.decode(encoded, StandardCharsets.UTF_8.name()));
        } catch (UnsupportedEncodingException ex) {
            LOG.log(Level.WARNING, null, ex);
        }
        String ans = System.getProperty("user.dir") + File.separator;
        String cmd = System.getProperty("sun.java.command");
        int idx = cmd.lastIndexOf(File.separator);
        return new File(ans + ((idx < 0) ? "" : cmd.substring(0, idx + 1)));
    }
}

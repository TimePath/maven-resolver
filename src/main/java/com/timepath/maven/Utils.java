package com.timepath.maven;

import javax.xml.transform.*;
import javax.xml.transform.stream.StreamResult;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.prefs.Preferences;

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

    public static String loadPage(URL u) {
        LOG.log(Level.INFO, "loadPage: {0}", u);
        if (u == null) return null;
        try {
            URLConnection connection = u.openConnection();
            if (connection instanceof HttpURLConnection) {
                HttpURLConnection http = ((HttpURLConnection) connection);
                if (http.getResponseCode() == HttpURLConnection.HTTP_MOVED_TEMP) {
                    connection = new URL(http.getHeaderField("Location")).openConnection();
                }
            }
            try (InputStreamReader isr = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                BufferedReader br = new BufferedReader(isr);
                StringBuilder sb = new StringBuilder(Math.max(connection.getContentLength(), 0));
                for (String line; (line = br.readLine()) != null; ) sb.append('\n').append(line);
                if (sb.length() < 1) return null;
                return sb.substring(1);
            }
        } catch (FileNotFoundException e) {
            LOG.log(Level.FINE, "Exception in loadPage", e);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "Exception in loadPage", e);
        }
        return null;
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

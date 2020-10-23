package org.spigotmc.builder;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class Utils {
    private Utils() {
        throw new IllegalStateException("Utility class");
    }

    public static boolean doesCommandFail(File workingDir, String cmd, String... args) {
        try {
            return runCommand(workingDir, cmd, args) != 0;
        } catch (IOException ex) {
            ex.printStackTrace();
        }

        return true;
    }

    public static int runCommand(File workingDir, String cmd, String... args) throws IOException {
        CommandLine cmdLine = new CommandLine(Objects.requireNonNull(cmd));

        for (String arg : args) {
            if (arg != null && !arg.isEmpty()) {
                cmdLine.addArgument(arg);
            }
        }

        DefaultExecutor executor = new DefaultExecutor();
        executor.setWatchdog(new ExecuteWatchdog(60000));
        executor.setWorkingDirectory(Objects.requireNonNull(workingDir));

        Map<String, String> env = new HashMap<>(System.getenv());

        env.put("JAVA_HOME", System.getProperty("java.home"));

        if (!env.containsKey("MAVEN_OPTS")) {
            env.put("MAVEN_OPTS", "-Xmx1024M");
        }

        if (!env.containsKey("_JAVA_OPTIONS")) {
            StringBuilder javaOptions = new StringBuilder("-Djdk.net.URLClassPath.disableClassPathURLCheck=true");

            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg.startsWith("-Xmx")) {
                    javaOptions.append(" ")
                            .append(arg);
                }
            }

            env.put("_JAVA_OPTIONS", javaOptions.toString());
        }

        return executor.execute(cmdLine, env);
    }

    public static void disableHttpsCertificateCheck() {
        // This globally disables certificate checking
        // http://stackoverflow.com/questions/19723415/java-overriding-function-to-disable-ssl-certificate-check
        try {
            TrustManager[] trustAllCerts = new TrustManager[] {
                    new X509TrustManager() {
                        @Override
                        public X509Certificate[] getAcceptedIssuers() {
                            return new X509Certificate[0];
                        }

                        @Override
                        public void checkClientTrusted(X509Certificate[] certs, String authType) {
                        }

                        @Override
                        public void checkServerTrusted(X509Certificate[] certs, String authType) {
                        }
                    }
            };

            // Trust SSL certs
            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            // Trust host names
            HostnameVerifier allHostsValid = (hostname, session) -> true;
            HttpsURLConnection.setDefaultHostnameVerifier(allHostsValid);
        } catch (NoSuchAlgorithmException | KeyManagementException ex) {
            System.err.println("Failed to disable https certificate check");
            ex.printStackTrace();
        }
    }
}
package org.spigotmc.builder;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
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
    public static boolean doesCommandFail(@NotNull File workingDir, @NotNull String cmd, @Nullable String... args) {
        try {
            return runCommand(workingDir, cmd, args) != 0;
        } catch (IOException ignore) {
        }

        return true;
    }

    public static int runCommand(@NotNull File workingDir, @NotNull String cmd, @Nullable String... args) throws IOException {
        CommandLine cmdLine = new CommandLine(Objects.requireNonNull(cmd));

        for (String arg : args) {
            if (arg != null && !arg.isEmpty()) {
                cmdLine.addArgument(arg);
            }
        }

        DefaultExecutor executor = new DefaultExecutor();
        executor.setWorkingDirectory(Objects.requireNonNull(workingDir));

        Map<String, String> env = new HashMap<>(System.getenv());

        env.put("JAVA_HOME", System.getProperty("java.home"));

        if (!env.containsKey("MAVEN_OPTS")) {
            env.put("MAVEN_OPTS", "-Xmx1G");
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

    /**
     * This globally disables certificate checking
     * https://stackoverflow.com/a/26448998/9346616
     *
     * @throws NoSuchAlgorithmException Thrown by {@link SSLContext#getInstance(String)} with {@code protocol = "SSL"}
     * @throws KeyManagementException   Thrown by {@link SSLContext#init(KeyManager[], TrustManager[], SecureRandom)}
     */
    public static void disableHttpsCertificateChecks() throws NoSuchAlgorithmException, KeyManagementException {
        TrustManager[] trustAllCerts = new TrustManager[] {
                new X509TrustManager() {
                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
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
    }
}
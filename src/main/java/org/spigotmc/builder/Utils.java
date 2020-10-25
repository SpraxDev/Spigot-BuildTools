package org.spigotmc.builder;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Utils {
    private Utils() {
        throw new IllegalStateException("Utility class");
    }

    public static boolean doesCommandFail(File workingDir, String cmd, String... args) {
        try {
            return runCommand(workingDir, cmd, args) != 0;
        } catch (IOException ignore) {
        }

        return true;
    }

    public static int runCommand(File workingDir, String cmd, String... args) throws IOException {
        return runCommand(workingDir, cmd, false, false, args);
    }

    public static int runCommand(File workingDir, String cmd, boolean silentOut, boolean silentErr, String... args) throws IOException {
        CommandLine cmdLine = new CommandLine(Objects.requireNonNull(cmd));

        for (String arg : args) {
            if (arg != null && !arg.isEmpty()) {
                cmdLine.addArgument(arg);
            }
        }

        DefaultExecutor executor = new DefaultExecutor();
        executor.setWatchdog(new ExecuteWatchdog(60000));
        executor.setWorkingDirectory(Objects.requireNonNull(workingDir));

        if (silentOut || silentErr) {
            executor.setStreamHandler(new PumpStreamHandler(silentOut ? null : System.out, silentErr ? null : System.err));
        }

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

    public static void unzip(File zipFile, File targetFolder) throws IOException {
        unzip(zipFile, targetFolder, null);
    }

    public static void unzip(File zipFile, File targetFolder, Predicate<String> filter) throws IOException {
        Path targetPath = targetFolder.getAbsoluteFile().toPath().normalize();
        Files.createDirectories(targetPath);

        try (ZipFile zip = new ZipFile(zipFile)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();

                if (filter != null && !filter.test(entry.getName())) {
                    continue;
                }

                File outFile = new File(targetPath.toFile(), entry.getName());

                if (!outFile.toPath().normalize().startsWith(targetPath))
                    throw new IllegalStateException("Bad zip entry(=" + entry.getName() + ") - malicious archive?");  // e.g. containing '..'

                if (!entry.isDirectory()) {
                    if (outFile.getParentFile() != null) {
                        Files.createDirectories(outFile.getParentFile().toPath());
                    }

                    try (InputStream in = zip.getInputStream(entry)) {
                        Files.copy(in, outFile.toPath());
                    }
                } else {
                    Files.createDirectories(outFile.toPath());
                }
            }
        }
    }

    /**
     * This is an alias for:
     * <p>
     * {@code runTasksMultiThreaded(Math.max(2, Runtime.getRuntime().availableProcessors()), tasks)}
     *
     * @see #runTasksMultiThreaded(int, MultiThreadedTask...)
     */
    public static int runTasksMultiThreaded(MultiThreadedTask... tasks) throws Exception {
        return runTasksMultiThreaded(Math.max(2, Runtime.getRuntime().availableProcessors()), tasks);
    }

    /**
     * Runs the given tasks in multiple threads and blocks the calling
     * thread until all the tasks have been executed or aborts
     * <p>
     * If a task throws an {@link Exception}, this method will throw it
     * after all the other tasks finished (only from the last task throwing one!).
     *
     * @param threadCount The amount of threads to use for this task (uses {@code Math.min(threadCount, tasks.length)})
     * @param tasks       The tasks to be executed
     *
     * @return {@code 0} if all tasks ran successfully, else the status code of the last failed task
     *
     * @throws Exception             The Exception thrown by the last last task throwing one
     * @throws IllegalStateException If {@code tasks.length == 0}
     */
    public static int runTasksMultiThreaded(int threadCount, MultiThreadedTask... tasks) throws Exception {
        if (threadCount <= 0) throw new IllegalArgumentException("threadCount needs to be larger than 0");
        if (tasks.length == 0) throw new IllegalArgumentException("You have to provide tasks to execute");

        ExecutorService pool = Executors.newFixedThreadPool(Math.min(threadCount, tasks.length));

        AtomicInteger statusCode = new AtomicInteger();
        AtomicReference<Exception> exception = new AtomicReference<>();

        for (MultiThreadedTask task : tasks) {
            pool.execute(() -> {
                try {
                    int result = task.runTask();

                    if (result != 0) {
                        statusCode.set(result);
                    }
                } catch (Exception ex) {
                    exception.set(ex);
                }
            });
        }

        pool.shutdown();
        pool.awaitTermination(1, TimeUnit.HOURS);   // This *should* not be exceeded and Long.MAX_VALUE seems overkill

        // Making sure there won't be any buggy/unwanted threads left
        if (pool.shutdownNow().size() > 0) {
            throw new IllegalStateException("There are still tasks in the queue after 1 hour of execution... This doesn't look right");
        }

        if (exception.get() != null) {
            throw exception.get();
        }

        return statusCode.get();
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

    interface MultiThreadedTask {
        /**
         * @return A numeric status code (e.g. exit code)
         *
         * @throws Exception An exception that may be thrown by the task
         */
        int runTask() throws Exception;
    }
}
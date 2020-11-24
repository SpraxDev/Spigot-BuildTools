package org.spigotmc.builder;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.FetchResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class Utils {
    private static final byte[] HEX_ARRAY = "0123456789abcdef".getBytes(StandardCharsets.US_ASCII);

    public static String httpGet(@NotNull String url) throws IOException {
        URLConnection con = new URL(url).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);

        try (InputStream in = con.getInputStream()) {
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        }
    }

    public static void downloadFile(@NotNull String url, @NotNull File dest, @Nullable HashAlgo hashAlgo, @Nullable String goodHash) throws IOException {
        if (hashAlgo != null && goodHash == null) {
            hashAlgo = null;
        }

        System.out.println("Downloading '" + url + "' to '" + dest.toString() + "'...");

        byte[] data = IOUtils.toByteArray(new URL(url));
        String dataHash = hashAlgo != null ? hashAlgo.getHash(data) : null;

        if (dataHash != null && !dataHash.equalsIgnoreCase(goodHash)) {
            throw new IllegalStateException("File at '" + url + "' did not match the expected " + hashAlgo.getAlgorithm()
                    + " (Expected: " + goodHash + ")");
        }

        System.out.println("Successfully downloaded '" + url + "'" +
                (hashAlgo != null ? " (" + hashAlgo.getAlgorithm() + ": " + dataHash + ")" : ""));

        Files.createDirectories(dest.getParentFile().toPath());
        try (FileOutputStream out = new FileOutputStream(dest)) {
            IOUtils.write(data, out);
        }
    }

    public static void extractZip(@NotNull File zipFile, @NotNull File targetFolder, @Nullable Predicate<String> filter) throws IOException {
        System.out.println("Extracting '" + zipFile.getAbsolutePath() + "' to '" + targetFolder.getAbsolutePath() + "'...");

        Path targetPath = targetFolder.getAbsoluteFile().toPath().normalize();
        Files.createDirectories(targetPath);

        try (ZipFile zip = new ZipFile(zipFile)) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();

                if (filter != null && !filter.test(entry.getName())) {
                    continue;
                }

                File outFile = targetPath.resolve(entry.getName()).toFile();

                if (!outFile.toPath().normalize().startsWith(targetPath))
                    throw new IllegalStateException("Bad zip entry(=" + entry.getName() + ") - malicious archive?");  // e.g. containing '..'

                if (entry.isDirectory()) {
                    Files.createDirectories(outFile.toPath());

                    continue;
                }

                if (outFile.getParentFile() != null) {
                    Files.createDirectories(outFile.getParentFile().toPath());
                }

                try (InputStream is = zip.getInputStream(entry);
                     OutputStream os = new FileOutputStream(outFile)) {
                    IOUtils.copy(is, os);
                }

                System.out.println("Extracted " + targetPath.relativize(outFile.toPath()).toString());
            }
        }
    }

    public static boolean doesCommandFail(@NotNull File workingDir, @NotNull String cmd, @Nullable String... args) {
        try {
            return runCommand(workingDir, cmd, args) != 0;
        } catch (IOException ignore) {
        }

        return true;
    }

    public static int runCommand(@NotNull File workingDir, @NotNull String cmd, @Nullable String... args) throws IOException {
        CommandLine cmdLine = new CommandLine(cmd);

        for (String arg : args) {
            if (arg != null && !arg.isEmpty()) {
                cmdLine.addArgument(arg);
            }
        }

        DefaultExecutor executor = new DefaultExecutor();
        executor.setWorkingDirectory(workingDir);
        executor.setStreamHandler(new PumpStreamHandler(System.out, System.err));

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
        pool.awaitTermination(60, TimeUnit.MINUTES);   // This *should* not be exceeded and Long.MAX_VALUE seems overkill

        // Making sure there won't be any buggy/unwanted threads left
        if (!pool.shutdownNow().isEmpty()) {
            throw new IllegalStateException("There are still tasks in the queue after 1 hour of execution... This doesn't look right");
        }

        if (exception.get() != null) {
            throw exception.get();
        }

        return statusCode.get();
    }

    public static void gitClone(@NotNull String url, @NotNull File target, boolean autoCRLF) throws GitAPIException, IOException {
        System.out.println("Cloning git repository '" + url + "' to '" + target.toString() + "'");

        try (Git result = Git.cloneRepository().setURI(url).setDirectory(target).call()) {
            StoredConfig config = result.getRepository().getConfig();
            config.setBoolean("core", null, "autocrlf", autoCRLF);
            config.save();

            System.out.println("Successfully cloned '" + url + "' (HEAD: " + getCurrGitHeadHash(result) + ")");
        }
    }

    public static boolean gitPull(@NotNull Git repo, @NotNull String ref) throws GitAPIException {
        System.out.println("Pulling updates for '" + repo.getRepository().getDirectory().toString() + "'");

        try {
            repo.reset().setRef("origin/master").setMode(ResetCommand.ResetType.HARD).call();
        } catch (JGitInternalException ex) {
            System.err.println("*** Warning, could not find origin/master ref, but continuing anyway.");
            System.err.println("*** If further errors occur, delete '" + repo.getRepository().getDirectory().getParent() + "' and retry.");
        }
        FetchResult result = repo.fetch().call();

        System.out.println("Successfully fetched updates for '" + repo.getRepository().getDirectory().toString() + "'");

        repo.reset().setRef(ref).setMode(ResetCommand.ResetType.HARD).call();
        if (ref.equals("master")) {
            repo.reset().setRef("origin/master").setMode(ResetCommand.ResetType.HARD).call();
        }
        System.out.println("Checked out '" + ref + "' for '" + repo.getRepository().getDirectory().toString() + "'");

        // Return true if fetch changed any tracking refs.
        return !result.getTrackingRefUpdates().isEmpty();
    }

    public static String getCurrGitHeadHash(Git repo) throws GitAPIException {
        return repo.log().setMaxCount(1).call().iterator().next().getName();
    }

    public static String toHex(byte[] bytes) {
        byte[] hexChars = new byte[bytes.length * 2];

        for (int j = 0; j < bytes.length; ++j) {
            int v = bytes[j] & 0xFF;

            hexChars[j * 2] = HEX_ARRAY[v >>> 4];
            hexChars[j * 2 + 1] = HEX_ARRAY[v & 0x0F];
        }

        return new String(hexChars, StandardCharsets.UTF_8);
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
    }

    public interface MultiThreadedTask {
        /**
         * @return A numeric status code (e.g. exit code)
         *
         * @throws Exception An exception that may be thrown by the task
         */
        int runTask() throws Exception;
    }
}
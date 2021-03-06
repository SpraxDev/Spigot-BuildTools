package org.spigotmc.builder;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.EnumConverter;
import org.apache.commons.io.output.TeeOutputStream;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public class Bootstrap {
    /**
     * This field holds the compatibility information
     * When the original project updates, this field has to be updated too!
     */
    public static final int ORIGINAL_BUILD_NUMBER = getBuildNumber("git-BuildTools-36c11e9-126");

    public static final boolean IS_WINDOWS = System.getProperty("os.name").startsWith("Windows");
    public static final boolean AUTO_CRLF = !"\n".equals(System.getProperty("line.separator"));

    public static final File CWD = new File(".").toPath().toAbsolutePath().normalize().toFile();
    private static final File LOG_FILE = new File(CWD, "BuildTools.log.txt");

    public static void main(String[] args) throws IOException {
        checkJVM();

        /* parse args */
        OptionParser optionParser = new OptionParser();
        OptionSpec<Void> helpFlag = optionParser.acceptsAll(Arrays.asList("help", "?"), "Show the help");
        OptionSpec<Void> disableCertFlag = optionParser.accepts("disable-certificate-check", "Disable HTTPS certificate check");
        OptionSpec<Void> disableJavaCheckFlag = optionParser.accepts("disable-java-check", "Disable Java version check");
        OptionSpec<Void> skipUpdateFlag = optionParser.acceptsAll(Arrays.asList("skip-update", "dont-update"), "Don't pull updates from Git");
        OptionSpec<Void> exitAfterFetchFlag = optionParser.accepts("exit-after-fetch", "Everything '--rev' unrelated is downloaded (No de-/compiling)");
        OptionSpec<Void> generateSrcFlag = optionParser.acceptsAll(Arrays.asList("generate-src", "generate-source"), "Generate source jar");
        OptionSpec<Void> generateDocFlag = optionParser.acceptsAll(Arrays.asList("generate-doc", "generate-docs"), "Generate Javadoc jar");
        OptionSpec<Void> devModeFlag = optionParser.accepts("dev", "Development mode");
        OptionSpec<File> outputDirFlag = optionParser.acceptsAll(Arrays.asList("o", "output-dir"), "Final jar output directory")
                .withRequiredArg()
                .ofType(File.class)
                .defaultsTo(CWD);
        OptionSpec<String> jenkinsVersionFlag = optionParser.accepts("rev", "Version to build")
                .withRequiredArg()
                .defaultsTo("latest");
        OptionSpec<Compile> toCompileFlag = optionParser.accepts("compile", "Comma separated list of software to compile")
                .withRequiredArg()
                .ofType(Compile.class)
                .withValuesConvertedBy(new EnumConverter<Compile>(Compile.class) { })
                .withValuesSeparatedBy(',')
                .defaultsTo(Compile.SPIGOT);
        OptionSpec<Void> onlyCompileOnChangeFlag = optionParser.acceptsAll(Arrays.asList("compile-if-changed", "only-compile-on-changed"),
                "Run BuildTools only when changes are detected in the repository");

        OptionSet options = optionParser.parse(args);

        // Print help and exit
        if (options.has(helpFlag)) {
            printToolVersion();
            optionParser.printHelpOn(System.out);

            System.exit(0);
            return;
        }

        final boolean skipUpdate = options.has(skipUpdateFlag);
        final boolean exitAfterFetch = options.has(exitAfterFetchFlag);
        final boolean generateSrc = options.has(generateSrcFlag);
        final boolean generateDoc = options.has(generateDocFlag);
        final boolean isDevMode = options.has(devModeFlag);
        final boolean disableJavaCheck = options.has(disableJavaCheckFlag);
        final boolean onlyCompileOnChange = options.has(onlyCompileOnChangeFlag);
        final boolean hasJenkinsVersion = options.has(jenkinsVersionFlag);

        final String jenkinsVersion = options.valueOf(jenkinsVersionFlag);
        final List<Compile> toCompile = options.valuesOf(toCompileFlag);
        final File outputDir = outputDirFlag.value(options);

        if (toCompile.isEmpty()) {
            toCompile.add(Compile.NONE);
        } else if (toCompile.size() > 1 && toCompile.contains(Compile.NONE)) {
            toCompile.removeIf(compile -> compile != Compile.NONE);
        }

        /* Start of actual BuildTools logic */

        startLogFile(); // Write application output to file

        // Disable https cert check
        if (options.has(disableCertFlag)) {
            try {
                Utils.disableHttpsCertificateChecks();
            } catch (NoSuchAlgorithmException | KeyManagementException ex) {
                System.out.println("Could not disable https certificate checks");
                ex.printStackTrace();
            }
        }

        printToolVersion();

        /* Start Builder */

        final long buildStart = System.nanoTime();  // Using nanos to be independent of the system clock

        try {
            new Builder(CWD, new Builder.BuilderConfiguration(skipUpdate, exitAfterFetch, generateSrc, generateDoc,
                    isDevMode, disableJavaCheck, onlyCompileOnChange, hasJenkinsVersion, jenkinsVersion, toCompile, outputDir))
                    .runBuild();
        } catch (Exception ex) {
            System.err.println();

            if (ex instanceof BuilderException) {
                System.err.println(ex.getMessage());

                if (ex.getCause() != null) {
                    ex.getCause().printStackTrace();
                }
            } else {
                ex.printStackTrace();
            }

            System.exit(1);
            return;
        }

        final long buildEnd = System.nanoTime();
        System.out.println("Finished in " + new DecimalFormat("#0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH))
                .format(TimeUnit.NANOSECONDS.toMillis(buildEnd - buildStart) / 1000.0) + " seconds");
    }

    private static void printToolVersion() {
        System.out.println("Running BuildTools '" + getBuildVersion() + "' (#" + getBuildNumber(getBuildVersion()) +
                " - Based on #" + ORIGINAL_BUILD_NUMBER + ")");
        System.out.println("Java Version: " + JavaVersion.getCurrentVersion() + " (" +
                System.getProperty("java.version", "Unknown Version") + ", " +
                System.getProperty("java.vendor", "Unknown Vendor") + ", " +
                System.getProperty("os.arch", "Unknown architecture") + ")");
        System.out.println("Working Directory: '" + CWD.getAbsolutePath() + "'");
        System.out.println();
    }

    @Nullable
    private static String getBuildVersion() {
        return Builder.class.getPackage().getImplementationVersion();
    }

    public static int getBuildNumber(@Nullable String buildVersion) {
        if (buildVersion != null) {
            String[] split = buildVersion.split("-");

            if (split.length == 4) {
                try {
                    return Integer.parseInt(split[3]);
                } catch (NumberFormatException ignore) {
                }
            }
        }

        return -1;
    }

    private static void checkJVM() {
        JavaVersion javaVersion = JavaVersion.getCurrentVersion();

        if (javaVersion.isUnknown()) {
            System.err.println("*** WARNING *** Unsupported Java detected (" + System.getProperty("java.class.version") +
                    "). BuildTools has only been tested up to Java 16. Use of development Java versions is not supported.");
            System.err.println("*** WARNING *** You may use 'java -version' to double check your Java version.");
        } else {
            // Older JVMs (including Java 8) report less than Xmx here. Allow some slack for people actually using -Xmx512M
            long memoryMb = Runtime.getRuntime().maxMemory() >> 20;

            if (memoryMb < 448) {
                System.err.println("BuildTools requires at least 512M of memory to run (1024M recommended), but has only detected " + memoryMb + "M.");
                System.err.println("This can often occur if you are running a 32-bit system, or one with low RAM.");
                System.err.println("Please re-run BuildTools with manually specified memory, e.g.: java -Xmx1G -jar BuildTools.jar");

                System.exit(1);
            }
        }
    }

    private static void startLogFile() throws FileNotFoundException, UnsupportedEncodingException {
        FileOutputStream fileOut = new FileOutputStream(Bootstrap.LOG_FILE);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
            System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err)));

            try {
                fileOut.close();
            } catch (IOException ignore) {
                // We're shutting the jvm down anyway
            }
        }));

        System.setOut(new PrintStream(new TeeOutputStream(System.out, fileOut), false, StandardCharsets.UTF_8.displayName()));
        System.setErr(new PrintStream(new TeeOutputStream(System.err, fileOut), false, StandardCharsets.UTF_8.displayName()));
    }
}
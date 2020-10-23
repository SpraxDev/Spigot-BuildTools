package org.spigotmc.builder;

import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import joptsimple.util.EnumConverter;
import org.apache.commons.io.output.TeeOutputStream;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class Bootstrap {
    public static final File CWD = new File(".").getAbsoluteFile().toPath().normalize().toFile();
    private static final File LOG_FILE = new File(CWD, "BuildTools.log.txt");

    private static OptionParser optionParser;
    public static boolean disableCertCheck, disableJavaCheck, doNotUpdate,
            skipCompile, generateSrc, generateDoc, dev, compileIfChanged;
    public static File outputDir;
    public static String rev;
    public static List<Compile> compile;

    public static void main(String[] args) throws Exception {
        /* Parse args */
        optionParser = new OptionParser();

        OptionSpec<Void> helpFlag = optionParser.accepts("help", "Show the help");
        OptionSpec<Void> disableCertCheckFlag = optionParser.accepts("disable-certificate-check", "Disable HTTPS certificate check");
        OptionSpec<Void> disableJavaCheckFlag = optionParser.accepts("disable-java-check", "Disable Java version check");
        OptionSpec<Void> doNotUpdateFlag = optionParser.accepts("dont-update", "Don't pull updates from Git");
        OptionSpec<Void> skipCompileFlag = optionParser.accepts("skip-compile", "Skip compilation");
        OptionSpec<Void> generateSrcFlag = optionParser.accepts("generate-source", "Generate source jar");
        OptionSpec<Void> generateDocFlag = optionParser.accepts("generate-docs", "Generate Javadoc jar");
        OptionSpec<Void> devFlag = optionParser.accepts("dev", "Development mode");
        OptionSpec<Void> compileIfChangedFlag = optionParser.accepts("compile-if-changed", "Run BuildTools only when changes are detected in the repository");

        OptionSpec<File> outputDirFlag = optionParser.acceptsAll(Arrays.asList("o", "output-dir"), "Final jar output directory")
                .withRequiredArg().ofType(File.class).defaultsTo(CWD);
        OptionSpec<String> revFlag = optionParser.accepts("rev", "Version to build")
                .withRequiredArg().defaultsTo("latest");
        OptionSpec<Compile> compileFlag = optionParser.accepts("compile", "Software to compile")
                .withRequiredArg()
                .ofType(Compile.class)
                .withValuesConvertedBy(new EnumConverter<Compile>(Compile.class) { })
                .withValuesSeparatedBy(',');

        OptionSet options = optionParser.parse(args);

        if (options.has(helpFlag)) {
            printHelp();
            System.exit(0);
        }

        // We initialize the logger after help could have been printed
        initLogger();

        disableCertCheck = options.has(disableCertCheckFlag);
        disableJavaCheck = options.has(disableJavaCheckFlag);
        doNotUpdate = options.has(doNotUpdateFlag);
        skipCompile = options.has(skipCompileFlag);
        generateSrc = options.has(generateSrcFlag);
        generateDoc = options.has(generateDocFlag);
        dev = options.has(devFlag);
        compileIfChanged = options.has(compileIfChangedFlag);

        outputDir = outputDirFlag.value(options);
        compile = compileFlag.values(options);
        if (compile.isEmpty()) {
            compile = Collections.singletonList(Compile.SPIGOT);
        } else if (compile.size() > 1 && compile.contains(Compile.NONE)) {
            System.err.println("You can't use '--compile NONE' while listing things to compile");
            System.exit(1);
        }
        if (options.has(skipCompileFlag)) {
            compile = Collections.singletonList(Compile.NONE);
            System.err.println("--skip-compile is deprecated, please use --compile NONE");
        }

        rev = options.valueOf(revFlag);

        if ((dev || doNotUpdate) && options.has(revFlag)) {
            System.err.println("Using --dev or --dont-update with --rev makes no sense, exiting.");
            System.exit(1);
        }

        /* Check java version (needed?) */
        JavaVersion javaVersion = JavaVersion.getCurrentVersion();

        if (javaVersion.getVersion() < JavaVersion.JAVA_8.getVersion()) {
            System.err.println("Outdated Java detected (" + javaVersion + "). BuildTools requires at least Java 8. Please update Java and try again.");
            System.err.println("You may use java -version to double check your Java version.");

            System.exit(1);
        }

        if (javaVersion.isUnknown()) {
            System.err.println("*** WARNING *** Unsupported Java detected (" + System.getProperty("java.class.version") +
                    "). BuildTools has only been tested up to Java 15. Use of development Java versions is not supported.");
            System.err.println("*** WARNING *** You may use java -version to double check your Java version.");
        }

        long memoryMb = Runtime.getRuntime().maxMemory() >> 20;
        if (memoryMb < 448) { // Older JVMs (including Java 8) report less than Xmx here. Allow some slack for people actually using -Xmx512M
            System.err.println("BuildTools requires at least 512M of memory to run (1024M recommended), but has only detected " + memoryMb + "M.");
            System.err.println("This can often occur if you are running a 32-bit system, or one with low RAM.");
            System.err.println("Please re-run BuildTools with manually specified memory, e.g: java -Xmx1024M -jar BuildTools.jar " + String.join(" ", args));

            System.exit(1);
        }

        // Start builder
        Builder.main();
    }

    public static void printHelp() throws IOException {
        optionParser.printHelpOn(System.out);
    }

    private static void initLogger() {
        try {
            OutputStream logOut = new BufferedOutputStream(new FileOutputStream(LOG_FILE));

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out)));
                System.setErr(new PrintStream(new FileOutputStream(FileDescriptor.err)));

                try {
                    logOut.close();
                } catch (IOException ignore) {
                    // We're shutting the jvm down anyway.
                }
            }));

            System.setOut(new PrintStream(new TeeOutputStream(System.out, logOut)));
            System.setErr(new PrintStream(new TeeOutputStream(System.err, logOut)));
        } catch (FileNotFoundException ex) {
            System.err.println("Could not create log file " + LOG_FILE.getAbsolutePath());
            ex.printStackTrace();
        }
    }
}
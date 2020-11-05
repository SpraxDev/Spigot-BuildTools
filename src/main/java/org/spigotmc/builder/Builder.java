package org.spigotmc.builder;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
import com.google.common.collect.ObjectArrays;
import com.google.common.hash.HashFunction;
import com.google.common.hash.Hasher;
import com.google.common.hash.Hashing;
import com.google.common.io.ByteStreams;
import com.google.common.io.CharStreams;
import com.google.common.io.Files;
import com.google.gson.Gson;
import difflib.DiffUtils;
import difflib.Patch;
import difflib.PatchFailedException;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.FetchResult;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.net.URL;
import java.net.URLConnection;
import java.nio.file.FileSystemException;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.spigotmc.builder.Bootstrap.CWD;

public class Builder {
    private static final boolean IS_WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private static final boolean AUTO_CRLF = !"\n".equals(System.getProperty("line.separator"));

    private static String applyPatchesShell = "sh";
    private static boolean didClone = false;

    private static File msysDir;

    // TODO: Don't use any static methods and enforce Builder to be instantiated instead
    public static void main(boolean dontUpdate, boolean generateSource, boolean generateDocs, boolean dev,
                            List<Compile> compile, boolean hasJenkinsVersion, String jenkinsVersion, boolean disableJavaCheck,
                            boolean compileIfChanged, File outputDir) throws IOException, InterruptedException, GitAPIException, PatchFailedException {
        // TODO: Remove this - commands should be escaped properly instead
        if (CWD.getAbsolutePath().contains("'") ||
                CWD.getAbsolutePath().contains("#") ||
                CWD.getAbsolutePath().contains("~") ||
                CWD.getAbsolutePath().contains("(") ||
                CWD.getAbsolutePath().contains(")")) {
            System.err.println("Please do not run in a path with special characters!");

            System.exit(1);
            return;
        }

        // TODO: Warn the user but don't enforce it (Give 1.5s time to read the warning)
        if (CWD.getAbsolutePath().contains("Dropbox") ||
                CWD.getAbsolutePath().contains("OneDrive")) {
            System.err.println("Please do not run BuildTools in a Dropbox, OneDrive, or similar. You can always copy the completed jars there later.");
            return;
        }

        if ((dev || dontUpdate) && hasJenkinsVersion) {
            System.err.println("Using --dev or --dont-update with --rev makes no sense, exiting.");

            System.exit(1);
            return;
        }

        try {
            runProcess(CWD, "sh", "-c", "exit");    // TODO: Support cmd or don't - not even every linux distro uses 'sh' by default
        } catch (Exception ex) {
            if (IS_WINDOWS) {
                String gitVersion = "PortableGit-2.24.1.2-" + (System.getProperty("os.arch").endsWith("64") ? "64" : "32") + "-bit";
                // https://github.com/git-for-windows/git/releases/tag/v2.24.1.windows.2
                String gitHash = System.getProperty("os.arch").endsWith("64") ?
                        "cb75e4a557e01dd27b5af5eb59dfe28adcbad21638777dd686429dd905d13899" :
                        "88f5525999228b0be8bb51788bfaa41b14430904bc65f1d4bbdcf441cac1f7fc";
                msysDir = new File(gitVersion, "PortableGit");

                if (!msysDir.isDirectory()) {
                    System.out.println("*** Could not find PortableGit installation, downloading. ***");

                    String gitName = gitVersion + ".7z.exe";
                    File gitInstall = new File(gitVersion, gitName);
                    gitInstall.deleteOnExit();
                    gitInstall.getParentFile().mkdirs();

                    if (!gitInstall.exists()) {
                        downloadFile("https://static.spigotmc.org/git/" + gitName, gitInstall, HashFormat.SHA256, gitHash);
                    }

                    System.out.println("Extracting downloaded git install");
                    // yes to all, silent, don't run. Only -y seems to work.
                    runProcess(gitInstall.getParentFile(), gitInstall.getAbsolutePath(), "-y", "-gm2", "-nr");

                    gitInstall.delete();
                }

                System.out.println("*** Using downloaded git " + msysDir + " ***");
                System.out.println("*** Please note that this is a beta feature, so if it does not work please also try a manual install of git from https://git-for-windows.github.io/ ***");
            } else {
                System.out.println("You must run this jar through bash (msysgit)");

                System.exit(1);
                return;
            }
        }

        try {
            // TODO: Rewrite method: cross-platform, escaping, ...
            runProcess(CWD, "git", "--version");
        } catch (Exception ex) {
            System.out.println("Could not successfully run git. Please ensure it is installed and functioning. " + ex.getMessage());

            System.exit(1);
            return;
        }

        // TODO: DON'T set these globally!!!!!! Why should this be a good idea? :c
        try {
            runProcess(CWD, "git", "config", "--global", "--includes", "user.name");
        } catch (Exception ex) {
            System.out.println("Git name not set, setting it to default value.");
            runProcess(CWD, "git", "config", "--global", "user.name", "BuildTools");
        }
        try {
            runProcess(CWD, "git", "config", "--global", "--includes", "user.email");
        } catch (Exception ex) {
            System.out.println("Git email not set, setting it to default value.");
            runProcess(CWD, "git", "config", "--global", "user.email", "unconfigured@null.spigotmc.org");
        }

        // TODO: Make the following lines easier to read by moving it into its own method

        File workDir = new File("work");
        workDir.mkdir();

        File bukkit = new File("Bukkit");
        if (!bukkit.exists() || !containsGit(bukkit)) {
            clone("https://hub.spigotmc.org/stash/scm/spigot/bukkit.git", bukkit);
        }

        File craftBukkit = new File("CraftBukkit");
        if (!craftBukkit.exists() || !containsGit(craftBukkit)) {
            clone("https://hub.spigotmc.org/stash/scm/spigot/craftbukkit.git", craftBukkit);
        }

        File spigot = new File("Spigot");
        if (!spigot.exists() || !containsGit(spigot)) {
            clone("https://hub.spigotmc.org/stash/scm/spigot/spigot.git", spigot);
        }

        File buildData = new File("BuildData");
        if (!buildData.exists() || !containsGit(buildData)) {
            clone("https://hub.spigotmc.org/stash/scm/spigot/builddata.git", buildData);
        }

        File maven;
        String m2Home = System.getenv("M2_HOME");   // TODO: Checking M2_ but downloading v3.6.0? Don't check even env, is not set by maven itself
        if (m2Home == null || !(maven = new File(m2Home)).exists()) {
            String mavenVersion = "apache-maven-3.6.0";
            maven = new File(mavenVersion);

            if (!maven.exists()) {
                System.out.println("Maven does not exist, downloading. Please wait.");

                File mvnTemp = new File(mavenVersion + "-bin.zip");
                mvnTemp.deleteOnExit();

                // https://www.apache.org/dist/maven/maven-3/3.6.0/binaries/apache-maven-3.6.0-bin.zip.sha512
                downloadFile("https://static.spigotmc.org/maven/" + mvnTemp.getName(), mvnTemp, HashFormat.SHA512, "7d14ab2b713880538974aa361b987231473fbbed20e83586d542c691ace1139026f232bd46fdcce5e8887f528ab1c3fbfc1b2adec90518b6941235952d3868e9");
                unzip(mvnTemp, new File("."));
                mvnTemp.delete();
            }
        }

        String mvn = maven.getAbsolutePath() + "/bin/mvn";

        Git bukkitGit = Git.open(bukkit);
        Git craftBukkitGit = Git.open(craftBukkit);
        Git spigotGit = Git.open(spigot);
        Git buildGit = Git.open(buildData);

        BuildInfo buildInfo = new BuildInfo("Dev Build", "Development", 0, null,
                new BuildInfo.Refs("master", "master", "master", "master"));

        if (!dontUpdate) {
            if (!dev) {
                System.out.println("Attempting to build version: '" + jenkinsVersion + "' use --rev <version> to override");

                String verInfo;
                try {
                    verInfo = get("https://hub.spigotmc.org/versions/" + jenkinsVersion + ".json");
                } catch (IOException ex) {
                    System.err.println("Could not get version " + jenkinsVersion + " does it exist? Try another version or use 'latest'");
                    ex.printStackTrace();

                    System.exit(1);
                    return;
                }
                System.out.println("Found version");
                System.out.println(verInfo);

                // TODO: Abstract json parsing to not use a dummy class
                buildInfo = new Gson().fromJson(verInfo, BuildInfo.class);

                // TODO: Check if this can be extracted into own method and maybe simplified
                if (Bootstrap.getBuildNumber() != -1 &&
                        buildInfo.getToolsVersion() != -1 &&
                        Bootstrap.getBuildNumber() < buildInfo.getToolsVersion()) {
                    System.err.println("**** Your BuildTools is out of date and will not build the requested version. " +
                            "Please grab a new copy from https://www.spigotmc.org/go/buildtools-dl");

                    System.exit(1);
                    return;
                }

                // TODO: Move to own method
                if (!disableJavaCheck) {
                    if (buildInfo.getJavaVersions() == null) {
                        buildInfo.setJavaVersions(new int[] {JavaVersion.JAVA_7.getVersion(), JavaVersion.JAVA_8.getVersion()});
                    }

                    Preconditions.checkArgument(buildInfo.getJavaVersions().length == 2,
                            "Expected only two Java versions, got %s", JavaVersion.printVersions(buildInfo.getJavaVersions()));

                    JavaVersion curVersion = JavaVersion.getCurrentVersion();
                    JavaVersion minVersion = JavaVersion.getByVersion(buildInfo.getJavaVersions()[0]);
                    JavaVersion maxVersion = JavaVersion.getByVersion(buildInfo.getJavaVersions()[1]);

                    if (curVersion.getVersion() < minVersion.getVersion() || curVersion.getVersion() > maxVersion.getVersion()) {
                        System.err.println("*** The version you have requested to build requires Java versions between " +
                                JavaVersion.printVersions(buildInfo.getJavaVersions()) + ", but you are using " + curVersion);
                        System.err.println("*** Please rerun BuildTools using an appropriate Java version. For obvious " +
                                "reasons outdated MC versions do not support Java versions that did not exist at their release.");

                        System.exit(1);
                    }
                }
            }

            // TODO: merge into one variable
            boolean buildDataChanged = pull(buildGit, buildInfo.getRefs().getBuildData());
            boolean bukkitChanged = pull(bukkitGit, buildInfo.getRefs().getBukkit());
            boolean craftBukkitChanged = pull(craftBukkitGit, buildInfo.getRefs().getCraftBukkit());
            boolean spigotChanged = pull(spigotGit, buildInfo.getRefs().getSpigot());

            // Checks if any of the 4 repositories have been updated via a fetch, the --compile-if-changed flag is set and none of the repositories were cloned in this run.
            if (!buildDataChanged && !bukkitChanged && !craftBukkitChanged && !spigotChanged && compileIfChanged && !didClone) {
                System.out.println("*** No changes detected in any of the repositories!");
                System.out.println("*** Exiting due to the --compile-if-changes");

                System.exit(0);
            }
        }

        // TODO: Use '--skip-decompile' to skip decompile etc. but fail compiling spigot/craftbukkit with exit 1 if flag is set

        // TODO: Replace any @Beta annotated method calls
        VersionInfo versionInfo = new Gson().fromJson(
                Files.asCharSource(new File("BuildData/info.json"), Charsets.UTF_8).read(),
                VersionInfo.class
        );
        // Default to 1.8 builds.
        if (versionInfo == null) {
            versionInfo = new VersionInfo("1.8", "bukkit-1.8.at",
                    "bukkit-1.8-cl.csrg", "bukkit-1.8-members.csrg",
                    "package.srg", null);
        }
        System.out.println("Attempting to build Minecraft with details: " + versionInfo);

        if (Bootstrap.getBuildNumber() != -1 &&
                versionInfo.getToolsVersion() != -1 &&
                Bootstrap.getBuildNumber() < versionInfo.getToolsVersion()) {
            // TODO: Update download link
            System.err.println("\n**** Your BuildTools is out of date and will not build the requested version. Please grab a new copy from https://www.spigotmc.org/go/buildtools-dl");

            System.exit(1);
            return;
        }

        File vanillaJar = new File(workDir, "minecraft_server." + versionInfo.getMinecraftVersion() + ".jar");
        if (!vanillaJar.exists() || !checkHash(vanillaJar, versionInfo, dev)) {
            if (versionInfo.getServerUrl() != null) {
                downloadFile(versionInfo.getServerUrl(), vanillaJar, HashFormat.MD5, dev ? null : versionInfo.getMinecraftHash());
            } else {
                downloadFile(String.format("https://s3.amazonaws.com/Minecraft.Download/versions/%1$s/minecraft_server.%1$s.jar",
                        versionInfo.getMinecraftVersion()), vanillaJar, HashFormat.MD5, dev ? null : versionInfo.getMinecraftHash());
            }
        }

        // TODO: What is this?
        if (versionInfo.getServerUrl() == null) {
            // Legacy versions can also specify a specific shell to build with which has to be bash-compatible
            applyPatchesShell = System.getenv().get("SHELL");
            if (applyPatchesShell == null || applyPatchesShell.trim().isEmpty()) {
                applyPatchesShell = "bash";
            }
        }

        Iterable<RevCommit> mappings = buildGit.log()
                .addPath("mappings/")
                .setMaxCount(1).call();

        Hasher mappingsHash = HashFormat.MD5.getHash().newHasher();
        for (RevCommit rev : mappings) {
            mappingsHash.putString(rev.getName(), Charsets.UTF_8);
        }
        String mappingsVersion = mappingsHash.hash().toString().substring(24); // Last 8 chars

        File finalMappedJar = new File(workDir, "mapped." + mappingsVersion + ".jar");
        if (!finalMappedJar.exists()) {
            System.out.println("Final mapped jar: " + finalMappedJar + " does not exist, creating (please wait)!");

            File clMappedJar = new File(finalMappedJar + "-cl");
            File mMappedJar = new File(finalMappedJar + "-m");

            if (versionInfo.getClassMapCommand() == null) {
                versionInfo.setClassMapCommand("java -jar BuildData/bin/SpecialSource-2.jar map -i {0} -m {1} -o {2}");
            }
            runProcess(CWD, MessageFormat.format(versionInfo.getClassMapCommand(), vanillaJar.getPath(),
                    "BuildData/mappings/" + versionInfo.getClassMappings() /* TODO: use File-class to construct path */, clMappedJar.getPath()).split(" "));

            if (versionInfo.getMemberMapCommand() == null) {
                versionInfo.setMemberMapCommand("java -jar BuildData/bin/SpecialSource-2.jar map -i {0} -m {1} -o {2}");
            }
            runProcess(CWD, MessageFormat.format(versionInfo.getMemberMapCommand(), clMappedJar.getPath(),
                    "BuildData/mappings/" + versionInfo.getMemberMappings() /* TODO: use File-class to construct path */, mMappedJar.getPath()).split(" "));

            if (versionInfo.getFinalMapCommand() == null) {
                versionInfo.setFinalMapCommand("java -jar BuildData/bin/SpecialSource.jar --kill-lvt -i {0} --access-transformer {1} -m {2} -o {3}");
            }
            runProcess(CWD, MessageFormat.format(versionInfo.getFinalMapCommand(), mMappedJar.getPath(), "BuildData/mappings/" + versionInfo.getAccessTransforms(),
                    "BuildData/mappings/" + versionInfo.getPackageMappings() /* TODO: use File-class to construct path */, finalMappedJar.getPath()).split(" "));
        }

        runProcess(CWD, "sh", mvn, "install:install-file", "-Dfile=" + finalMappedJar, "-Dpackaging=jar", "-DgroupId=org.spigotmc",
                "-DartifactId=minecraft-server", "-Dversion=" + versionInfo.getMinecraftVersion() + "-SNAPSHOT");

        File decompileDir = new File(workDir, "decompile-" + mappingsVersion);
        if (!decompileDir.exists()) {
            decompileDir.mkdir();

            File clazzDir = new File(decompileDir, "classes");
            unzip(finalMappedJar, clazzDir, input -> input.startsWith("net/minecraft/server"));

            if (versionInfo.getDecompileCommand() == null) {
                // TODO: Use File-class for path to fernflower.jar
                versionInfo.setDecompileCommand("java -jar BuildData/bin/fernflower.jar -dgs=1 -hdc=0 -rbr=0 -asc=1 -udv=0 {0} {1}");
            }

            // TODO: Don't use #format because it could destroy paths when executed
            runProcess(CWD, MessageFormat.format(versionInfo.getDecompileCommand(), clazzDir.getPath(), decompileDir.getPath()).split(" "));
        }

        try {
            File latestLink = new File(workDir, "decompile-latest");
            latestLink.delete();

            java.nio.file.Files.createSymbolicLink(latestLink.toPath(), decompileDir.getParentFile().toPath().relativize(decompileDir.toPath()));
        } catch (UnsupportedOperationException ex) {
            // Ignore if not possible
        } catch (FileSystemException ex) {
            // Not running as admin on Windows
        } catch (IOException ex) {
            System.out.println("Did not create decompile-latest link " + ex.getMessage());
        }

        System.out.println("Applying CraftBukkit Patches");
        File nmsDir = new File(craftBukkit, "src/main/java/net");
        if (nmsDir.exists()) {
            System.out.println("Backing up NMS dir");
            FileUtils.moveDirectory(nmsDir, new File(workDir, "nms.old." + System.currentTimeMillis()));
        }
        File patchDir = new File(craftBukkit, "nms-patches");
        for (File file : Objects.requireNonNull(patchDir.listFiles())) {
            if (!file.getName().endsWith(".patch")) {
                continue;
            }

            String targetFile = "net/minecraft/server/" + file.getName().replace(".patch", ".java");

            File clean = new File(decompileDir, targetFile);
            File t = new File(nmsDir.getParentFile(), targetFile);
            t.getParentFile().mkdirs();

            System.out.println("Patching with " + file.getName());

            List<String> readFile = Files.readLines(file, Charsets.UTF_8);

            // Manually append prelude if it is not found in the first few lines.
            boolean preludeFound = false;
            for (int i = 0; i < Math.min(3, readFile.size()); i++) {
                if (readFile.get(i).startsWith("+++")) {
                    preludeFound = true;
                    break;
                }
            }
            if (!preludeFound) {
                readFile.add(0, "+++");
            }

            Patch parsedPatch = DiffUtils.parseUnifiedDiff(readFile);
            List<?> modifiedLines = DiffUtils.patch(Files.readLines(clean, Charsets.UTF_8), parsedPatch);

            BufferedWriter bw = new BufferedWriter(new FileWriter(t));
            for (Object line : modifiedLines) {
                bw.write((String) line);
                bw.newLine();
            }
            bw.close();
        }
        File tmpNms = new File(craftBukkit, "tmp-nms");
        FileUtils.copyDirectory(nmsDir, tmpNms);

        craftBukkitGit.branchDelete().setBranchNames("patched").setForce(true).call();
        craftBukkitGit.checkout().setCreateBranch(true).setForceRefUpdate(true).setName("patched").call();
        craftBukkitGit.add().addFilepattern("src/main/java/net/").call();
        craftBukkitGit.commit().setSign(false).setMessage("CraftBukkit $ " + new Date()).call();
        craftBukkitGit.checkout().setName(buildInfo.getRefs().getCraftBukkit()).call();

        FileUtils.moveDirectory(tmpNms, nmsDir);

        if (versionInfo.getToolsVersion() < 93) {
            File spigotApi = new File(spigot, "Bukkit");
            if (!spigotApi.exists()) {
                clone("file://" + bukkit.getAbsolutePath(), spigotApi);
            }
            File spigotServer = new File(spigot, "CraftBukkit");
            if (!spigotServer.exists()) {
                clone("file://" + craftBukkit.getAbsolutePath(), spigotServer);
            }
        }

        if (compile == null || compile.isEmpty()) {
            if (versionInfo.getToolsVersion() <= 104 || dev) {
                compile = Arrays.asList(Compile.CRAFTBUKKIT, Compile.SPIGOT);
            } else {
                compile = Collections.singletonList(Compile.SPIGOT);
            }
        }

        if (compile.contains(Compile.CRAFTBUKKIT)) {
            System.out.println("Compiling Bukkit");
            if (dev) {
                runProcess(bukkit, "sh", mvn, "-P", "development", "clean", "install");
            } else {
                runProcess(bukkit, "sh", mvn, "clean", "install");
            }
            if (generateDocs) {
                runProcess(bukkit, "sh", mvn, "javadoc:jar");
            }
            if (generateSource) {
                runProcess(bukkit, "sh", mvn, "source:jar");
            }

            System.out.println("Compiling CraftBukkit");
            if (dev) {
                runProcess(craftBukkit, "sh", mvn, "-P", "development", "clean", "install");
            } else {
                runProcess(craftBukkit, "sh", mvn, "clean", "install");
            }
        }

        try {
            runProcess(spigot, applyPatchesShell, "applyPatches.sh");
            System.out.println("*** Spigot patches applied!");

            if (compile.contains(Compile.SPIGOT)) {
                System.out.println("Compiling Spigot & Spigot-API");
                if (dev) {
                    runProcess(spigot, "sh", mvn, "-P", "development", "clean", "install");
                } else {
                    runProcess(spigot, "sh", mvn, "clean", "install");
                }
            }
        } catch (Exception ex) {
            System.err.println("Error compiling Spigot. Please check the wiki for FAQs.");
            System.err.println("If this does not resolve your issue then please pastebin the entire BuildTools.log.txt file when seeking support.");
            ex.printStackTrace();

            System.exit(1);
            return;
        }

        for (int i = 0; i < 36; ++i) {
            System.out.println();
        }

        // TODO: Don't print this message if nothing compiled!
        System.out.println("Success! Everything completed successfully. Copying final .jar files now.");
        if (compile.contains(Compile.CRAFTBUKKIT) && (versionInfo.getToolsVersion() < 101 || versionInfo.getToolsVersion() > 104)) {
            copyJar("CraftBukkit/target", "craftbukkit", new File(outputDir, "craftbukkit-" + versionInfo.getMinecraftVersion() + ".jar"));
        }

        if (compile.contains(Compile.SPIGOT)) {
            copyJar("Spigot/Spigot-Server/target", "spigot", new File(outputDir, "spigot-" + versionInfo.getMinecraftVersion() + ".jar"));
        }
    }

    private static boolean checkHash(File vanillaJar, VersionInfo versionInfo, boolean dev) throws IOException {
        String hash = Files.asByteSource(vanillaJar).hash(HashFormat.MD5.getHash()).toString();

        if (dev || versionInfo.getMinecraftHash() == null || hash.equalsIgnoreCase(versionInfo.getMinecraftHash())) {
            System.out.println("Found good Minecraft hash (" + hash + ")");
            return true;
        } else {
            System.err.println("**** Warning, Minecraft jar hash of " + hash + " does not match stored hash of " + versionInfo.getMinecraftHash());
        }

        return false;
    }

    public static String get(String url) throws IOException {
        URLConnection con = new URL(url).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);

        try (InputStreamReader r = new InputStreamReader(con.getInputStream())) {
            return CharStreams.toString(r);
        }
    }

    public static void copyJar(String path, final String jarPrefix, File outJar) throws IOException {
        File[] files = new File(path).listFiles((dir, name) -> name.startsWith(jarPrefix) && name.endsWith(".jar"));

        if (!outJar.getParentFile().isDirectory()) {
            outJar.getParentFile().mkdirs();
        }

        for (File file : Objects.requireNonNull(files)) {
            System.out.println("Copying " + file.getName() + " to " + outJar.getAbsolutePath());
            Files.copy(file, outJar);

            System.out.println("  - Saved as " + outJar);
        }
    }

    public static boolean pull(Git repo, String ref) throws GitAPIException {
        System.out.println("Pulling updates for " + repo.getRepository().getDirectory());

        try {
            repo.reset().setRef("origin/master").setMode(ResetCommand.ResetType.HARD).call();
        } catch (JGitInternalException ex) {
            System.err.println("*** Warning, could not find origin/master ref, but continuing anyway.");
            System.err.println("*** If further errors occur please delete " + repo.getRepository().getDirectory().getParent() + " and retry.");
        }
        FetchResult result = repo.fetch().call();

        System.out.println("Successfully fetched updates!");

        repo.reset().setRef(ref).setMode(ResetCommand.ResetType.HARD).call();
        if (ref.equals("master")) {
            repo.reset().setRef("origin/master").setMode(ResetCommand.ResetType.HARD).call();
        }
        System.out.println("Checked out: " + ref);

        // Return true if fetch changed any tracking refs.
        return !result.getTrackingRefUpdates().isEmpty();
    }

    public static int runProcess(File workDir, String... command) throws IOException, InterruptedException {
        if (msysDir != null) {
            if ("bash".equals(command[0])) {
                command[0] = "git-bash";
            }

            String[] shim = new String[] {"cmd.exe", "/D", "/C"};
            command = ObjectArrays.concat(shim, command, String.class);
        }

        return runProcess0(workDir, command);
    }

    private static int runProcess0(File workDir, String... command) throws IOException, InterruptedException {
        Preconditions.checkArgument(workDir != null, "workDir");
        Preconditions.checkArgument(command != null && command.length > 0, "Invalid command");

        if (command[0].equals("java")) {
            command[0] = System.getProperty("java.home") + "/bin/" + command[0];
        }

        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workDir);

        pb.environment().put("JAVA_HOME", System.getProperty("java.home"));
        pb.environment().remove("M2_HOME"); // Just let maven figure this out from where it is invoked

        if (!pb.environment().containsKey("MAVEN_OPTS")) {
            pb.environment().put("MAVEN_OPTS", "-Xmx1024M");
        }

        if (!pb.environment().containsKey("_JAVA_OPTIONS")) {
            StringBuilder javaOptions = new StringBuilder("-Djdk.net.URLClassPath.disableClassPathURLCheck=true");

            for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
                if (arg.startsWith("-Xmx")) {
                    javaOptions.append(" ")
                            .append(arg);
                }
            }

            pb.environment().put("_JAVA_OPTIONS", javaOptions.toString());
        }

        if (msysDir != null) {
            String pathEnv = null;

            for (String key : pb.environment().keySet()) {
                if (key.equalsIgnoreCase("path")) {
                    pathEnv = key;
                }
            }

            if (pathEnv == null) {
                throw new IllegalStateException("Could not find path variable!");
            }

            String path = msysDir.getAbsolutePath() + ";" + new File(msysDir, "bin").getAbsolutePath() + ";" + pb.environment().get(pathEnv);
            pb.environment().put(pathEnv, path);
        }

        final Process ps = pb.start();

        new Thread(new StreamRedirector(ps.getInputStream(), System.out), "System.out redirector").start();
        new Thread(new StreamRedirector(ps.getErrorStream(), System.err), "System.err redirector").start();

        int status = ps.waitFor();

        if (status != 0) {
            throw new RuntimeException("Error running command, return status !=0: " + Arrays.toString(command));
        }

        return status;
    }

    private static class StreamRedirector implements Runnable {
        private final InputStream in;
        private final PrintStream out;

        public StreamRedirector(InputStream in, PrintStream out) {
            this.in = in;
            this.out = out;
        }

        @Override
        public void run() {
            BufferedReader br = new BufferedReader(new InputStreamReader(in));

            try {
                String line;
                while ((line = br.readLine()) != null) {
                    out.println(line);
                }
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public static void unzip(File zipFile, File targetFolder) throws IOException {
        unzip(zipFile, targetFolder, null);
    }

    public static void unzip(File zipFile, File targetFolder, Predicate<String> filter) throws IOException {
        targetFolder.mkdir();

        try (ZipFile zip = new ZipFile(zipFile)) {
            for (Enumeration<? extends ZipEntry> entries = zip.entries(); entries.hasMoreElements(); ) {
                ZipEntry entry = entries.nextElement();

                if (filter != null) {
                    if (!filter.test(entry.getName())) {
                        continue;
                    }
                }

                File outFile = new File(targetFolder, entry.getName());

                if (entry.isDirectory()) {
                    outFile.mkdirs();
                    continue;
                }
                if (outFile.getParentFile() != null) {
                    outFile.getParentFile().mkdirs();
                }

                try (InputStream is = zip.getInputStream(entry);
                     OutputStream os = new FileOutputStream(outFile)) {
                    ByteStreams.copy(is, os);
                }

                System.out.println("Extracted: " + outFile);
            }
        }
    }

    public static void clone(String url, File target) throws GitAPIException, IOException {
        System.out.println("Starting clone of " + url + " to " + target);

        try (Git result = Git.cloneRepository().setURI(url).setDirectory(target).call()) {
            StoredConfig config = result.getRepository().getConfig();
            config.setBoolean("core", null, "autocrlf", AUTO_CRLF);
            config.save();

            didClone = true;
            System.out.println("Cloned git repository " + url + " to " + target.getAbsolutePath() + ". Current HEAD: " + commitHash(result));
        }
    }

    public static String commitHash(Git repo) throws GitAPIException {
        return Iterables.getOnlyElement(repo.log().setMaxCount(1).call()).getName();
    }

    public static void downloadFile(String url, File dest, HashFormat hashFormat, String goodHash) throws IOException {
        System.out.println("Starting download of " + url);

        FileUtils.copyURLToFile(new URL(url), dest);
        byte[] bytes = IOUtils.toByteArray(new URL(url));
        String hash = hashFormat.getHash().hashBytes(bytes).toString(); // TODO: Rewrite hashing methods

        System.out.println("Downloaded file: " + dest + " with hash: " + hash);

        if (goodHash != null && !goodHash.equals(hash)) {
            throw new IllegalStateException("Downloaded file: " + dest + " did not match expected hash: " + goodHash);
        }

        java.nio.file.Files.createDirectories(dest.getParentFile().toPath());
        try (FileOutputStream out = new FileOutputStream(dest)) {
            IOUtils.write(bytes, out);
        }
    }

    public enum HashFormat {
        MD5 {
            @Override
            @SuppressWarnings("deprecation")
            public HashFunction getHash() {
                return Hashing.md5();
            }
        }, SHA256 {
            @Override
            public HashFunction getHash() {
                return Hashing.sha256();
            }
        }, SHA512 {
            @Override
            public HashFunction getHash() {
                return Hashing.sha512();
            }
        };

        public abstract HashFunction getHash();
    }

    private static boolean containsGit(File file) {
        return new File(file, ".git").isDirectory();
    }
}
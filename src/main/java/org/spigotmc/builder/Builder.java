package org.spigotmc.builder;

import com.google.common.base.Charsets;
import com.google.common.base.Preconditions;
import com.google.common.collect.Iterables;
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

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
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

    private static boolean didClone = false;

    // These variables may be filled with the path to a portable installation
    private static String gitCmd = "git";
    private static String mvnCmd = "mvn";
    private static String shCmd = "sh";
    private static final String javaCmd = new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath();

    // TODO: Don't use any static methods and enforce Builder to be instantiated instead
    public static void main(boolean dontUpdate, boolean generateSource, boolean generateDocs, boolean dev,
                            List<Compile> compile, boolean hasJenkinsVersion, String jenkinsVersion, boolean disableJavaCheck,
                            boolean compileIfChanged, File outputDir) throws IOException, GitAPIException, PatchFailedException {
        if ((dev || dontUpdate) && hasJenkinsVersion) {
            System.err.println("Using --dev or --dont-update with --rev makes no sense, exiting.");

            System.exit(1);
            return;
        }

        if (!prepareGitInstallation()) {
            System.err.println("Could not run 'git' - Please install it on your machine");
            System.err.println("More information at " +
                    (IS_WINDOWS ? "https://git-for-windows.github.io/" : "https://git-scm.com/downloads"));

            System.exit(1);
            return;
        }

        System.out.println();

        if (!prepareMavenInstallation()) {
            System.err.println("Could not run 'mvn' - Please install Maven3 on your machine");

            System.exit(1);
            return;
        }

        if (Utils.doesCommandFail(CWD, shCmd, "-c", "exit")) {
            System.err.println("Could not run '" + shCmd + "' - Please make sure it is available on your machine");

            System.exit(-1);
            return;
        }

        System.out.println();

        /* TODO: Make the following lines easier to read by moving it into its own method */
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
            String[] args = MessageFormat.format(versionInfo.getClassMapCommand(), vanillaJar.getPath(),
                    "BuildData/mappings/" + versionInfo.getClassMappings() /* TODO: use File-class to construct path */, clMappedJar.getPath()).split(" ");
            String cmd = args[0];
            args[0] = null;
            Utils.runCommand(CWD, cmd.equalsIgnoreCase("java") ? javaCmd : cmd, args);

            if (versionInfo.getMemberMapCommand() == null) {
                versionInfo.setMemberMapCommand("java -jar BuildData/bin/SpecialSource-2.jar map -i {0} -m {1} -o {2}");
            }
            args = MessageFormat.format(versionInfo.getMemberMapCommand(), clMappedJar.getPath(),
                    "BuildData/mappings/" + versionInfo.getMemberMappings() /* TODO: use File-class to construct path */, mMappedJar.getPath()).split(" ");
            cmd = args[0];
            args[0] = null;
            Utils.runCommand(CWD, cmd.equalsIgnoreCase("java") ? javaCmd : cmd, args);

            if (versionInfo.getFinalMapCommand() == null) {
                versionInfo.setFinalMapCommand("java -jar BuildData/bin/SpecialSource.jar --kill-lvt -i {0} --access-transformer {1} -m {2} -o {3}");
            }
            args = MessageFormat.format(versionInfo.getFinalMapCommand(), mMappedJar.getPath(), "BuildData/mappings/" + versionInfo.getAccessTransforms(),
                    "BuildData/mappings/" + versionInfo.getPackageMappings() /* TODO: use File-class to construct path */, finalMappedJar.getPath()).split(" ");
            cmd = args[0];
            args[0] = null;
            Utils.runCommand(CWD, cmd.equalsIgnoreCase("java") ? javaCmd : cmd, args);
        }

        Utils.runCommand(CWD, mvnCmd, "install:install-file", "-Dfile=" + finalMappedJar, "-Dpackaging=jar", "-DgroupId=org.spigotmc",
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
            String[] args = MessageFormat.format(versionInfo.getDecompileCommand(), clazzDir.getPath(), decompileDir.getPath()).split(" ");
            String cmd = args[0];
            args[0] = null;
            Utils.runCommand(CWD, cmd.equalsIgnoreCase("java") ? javaCmd : cmd, args);
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
                Utils.runCommand(bukkit, mvnCmd, "-P", "development", "clean", "install");
            } else {
                Utils.runCommand(bukkit, mvnCmd, "clean", "install");
            }
            if (generateDocs) {
                Utils.runCommand(bukkit, mvnCmd, "javadoc:jar");
            }
            if (generateSource) {
                Utils.runCommand(bukkit, mvnCmd, "source:jar");
            }

            System.out.println("Compiling CraftBukkit");
            if (dev) {
                Utils.runCommand(craftBukkit, mvnCmd, "-P", "development", "clean", "install");
            } else {
                Utils.runCommand(craftBukkit, mvnCmd, "clean", "install");
            }
        }

        try {
            Utils.runCommand(spigot, shCmd, "applyPatches.sh");
            System.out.println("*** Spigot patches applied!");

            if (compile.contains(Compile.SPIGOT)) {
                System.out.println("Compiling Spigot & Spigot-API");
                if (dev) {
                    Utils.runCommand(spigot, mvnCmd, "-P", "development", "clean", "install");
                } else {
                    Utils.runCommand(spigot, mvnCmd, "clean", "install");
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

    private static boolean prepareGitInstallation() throws IOException {
        if (Utils.doesCommandFail(CWD, gitCmd, "--version")) {
            if (IS_WINDOWS) {
                boolean arch64 = System.getProperty("os.arch").endsWith("64");

                // https://github.com/git-for-windows/git/releases/tag/v2.24.1.windows.2
                String gitVersion = "PortableGit-2.24.1.2-" + (arch64 ? "64" : "32") + "-bit";
                String gitHash = arch64 ?
                        "cb75e4a557e01dd27b5af5eb59dfe28adcbad21638777dd686429dd905d13899" :
                        "88f5525999228b0be8bb51788bfaa41b14430904bc65f1d4bbdcf441cac1f7fc";

                File gitDir = new File(new File(CWD, gitVersion), "PortableGit");

                if (!gitDir.isDirectory()) {
                    System.out.println("\n*** Downloading PortableGit ***");

                    String installerName = gitVersion + ".7z.exe";

                    File gitInstaller = new File(gitDir.getParentFile(), installerName);
                    gitInstaller.deleteOnExit();

                    downloadFile("https://static.spigotmc.org/git/" + installerName, gitInstaller, HashFormat.SHA256, gitHash);

                    System.out.println("Extracting downloaded git installer");
                    // yes to all, silent, don't run. Only -y seems to work.
                    Utils.runCommand(gitInstaller.getParentFile(), gitInstaller.getAbsolutePath(), "-y", "-gm2", "-nr");

                    java.nio.file.Files.deleteIfExists(gitInstaller.toPath());
                }

                gitCmd = new File(new File(gitDir, "bin"), "git").getAbsolutePath();
                shCmd = new File(new File(gitCmd).getParentFile(), "sh").getAbsolutePath();
                System.out.println("\n*** Using PortableGit at " + gitDir.getAbsolutePath() + " ***\n");
            }

            if (Utils.doesCommandFail(CWD, gitCmd, "--version")) {
                return false;
            }
        }

        // TODO: DON'T set these globally!!!!!! Why should this be a good idea? :c
        try {
            Utils.runCommand(CWD, gitCmd, "config", "--global", "--includes", "user.name");
        } catch (Exception ex) {
            System.out.println("Git name not set, setting it to default value.");
            Utils.runCommand(CWD, gitCmd, "config", "--global", "user.name", "BuildTools");
        }

        try {
            Utils.runCommand(CWD, gitCmd, "config", "--global", "--includes", "user.email");
        } catch (Exception ex) {
            System.out.println("Git email not set, setting it to default value.");
            Utils.runCommand(CWD, gitCmd, "config", "--global", "user.email", "unconfigured@null.spigotmc.org");
        }

        return true;
    }

    private static boolean prepareMavenInstallation() throws IOException {
        if (Utils.doesCommandFail(CWD, mvnCmd, "-B", "--version")) {
            // https://www.apache.org/dist/maven/maven-3/3.6.0/binaries/apache-maven-3.6.0-bin.zip.sha512
            String mvnVersion = "apache-maven-3.6.0";
            String mvnHash = "7d14ab2b713880538974aa361b987231473fbbed20e83586d542c691ace1139026f232bd46fdcce5e8887f528ab1c3fbfc1b2adec90518b6941235952d3868e9";

            File mvnDir = new File(CWD, mvnVersion);

            if (!mvnDir.isDirectory()) {
                System.out.println("\n*** Downloading Maven3 ***");

                File mvnZip = new File(mvnDir.getParentFile(), mvnVersion + "-bin.zip");
                mvnZip.deleteOnExit();

                downloadFile("https://static.spigotmc.org/maven/" + mvnZip.getName(), mvnZip, HashFormat.SHA512, mvnHash);

                System.out.println("Extracting downloaded maven archive");
                unzip(mvnZip, mvnDir.getParentFile());

                java.nio.file.Files.deleteIfExists(mvnZip.toPath());
            }

            mvnCmd = new File(new File(mvnDir, "bin"), "mvn" + (IS_WINDOWS ? ".cmd" : "")).getAbsolutePath();
            System.out.println("*** Using Maven3 at " + mvnDir.getAbsolutePath() + " ***\n");

            return !Utils.doesCommandFail(CWD, mvnCmd, "-B", "--version");
        }

        return true;
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
package org.spigotmc.builder;

import com.google.gson.Gson;
import difflib.DiffUtils;
import difflib.Patch;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.revwalk.RevCommit;
import org.jetbrains.annotations.NotNull;
import org.spigotmc.builder.dummy.BuildInfo;
import org.spigotmc.builder.dummy.VersionInfo;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.Date;
import java.util.List;
import java.util.Objects;

public class Builder {
    private final File cwd;
    private final BuilderConfiguration cfg;

    private String gitCmd = "git";
    private String mvnCmd = "mvn";
    private String shCmd = "sh";
    private final String javaCmd = Paths.get(System.getProperty("java.home"), "bin", "java").toAbsolutePath().normalize().toString();

    public Builder(File cwd, BuilderConfiguration cfg) {
        this.cwd = cwd;
        this.cfg = cfg;
    }

    public void runBuild() throws Exception {
        if ((cfg.isDevMode || cfg.skipUpdate) && cfg.hasJenkinsVersion) {
            throw new BuilderException("Using --dev or --dont-update with --rev makes no sense, exiting.");
        }

        if (!prepareGitInstallation(cwd)) {
            throw new BuilderException("Could not run 'git' - Please install it on your machine\n" +
                    "More information at " + (Bootstrap.IS_WINDOWS ? "https://git-for-windows.github.io/" : "https://git-scm.com/downloads"));
        }

        System.out.println();

        if (!prepareMavenInstallation(cwd)) {
            throw new BuilderException("Could not run 'mvn' - Please install Maven3 on your machine");
        }

        if (Utils.doesCommandFail(cwd, shCmd, "-c", "exit")) {
            throw new BuilderException("Could not run '" + shCmd + "' - Please make sure it is available on your machine");
        }

        System.out.println();

        /* Prepare working directory by cloning all needed git repositories */

        File workDir = new File(cwd, "work");
        Files.createDirectories(workDir.toPath());

        Utils.MultiThreadedTask[] tasks = new Utils.MultiThreadedTask[GitRepository.values().length];
        for (int i = 0; i < GitRepository.values().length; ++i) {
            GitRepository repo = GitRepository.values()[i];

            tasks[i] = () -> {
                File repoDir = new File(cwd, repo.repoName);

                if (!new File(repoDir, ".git").isDirectory()) {
                    Utils.gitClone(repo.gitUrl, repoDir, Bootstrap.AUTO_CRLF);
                    return 1;   // Successful clone
                }

                return 0;   // No changes made
            };
        }
        boolean gitReposDidChange = Utils.runTasksMultiThreaded(tasks) == 1;    // 1 means at least one repo has been cloned

        try (Git bukkitGit = Git.open(new File(cwd, GitRepository.BUKKIT.repoName));
             Git craftBukkitGit = Git.open(new File(cwd, GitRepository.CRAFT_BUKKIT.repoName));
             Git spigotGit = Git.open(new File(cwd, GitRepository.SPIGOT.repoName));
             Git buildDataGit = Git.open(new File(cwd, GitRepository.BUILD_DATA.repoName))) {
            BuildInfo buildInfo = new BuildInfo("Dev Build", "Development", 0, null,
                    new BuildInfo.Refs("master", "master", "master", "master"));

            if (!cfg.skipUpdate) {
                if (!cfg.isDevMode) {
                    System.out.println("Attempting to build version: '" + cfg.jenkinsVersion + "'" +
                            (!cfg.hasJenkinsVersion ? " use --rev <version> to override" : ""));

                    String verInfo;
                    try {
                        verInfo = Utils.httpGet("https://hub.spigotmc.org/versions/" + cfg.jenkinsVersion + ".json");
                    } catch (IOException ex) {
                        throw new BuilderException("Could not get version '" + cfg.jenkinsVersion +
                                "' does it exist? Try another version or use 'latest'", ex);
                    }
                    System.out.println("Found version");
                    System.out.println(verInfo);

                    // TODO: Abstract json parsing to not use a dummy class
                    buildInfo = new Gson().fromJson(verInfo, BuildInfo.class);

                    if (Bootstrap.getBuildNumber() != -1 &&
                            buildInfo.getToolsVersion() != -1 &&
                            Bootstrap.getBuildNumber() < buildInfo.getToolsVersion()) {
                        // TODO: Update download link
                        throw new BuilderException("**** Your BuildTools is out of date and will not build the requested version. " +
                                "Please grab a new copy from https://www.spigotmc.org/go/buildtools-dl");
                    }

                    if (!cfg.disableJavaCheck) {
                        if (buildInfo.getJavaVersions() == null) {
                            buildInfo.setJavaVersions(new int[] {JavaVersion.JAVA_7.getVersion(), JavaVersion.JAVA_8.getVersion()});
                        }

                        if (buildInfo.getJavaVersions().length != 2) {
                            throw new IllegalArgumentException("Expected only two Java versions, got " + JavaVersion.printVersions(buildInfo.getJavaVersions()));
                        }

                        JavaVersion curVersion = JavaVersion.getCurrentVersion();
                        JavaVersion minVersion = JavaVersion.getByVersion(buildInfo.getJavaVersions()[0]);
                        JavaVersion maxVersion = JavaVersion.getByVersion(buildInfo.getJavaVersions()[1]);

                        if (curVersion.getVersion() < minVersion.getVersion() || curVersion.getVersion() > maxVersion.getVersion()) {
                            throw new BuilderException("*** The version you have requested to build requires Java versions between " +
                                    JavaVersion.printVersions(buildInfo.getJavaVersions()) + ", but you are using '" + curVersion + "'\n" +

                                    "*** Please rerun BuildTools using an appropriate Java version. For obvious " +
                                    "reasons outdated MC versions do not support Java versions that did not exist at their release.");
                        }
                    }
                }

                BuildInfo finalBuildInfo = buildInfo;
                gitReposDidChange = Utils.runTasksMultiThreaded(
                        () -> Utils.gitPull(buildDataGit, finalBuildInfo.getRefs().getBuildData()) ? 1 : 0,
                        () -> Utils.gitPull(bukkitGit, finalBuildInfo.getRefs().getBukkit()) ? 1 : 0,
                        () -> Utils.gitPull(craftBukkitGit, finalBuildInfo.getRefs().getCraftBukkit()) ? 1 : 0,
                        () -> Utils.gitPull(spigotGit, finalBuildInfo.getRefs().getSpigot()) ? 1 : 0
                ) == 1 || gitReposDidChange;

                // Checks if any of the 4 repositories have been updated via a git fetch, the --compile-if-changed flag is set and none of the repositories were cloned in this run.
                if (!gitReposDidChange && cfg.onlyCompileOnChange) {
                    System.out.println("*** No changes detected in any of the repositories!");
                    System.out.println("*** Exiting due to the --compile-if-changes");

                    return;
                }
            }

            if (cfg.exitAfterFetch) {
                System.out.println("Finished fetching all version unrelated data. Exiting because of '--exit-after-fetch'");
                return;
            }

            VersionInfo versionInfo = new Gson().fromJson(
                    FileUtils.readFileToString(new File("BuildData/info.json"), StandardCharsets.UTF_8),
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
                throw new BuilderException("**** Your BuildTools is out of date and will not build the requested version. Please grab a new copy from https://www.spigotmc.org/go/buildtools-dl");
            }

            File vanillaJar = new File(workDir, "minecraft_server." + versionInfo.getMinecraftVersion() + ".jar");
            if (!vanillaJar.exists() || !checkHash(vanillaJar, versionInfo, cfg.isDevMode)) {
                if (versionInfo.getServerUrl() != null) {
                    Utils.downloadFile(versionInfo.getServerUrl(), vanillaJar, HashAlgo.MD5, versionInfo.getMinecraftHash());
                } else {
                    Utils.downloadFile(String.format("https://s3.amazonaws.com/Minecraft.Download/versions/%1$s/minecraft_server.%1$s.jar",
                            versionInfo.getMinecraftVersion()), vanillaJar, HashAlgo.MD5, versionInfo.getMinecraftHash());
                }
            }

            Iterable<RevCommit> mappings = buildDataGit.log()
                    .addPath("mappings/")
                    .setMaxCount(1).call();

            HashAlgo mappingsHash = HashAlgo.MD5;
            for (RevCommit rev : mappings) {
                mappingsHash.update(rev.getName().getBytes(StandardCharsets.UTF_8));
            }
            String mappingsVersion = mappingsHash.getHash().substring(24); // Last 8 chars

            File finalMappedJar = new File(workDir, "mapped." + mappingsVersion + ".jar");
            if (!finalMappedJar.exists()) {
                System.out.println("Final mapped jar '" + finalMappedJar + "' does not exist, creating (please wait)!");

                File clMappedJar = new File(finalMappedJar + "-cl");
                File mMappedJar = new File(finalMappedJar + "-m");

                // This cannot be run in parallel because they rely on each other, but
                // I'm keeping this for better readability - notice the threadCount = 1
                VersionInfo finalVersionInfo = versionInfo;
                Utils.runTasksMultiThreaded(1, () -> {
                            String[] args = finalVersionInfo.getClassMapCommand().split(" ");
                            for (int i = 0; i < args.length; ++i) {
                                switch (args[i]) {
                                    case "{0}":
                                        args[i] = cwd.toPath().relativize(vanillaJar.toPath()).toString();
                                        break;
                                    case "{1}":
                                        args[i] = "BuildData/mappings/" + finalVersionInfo.getClassMappings();
                                        break;
                                    case "{2}":
                                        args[i] = cwd.toPath().relativize(clMappedJar.toPath()).toString();
                                        break;
                                    default:
                                        break;
                                }
                            }

                            String cmd = args[0];
                            args[0] = null;

                            Utils.runCommand(cwd, cmd.equalsIgnoreCase("java") ? javaCmd : cmd, args);

                            return 0;
                        },
                        () -> {
                            String[] args = finalVersionInfo.getMemberMapCommand().split(" ");
                            for (int i = 0; i < args.length; ++i) {
                                switch (args[i]) {
                                    case "{0}":
                                        args[i] = cwd.toPath().relativize(clMappedJar.toPath()).toString();
                                        break;
                                    case "{1}":
                                        args[i] = "BuildData/mappings/" + finalVersionInfo.getMemberMappings();
                                        break;
                                    case "{2}":
                                        args[i] = cwd.toPath().relativize(mMappedJar.toPath()).toString();
                                        break;
                                    default:
                                        break;
                                }
                            }

                            String cmd = args[0];
                            args[0] = null;

                            Utils.runCommand(cwd, cmd.equalsIgnoreCase("java") ? javaCmd : cmd, args);

                            return 0;
                        },
                        () -> {
                            String[] args = finalVersionInfo.getFinalMapCommand().split(" ");
                            for (int i = 0; i < args.length; ++i) {
                                switch (args[i]) {
                                    case "{0}":
                                        args[i] = cwd.toPath().relativize(mMappedJar.toPath()).toString();
                                        break;
                                    case "{1}":
                                        args[i] = "BuildData/mappings/" + finalVersionInfo.getAccessTransforms();
                                        break;
                                    case "{2}":
                                        args[i] = "BuildData/mappings/" + finalVersionInfo.getPackageMappings();
                                        break;
                                    case "{3}":
                                        args[i] = cwd.toPath().relativize(finalMappedJar.toPath()).toString();
                                        break;
                                    default:
                                        break;
                                }
                            }

                            String cmd = args[0];
                            args[0] = null;

                            Utils.runCommand(cwd, cmd.equalsIgnoreCase("java") ? javaCmd : cmd, args);

                            return 0;
                        });
            }

            Utils.runCommand(cwd, mvnCmd, "install:install-file", "-Dfile=" + finalMappedJar, "-Dpackaging=jar", "-DgroupId=org.spigotmc",
                    "-DartifactId=minecraft-server", "-Dversion=" + versionInfo.getMinecraftVersion() + "-SNAPSHOT");

            File decompileDir = new File(workDir, "decompile-" + mappingsVersion);
            if (!decompileDir.exists()) {
                Files.createDirectories(decompileDir.toPath());

                File clazzDir = new File(decompileDir, "classes");
                Utils.extractZip(finalMappedJar, clazzDir, s -> s.startsWith("net/minecraft/server"));

                if (versionInfo.getDecompileCommand() == null) {
                    versionInfo.setDecompileCommand("java -jar BuildData/bin/fernflower.jar -dgs=1 -hdc=0 -rbr=0 -asc=1 -udv=0 {0} {1}");
                }

                String[] args = versionInfo.getDecompileCommand().split(" ");
                for (int i = 0; i < args.length; ++i) {
                    switch (args[i]) {
                        case "{0}":
                            args[i] = cwd.toPath().relativize(clazzDir.toPath()).toString();
                            break;
                        case "{1}":
                            args[i] = cwd.toPath().relativize(decompileDir.toPath()).toString();
                            break;
                        default:
                            break;
                    }
                }

                String cmd = args[0];
                args[0] = null;
                Utils.runCommand(cwd, cmd.equalsIgnoreCase("java") ? javaCmd : cmd, args);
            }

            try {
                File latestLink = new File(workDir, "decompile-latest");
                Files.deleteIfExists(latestLink.toPath());

                Files.createSymbolicLink(latestLink.toPath(), decompileDir.getParentFile().toPath().relativize(decompileDir.toPath()));
            } catch (UnsupportedOperationException ex) {
                // Ignore if not possible
            } catch (FileSystemException ex) {
                // Not running as admin on Windows
            } catch (IOException ex) {
                System.out.println("Did not create decompile-latest link " + ex.getMessage());
            }

            System.out.println("Applying CraftBukkit Patches");

            File nmsDir = Paths.get(craftBukkitGit.getRepository().getDirectory().getParentFile().getPath(), "src", "main", "java", "net").toFile();
            if (nmsDir.exists()) {
                System.out.println("Backing up NMS dir");
                FileUtils.moveDirectory(nmsDir, new File(workDir, "nms.old." + System.currentTimeMillis()));
            }
            File patchDir = new File(craftBukkitGit.getRepository().getDirectory().getParentFile(), "nms-patches");
            for (File file : Objects.requireNonNull(patchDir.listFiles())) {
                if (!file.getName().endsWith(".patch")) {
                    continue;
                }

                String targetFile = "net/minecraft/server/" + file.getName().replace(".patch", ".java");

                File clean = new File(decompileDir, targetFile);
                File t = new File(nmsDir.getParentFile(), targetFile);
                Files.createDirectories(t.getParentFile().toPath());

                System.out.println("Patching with " + file.getName());

                List<String> readFile = FileUtils.readLines(file, StandardCharsets.UTF_8);

                // Manually append a prelude if it is not found in the first few lines.
                boolean preludeFound = false;
                for (int i = 0; i < Math.min(3, readFile.size()); ++i) {
                    if (readFile.get(i).startsWith("+++")) {
                        preludeFound = true;
                        break;
                    }
                }
                if (!preludeFound) {
                    readFile.add(0, "+++");
                }

                Patch parsedPatch = DiffUtils.parseUnifiedDiff(readFile);
                List<?> modifiedLines = DiffUtils.patch(FileUtils.readLines(clean, StandardCharsets.UTF_8), parsedPatch);

                BufferedWriter bw = new BufferedWriter(new FileWriter(t));
                for (Object line : modifiedLines) {
                    bw.write((String) line);
                    bw.newLine();
                }
                bw.close();
            }
            File tmpNms = new File(craftBukkitGit.getRepository().getDirectory().getParentFile(), "tmp-nms");
            FileUtils.copyDirectory(nmsDir, tmpNms);

            craftBukkitGit.branchDelete().setBranchNames("patched").setForce(true).call();
            craftBukkitGit.checkout().setCreateBranch(true).setForceRefUpdate(true).setName("patched").call();
            craftBukkitGit.add().addFilepattern("src/main/java/net/").call();
            craftBukkitGit.commit().setSign(false).setMessage("CraftBukkit $ " + new Date()).call();
            craftBukkitGit.checkout().setName(buildInfo.getRefs().getCraftBukkit()).call();

            FileUtils.moveDirectory(tmpNms, nmsDir);

            if (versionInfo.getToolsVersion() < 93) {
                Utils.runTasksMultiThreaded(
                        () -> {
                            File spigotApi = new File(spigotGit.getRepository().getDirectory().getParentFile(), "Bukkit");

                            if (!spigotApi.exists()) {
                                Utils.gitClone("file://" + bukkitGit.getRepository().getDirectory().getParentFile().getAbsolutePath(), spigotApi, Bootstrap.AUTO_CRLF);
                            }

                            return 0;
                        },

                        () -> {
                            File spigotServer = new File(spigotGit.getRepository().getDirectory().getParentFile(), "CraftBukkit");

                            if (!spigotServer.exists()) {
                                Utils.gitClone("file://" + craftBukkitGit.getRepository().getDirectory().getParentFile().getAbsolutePath(), spigotServer, Bootstrap.AUTO_CRLF);
                            }

                            return 0;
                        }
                );
            }

            if (cfg.toCompile.contains(Compile.CRAFTBUKKIT)) {
                System.out.println("Compiling Bukkit");
                if (cfg.isDevMode) {
                    Utils.runCommand(bukkitGit.getRepository().getDirectory().getParentFile(), mvnCmd, "-P", "development", "clean", "install");
                } else {
                    Utils.runCommand(bukkitGit.getRepository().getDirectory().getParentFile(), mvnCmd, "clean", "install");
                }
                if (cfg.generateDoc) {
                    Utils.runCommand(bukkitGit.getRepository().getDirectory().getParentFile(), mvnCmd, "javadoc:jar");
                }
                if (cfg.generateSrc) {
                    Utils.runCommand(bukkitGit.getRepository().getDirectory().getParentFile(), mvnCmd, "source:jar");
                }

                System.out.println("Compiling CraftBukkit");
                if (cfg.isDevMode) {
                    Utils.runCommand(craftBukkitGit.getRepository().getDirectory().getParentFile(), mvnCmd, "-P", "development", "clean", "install");
                } else {
                    Utils.runCommand(craftBukkitGit.getRepository().getDirectory().getParentFile(), mvnCmd, "clean", "install");
                }
            }

            try {
                Utils.runCommand(spigotGit.getRepository().getDirectory().getParentFile(), shCmd, "applyPatches.sh");
                System.out.println("*** Spigot patches applied!");

                if (cfg.toCompile.contains(Compile.SPIGOT)) {
                    System.out.println("Compiling Spigot & Spigot-API");
                    if (cfg.isDevMode) {
                        Utils.runCommand(spigotGit.getRepository().getDirectory().getParentFile(), mvnCmd, "-P", "development", "clean", "install");
                    } else {
                        Utils.runCommand(spigotGit.getRepository().getDirectory().getParentFile(), mvnCmd, "clean", "install");
                    }
                }
            } catch (Exception ex) {
                throw new BuilderException("Error compiling Spigot. Please check the wiki for FAQs.\n" +
                        "If this does not resolve your issue then please pastebin the entire BuildTools.log.txt file when seeking support.", ex);
            }

            for (int i = 0; i < 36; ++i) {
                System.out.println();
            }

            System.out.println("Success! Everything completed successfully.");

            if (!cfg.toCompile.contains(Compile.NONE)) {
                if (cfg.toCompile.contains(Compile.CRAFTBUKKIT) && (versionInfo.getToolsVersion() < 101 || versionInfo.getToolsVersion() > 104)) {
                    copyJar("CraftBukkit/target", "craftbukkit", new File(cfg.outputDir, "craftbukkit-" + versionInfo.getMinecraftVersion() + ".jar"));
                }

                if (cfg.toCompile.contains(Compile.SPIGOT)) {
                    copyJar("Spigot/Spigot-Server/target", "spigot", new File(cfg.outputDir, "spigot-" + versionInfo.getMinecraftVersion() + ".jar"));
                }
            }
        }
    }

    private boolean prepareGitInstallation(File cwd) throws IOException {
        if (Utils.doesCommandFail(cwd, gitCmd, "--version")) {
            if (Bootstrap.IS_WINDOWS) {
                boolean arch64 = System.getProperty("os.arch").endsWith("64");

                // https://github.com/git-for-windows/git/releases/tag/v2.24.1.windows.2
                String gitVersion = "PortableGit-2.24.1.2-" + (arch64 ? "64" : "32") + "-bit";
                String gitHash = arch64 ?
                        "cb75e4a557e01dd27b5af5eb59dfe28adcbad21638777dd686429dd905d13899" :
                        "88f5525999228b0be8bb51788bfaa41b14430904bc65f1d4bbdcf441cac1f7fc";

                File gitDir = Paths.get(cwd.getPath(), gitVersion, "PortableGit").toFile();

                if (!gitDir.isDirectory()) {
                    System.out.println("\n*** Downloading PortableGit ***");

                    String installerName = gitVersion + ".7z.exe";

                    File gitInstaller = new File(gitDir.getParentFile(), installerName);
                    gitInstaller.deleteOnExit();

                    Utils.downloadFile("https://static.spigotmc.org/git/" + installerName, gitInstaller, HashAlgo.SHA256, gitHash);

                    System.out.println("Extracting downloaded git installer");
                    // yes to all, silent, don't run. Only -y seems to work.
                    Utils.runCommand(gitInstaller.getParentFile(), gitInstaller.getAbsolutePath(), "-y", "-gm2", "-nr");

                    Files.deleteIfExists(gitInstaller.toPath());
                }

                gitCmd = Paths.get(gitDir.getPath(), "bin", "git").toString();
                shCmd = Paths.get(gitCmd, "..", "sh").toString();
                System.out.println("\n*** Using PortableGit at '" + gitDir.getAbsolutePath() + "' ***\n");
            }

            if (Utils.doesCommandFail(cwd, gitCmd, "--version")) {
                return false;
            }
        }

        try {
            Utils.runCommand(cwd, gitCmd, "config", "--global", "--includes", "user.name");
        } catch (Exception ex) {
            System.out.println("Git name not set, setting it to default value.");
            Utils.runCommand(cwd, gitCmd, "config", "--global", "user.name", "BuildTools");
        }

        try {
            Utils.runCommand(cwd, gitCmd, "config", "--global", "--includes", "user.email");
        } catch (Exception ex) {
            System.out.println("Git email not set, setting it to default value.");
            Utils.runCommand(cwd, gitCmd, "config", "--global", "user.email", "unconfigured@null.spigotmc.org");
        }

        return true;
    }

    private boolean prepareMavenInstallation(File cwd) throws IOException {
        if (Utils.doesCommandFail(cwd, mvnCmd, "-B", "--version")) {
            // https://www.apache.org/dist/maven/maven-3/3.6.0/binaries/apache-maven-3.6.0-bin.zip.sha512
            String mvnVersion = "apache-maven-3.6.0";
            String mvnHash = "7d14ab2b713880538974aa361b987231473fbbed20e83586d542c691ace1139026f232bd46fdcce5e8887f528ab1c3fbfc1b2adec90518b6941235952d3868e9";

            File mvnDir = new File(cwd, mvnVersion);

            if (!mvnDir.isDirectory()) {
                System.out.println("\n*** Downloading Maven3 ***");

                File mvnZip = new File(mvnDir.getParentFile(), mvnVersion + "-bin.zip");
                mvnZip.deleteOnExit();

                Utils.downloadFile("https://static.spigotmc.org/maven/" + mvnZip.getName(), mvnZip, HashAlgo.SHA512, mvnHash);

                System.out.println("Extracting downloaded maven archive");
                Utils.extractZip(mvnZip, mvnDir.getParentFile(), null);

                Files.deleteIfExists(mvnZip.toPath());
            }

            mvnCmd = Paths.get(mvnDir.getPath(), "bin", "mvn" + (Bootstrap.IS_WINDOWS ? ".cmd" : "")).toString();
            System.out.println("*** Using Maven3 at '" + mvnDir.getAbsolutePath() + "' ***\n");

            return !Utils.doesCommandFail(cwd, mvnCmd, "-B", "--version");
        }

        return true;
    }

    private void copyJar(@NotNull String path, @NotNull String jarPrefix, @NotNull File outJar) throws IOException {
        File[] files = new File(cwd, path).listFiles((dir, name) -> name.startsWith(jarPrefix) && name.endsWith(".jar"));

        Files.createDirectories(outJar.getParentFile().toPath());

        for (File file : Objects.requireNonNull(files)) {
            Files.copy(file.toPath(), outJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.out.println(file.getName() + "\n  - Saved as " + outJar.getAbsolutePath());
        }
    }

    private static boolean checkHash(File vanillaJar, VersionInfo versionInfo, boolean dev) throws IOException {
        String hash = HashAlgo.MD5.getHash(FileUtils.readFileToByteArray(vanillaJar));

        if (dev || versionInfo.getMinecraftHash() == null || hash.equalsIgnoreCase(versionInfo.getMinecraftHash())) {
            System.out.println("Found good Minecraft hash (" + hash + ")");
            return true;
        } else {
            System.err.println("**** Warning, Minecraft jar hash of '" + hash + "' does not match stored hash: '" + versionInfo.getMinecraftHash() + "'");
        }

        return false;
    }

    public static class BuilderConfiguration {
        public final boolean skipUpdate;
        public final boolean exitAfterFetch;
        public final boolean generateSrc;
        public final boolean generateDoc;
        public final boolean isDevMode;
        public final boolean disableJavaCheck;
        public final boolean onlyCompileOnChange;
        public final boolean hasJenkinsVersion;

        public final @NotNull String jenkinsVersion;
        public final @NotNull List<Compile> toCompile;
        public final @NotNull File outputDir;

        public BuilderConfiguration(boolean skipUpdate, boolean exitAfterFetch, boolean generateSrc, boolean generateDoc, boolean isDevMode,
                                    boolean disableJavaCheck, boolean onlyCompileOnChange, boolean hasJenkinsVersion,
                                    @NotNull String jenkinsVersion, @NotNull List<Compile> toCompile, @NotNull File outputDir) {
            this.skipUpdate = skipUpdate;
            this.exitAfterFetch = exitAfterFetch;
            this.generateSrc = generateSrc;
            this.generateDoc = generateDoc;
            this.isDevMode = isDevMode;
            this.disableJavaCheck = disableJavaCheck;
            this.onlyCompileOnChange = onlyCompileOnChange;
            this.hasJenkinsVersion = hasJenkinsVersion;

            this.jenkinsVersion = jenkinsVersion;
            this.toCompile = toCompile;
            this.outputDir = outputDir;
        }
    }
}
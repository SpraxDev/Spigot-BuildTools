package org.spigotmc.builder;

import com.google.gson.Gson;
import difflib.DiffUtils;
import difflib.Patch;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.JGitInternalException;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.transport.FetchResult;
import org.spigotmc.builder.hasher.Hasher;
import org.spigotmc.builder.hasher.HasherMD5;
import org.spigotmc.builder.hasher.HasherSHA256;
import org.spigotmc.builder.hasher.HasherSHA512;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.NoSuchAlgorithmException;
import java.text.MessageFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.spigotmc.builder.Bootstrap.CWD;

// TODO: Fix/reword console logging
public class Builder {
    public static final boolean IS_WINDOWS = System.getProperty("os.name").startsWith("Windows");
    private static final boolean autocrlf = !"\n".equals(System.getProperty("line.separator"));
    private static final String JAVA_CMD = new File(new File(System.getProperty("java.home"), "bin"),
            "java" + (IS_WINDOWS ? ".exe" : "")).getAbsolutePath();

    // These variables may be filled with the path to a portable installation
    private static String gitCmd = "git";
    private static String mvnCmd = "mvn";

    private Builder() {
        throw new IllegalStateException("Utility class");
    }

    public static void runBuild(Bootstrap bootstrap) throws Exception {
        // FIXME: Configure GitHub-Actions and indicate that this is a fork
        // May be null
        String buildVersion = Builder.class.getPackage().getImplementationVersion();
        int buildNumber = -1;
        if (buildVersion != null) {
            String[] split = buildVersion.split("-");
            if (split.length == 4) {
                try {
                    buildNumber = Integer.parseInt(split[3]);
                } catch (NumberFormatException ignored) {
                }
            }
        }

        System.out.println("Loading BuildTools version: " + buildVersion + " (#" + buildNumber + ")");
        System.out.println("Java Version: " + JavaVersion.getCurrentVersion());
        System.out.println("Current Path: " + CWD.getAbsolutePath());

        // FIXME: Why is this an issue?
        if (CWD.getAbsolutePath().contains("'") ||
                CWD.getAbsolutePath().contains("#") ||
                CWD.getAbsolutePath().contains("~") ||
                CWD.getAbsolutePath().contains("(") ||
                CWD.getAbsolutePath().contains(")")) {
            System.err.println("Please do not run in a path with special characters!");
            System.exit(1);
            return;
        }

        if (CWD.getAbsolutePath().contains("Dropbox") ||
                CWD.getAbsolutePath().contains("OneDrive")) { // may just be a directory or file containing one of these strings
            System.err.println("*** WARNING: Please do not run BuildTools in a Dropbox, OneDrive, or similar. You can always copy the completed jars there later.");
            Thread.sleep(1500); // Give user time to read the warning
        }

        if (bootstrap.disableCertCheck) {
            Utils.disableHttpsCertificateCheck();
        }

        if (Utils.doesCommandFail(CWD, gitCmd, "--version")) {
            if (IS_WINDOWS) {
                String gitVersion = "PortableGit-2.24.1.2-" + (System.getProperty("os.arch").endsWith("64") ? "64" : "32") + "-bit";

                // https://github.com/git-for-windows/git/releases/tag/v2.24.1.windows.2
                String gitHash = System.getProperty("os.arch").endsWith("64") ?
                        "cb75e4a557e01dd27b5af5eb59dfe28adcbad21638777dd686429dd905d13899" :
                        "88f5525999228b0be8bb51788bfaa41b14430904bc65f1d4bbdcf441cac1f7fc";

                gitCmd = new File(new File(new File(CWD, gitVersion), "PortableGit"), "git.exe").getAbsolutePath();

                if (!new File(gitCmd).getParentFile().isDirectory()) {
                    System.out.println("*** Could not find git installation, downloading PortableGit. ***");

                    String gitName = gitVersion + ".7z.exe";
                    File gitInstall = new File(new File(CWD, gitVersion), gitName);
                    gitInstall.deleteOnExit();
                    Files.createDirectories(gitInstall.toPath());

                    if (!gitInstall.exists()) {
                        download("https://static.spigotmc.org/git/" + gitName, gitInstall, new HasherSHA256(), gitHash, bootstrap.dev);
                    }

                    System.out.println("Extracting downloaded git install");
                    // yes to all, silent, don't run. Only -y seems to work
                    Utils.runCommand(gitInstall.getParentFile(), gitInstall.getAbsolutePath(), "-y", "-gm2", "-nr");

                    Files.deleteIfExists(gitInstall.toPath());
                }

                if (Utils.doesCommandFail(CWD, gitCmd, "--version")) {
                    System.err.println("Could not run command 'git' and PortableGit at '" + gitCmd + "'\nPlease install git on your system: https://git-for-windows.github.io/");
                    System.exit(1);
                    return;
                }

                System.out.println("*** Using downloaded git '" + gitCmd + "' ***");
                System.out.println("*** Please note that this is a beta feature, so if it does not work please also try a manual install of git from https://git-for-windows.github.io/ ***");
            } else {
                System.err.println("Could not run command 'git', please install git on your system");
                System.exit(1);
                return;
            }
        }

        // FIXME: We don't want to set it globally!! This can lead to further user errors and confusion outside of BuildTools
        try {
            Utils.runCommand(CWD, "git", "config", "--global", "--includes", "user.name");
        } catch (Exception ex) {
            System.out.println("Git name not set, setting it to default value.");
            Utils.runCommand(CWD, "git", "config", "--global", "user.name", "BuildTools");
        }

        // FIXME: We don't want to set it globally!! This can lead to further user errors and confusion outside of BuildTools
        try {
            Utils.runCommand(CWD, "git", "config", "--global", "--includes", "user.email");
        } catch (Exception ex) {
            System.out.println("Git email not set, setting it to default value.");
            Utils.runCommand(CWD, "git", "config", "--global", "user.email", "unconfigured@null.spigotmc.org");
        }

        // This variable is used for '--compile-if-changed' and later updated too
        boolean gitReposDidChange = setupWorkingDir();  // Clone all missing repositories

        // Check for env var M2_HOME too if 'mvn' fails and only download if not usable
        if (Utils.doesCommandFail(CWD, mvnCmd, "-B", "--version")) {
            String mavenVersion = "apache-maven-3.6.0";

            File maven = new File(CWD, mavenVersion);
            mvnCmd = new File(new File(maven, "bin"), "mvn" + (IS_WINDOWS ? ".cmd" : "")).getAbsolutePath();

            if (!maven.exists()) {
                System.out.println("Maven does not exist, downloading. Please wait.");

                File mvnZip = new File(CWD, mavenVersion + "-bin.zip");
                mvnZip.deleteOnExit();

                // https://www.apache.org/dist/maven/maven-3/3.6.0/binaries/apache-maven-3.6.0-bin.zip.sha512
                download("https://static.spigotmc.org/maven/" + mvnZip.getName(), mvnZip, new HasherSHA512(),
                        "7d14ab2b713880538974aa361b987231473fbbed20e83586d542c691ace1139026f232bd46fdcce5e8887f528ab1c3fbfc1b2adec90518b6941235952d3868e9", bootstrap.dev);
                unzip(mvnZip, CWD);
                Files.deleteIfExists(mvnZip.toPath());
            }

            if (Utils.doesCommandFail(CWD, mvnCmd, "-B", "--version")) {
                System.err.println("Could not run command 'mvn' or '" + mvnCmd + "'\nPlease install maven on your system");
                System.exit(1);
                return;
            }

            System.out.println("*** Using downloaded maven '" + mvnCmd + "' ***");
        }

        VersionInfo versionInfo;
        File nmsDir;
        File tmpNms;
        try (Git bukkitGit = Git.open(GitRepository.BUKKIT.getRepoDir());
             Git spigotGit = Git.open(GitRepository.SPIGOT.getRepoDir());
             Git craftBukkitGit = Git.open(GitRepository.CRAFT_BUKKIT.getRepoDir())) {
            File workDir = new File(CWD, "work");
            File vanillaJar;
            Iterable<RevCommit> mappings;

            BuildInfo buildInfo = new BuildInfo("Dev Build", "Development", 0, null,
                    new BuildInfo.Refs("master", "master", "master", "master"));

            try (Git buildGit = Git.open(GitRepository.BUILD_DATA.getRepoDir())) {
                if (!bootstrap.doNotUpdate) {
                    if (!bootstrap.dev) {
                        System.out.println("Attempting to build version: '" + bootstrap.rev + "' use --rev <version> to override");

                        String verInfo;
                        try {
                            verInfo = get("https://hub.spigotmc.org/versions/" + bootstrap.rev + ".json");
                        } catch (IOException ex) {
                            System.err.println("Could not get version " + bootstrap.rev + " does it exist? Try another version or use 'latest'");
                            ex.printStackTrace();
                            System.exit(1);
                            return;
                        }

                        System.out.println("Found version");
                        System.out.println(verInfo);

                        buildInfo = new Gson().fromJson(verInfo, BuildInfo.class);

                        if (buildNumber != -1 && buildInfo.getToolsVersion() != -1 && buildNumber < buildInfo.getToolsVersion()) {
                            System.err.println("**** Your BuildTools is out of date and will not build the requested version. Please grab a new copy from https://www.spigotmc.org/go/buildtools-dl");
                            System.exit(1);
                            return;
                        }

                        if (!bootstrap.disableJavaCheck) {
                            if (buildInfo.getJavaVersions() == null) {
                                buildInfo.setJavaVersions(new int[] {
                                        JavaVersion.JAVA_7.getVersion(), JavaVersion.JAVA_8.getVersion()
                                });
                            }

                            if (buildInfo.getJavaVersions().length != 2) {
                                throw new IllegalArgumentException("Expected only two Java versions, got " +
                                        JavaVersion.printVersions(buildInfo.getJavaVersions()));
                            }

                            JavaVersion currVersion = JavaVersion.getCurrentVersion();
                            JavaVersion minVersion = JavaVersion.getByVersion(buildInfo.getJavaVersions()[0]);
                            JavaVersion maxVersion = JavaVersion.getByVersion(buildInfo.getJavaVersions()[1]);

                            if (currVersion.getVersion() < minVersion.getVersion() || currVersion.getVersion() > maxVersion.getVersion()) {
                                System.err.println("*** The version you have requested to build requires Java versions between "
                                        + JavaVersion.printVersions(buildInfo.getJavaVersions()) + ", but you are using " + currVersion);
                                System.err.println("*** Please rerun BuildTools using an appropriate Java version. " +
                                        "For obvious reasons outdated MC versions do not support Java versions that did not exist at their release.");
                                System.exit(1);
                                return;
                            }
                        }
                    }

                    gitReposDidChange = gitReposDidChange ||
                            pull(buildGit, buildInfo.getRefs().getBuildData()) ||
                            pull(bukkitGit, buildInfo.getRefs().getBukkit()) ||
                            pull(craftBukkitGit, buildInfo.getRefs().getCraftBukkit()) ||
                            pull(spigotGit, buildInfo.getRefs().getSpigot());

                    // Checks if any of the 4 repositories have been updated via a fetch, the '--compile-if-changed' flag is set and none of the repositories were frshly cloned in this run.
                    if (!gitReposDidChange && bootstrap.compileIfChanged) {
                        System.out.println("*** No changes detected in any of the repositories!");
                        System.out.println("*** Exiting due to '--compile-if-changes'");
                        System.exit(0);
                    }
                }

                try (InputStream in = new FileInputStream(new File("BuildData/info.json"))) {
                    versionInfo = new Gson().fromJson(
                            IOUtils.toString(in, StandardCharsets.UTF_8),
                            VersionInfo.class
                    );
                }

                // Default to 1.8 builds.
                if (versionInfo == null) {
                    versionInfo = new VersionInfo("1.8", "bukkit-1.8.at", "bukkit-1.8-cl.csrg", "bukkit-1.8-members.csrg", "package.srg", null);
                }
                System.out.println("Attempting to build Minecraft with details: " + versionInfo);

                if (buildNumber != -1 && versionInfo.getToolsVersion() != -1 && buildNumber < versionInfo.getToolsVersion()) {
                    System.err.println();
                    System.err.println("**** Your BuildTools is out of date and will not build the requested version. Please grab a new copy from https://www.spigotmc.org/go/buildtools-dl");
                    System.exit(1);
                }

                vanillaJar = new File(workDir, "minecraft_server." + versionInfo.getMinecraftVersion() + ".jar");
                if (!vanillaJar.exists() || !checkHash(vanillaJar, versionInfo, bootstrap.dev)) {
                    if (versionInfo.getServerUrl() != null) {
                        download(versionInfo.getServerUrl(), vanillaJar, new HasherMD5(), versionInfo.getMinecraftHash(), bootstrap.dev);
                    } else {
                        download(String.format("https://s3.amazonaws.com/Minecraft.Download/versions/%1$s/minecraft_server.%1$s.jar",
                                versionInfo.getMinecraftVersion()), vanillaJar, new HasherMD5(), versionInfo.getMinecraftHash(), bootstrap.dev);
                    }
                }

                mappings = buildGit.log()
                        .addPath("mappings/")
                        .setMaxCount(1).call();
            }

            Hasher mappingsHash = new HasherMD5();
            for (RevCommit rev : mappings) {
                mappingsHash.update(rev.getName().getBytes(StandardCharsets.UTF_8));
            }
            String mappingsVersion = mappingsHash.getHash().substring(24); // Last 8 chars

            File finalMappedJar = new File(workDir, "mapped." + mappingsVersion + ".jar");
            if (!finalMappedJar.exists()) {
                System.out.println("Final mapped jar: " + finalMappedJar + " does not exist, creating (please wait)!");

                File clMappedJar = new File(finalMappedJar + "-cl");
                File mMappedJar = new File(finalMappedJar + "-m");

                if (versionInfo.getClassMapCommand() == null) {
                    versionInfo.setClassMapCommand(JAVA_CMD + " -jar BuildData/bin/SpecialSource-2.jar map -i {0} -m {1} -o {2}");
                }
                String[] args = MessageFormat.format(versionInfo.getClassMapCommand(), vanillaJar.getPath(), "BuildData/mappings/" + versionInfo.getClassMappings(), clMappedJar.getPath()).split(" ");
                String cmd = args[0];
                args[0] = null;
                Utils.runCommand(CWD, cmd, args);

                if (versionInfo.getMemberMapCommand() == null) {
                    versionInfo.setMemberMapCommand(JAVA_CMD + " -jar BuildData/bin/SpecialSource-2.jar map -i {0} -m {1} -o {2}");
                }
                args = MessageFormat.format(versionInfo.getMemberMapCommand(), clMappedJar.getPath(), "BuildData/mappings/" + versionInfo.getMemberMappings(), mMappedJar.getPath()).split(" ");
                cmd = args[0];
                args[0] = null;
                Utils.runCommand(CWD, cmd, args);

                if (versionInfo.getFinalMapCommand() == null) {
                    versionInfo.setFinalMapCommand(JAVA_CMD + " -jar BuildData/bin/SpecialSource.jar --kill-lvt -i {0} --access-transformer {1} -m {2} -o {3}");
                }
                args = MessageFormat.format(versionInfo.getFinalMapCommand(), mMappedJar.getPath(), "BuildData/mappings/" + versionInfo.getAccessTransforms(), "BuildData/mappings/" + versionInfo.getPackageMappings(), finalMappedJar.getPath()).split(" ");
                cmd = args[0];
                args[0] = null;
                Utils.runCommand(CWD, cmd, args);
            }

            Utils.runCommand(CWD, mvnCmd, "install:install-file", "-Dfile=" + finalMappedJar, "-Dpackaging=jar", "-DgroupId=org.spigotmc",
                    "-DartifactId=minecraft-server", "-Dversion=" + versionInfo.getMinecraftVersion() + "-SNAPSHOT");

            File decompileDir = new File(workDir, "decompile-" + mappingsVersion);
            if (!decompileDir.exists()) {
                Files.createDirectories(decompileDir.toPath());

                File clazzDir = new File(decompileDir, "classes");
                unzip(finalMappedJar, clazzDir, input -> input.startsWith("net/minecraft/server"));
                if (versionInfo.getDecompileCommand() == null) {
                    versionInfo.setDecompileCommand(JAVA_CMD + " -jar BuildData/bin/fernflower.jar -dgs=1 -hdc=0 -rbr=0 -asc=1 -udv=0 {0} {1}");
                }

                String[] args = MessageFormat.format(versionInfo.getDecompileCommand(), clazzDir.getPath(), decompileDir.getPath()).split(" ");
                String cmd = args[0];
                args[0] = null;
                Utils.runCommand(CWD, cmd, args);
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
            nmsDir = new File(GitRepository.CRAFT_BUKKIT.getRepoDir(), "src/main/java/net");
            if (nmsDir.exists()) {
                System.out.println("Backing up NMS dir");
                FileUtils.moveDirectory(nmsDir, new File(workDir, "nms.old." + System.currentTimeMillis()));
            }
            File patchDir = new File(GitRepository.CRAFT_BUKKIT.getRepoDir(), "nms-patches");
            for (File file : Objects.requireNonNull(patchDir.listFiles())) {
                if (!file.getName().endsWith(".patch")) {
                    continue;
                }

                String targetFile = "net/minecraft/server/" + file.getName().replace(".patch", ".java");

                File clean = new File(decompileDir, targetFile);
                File t = new File(nmsDir.getParentFile(), targetFile);
                Files.createDirectories(t.getParentFile().toPath());

                System.out.println("Patching with " + file.getName());

                List<String> readFile;
                try (InputStream in = new FileInputStream(file)) {
                    readFile = IOUtils.readLines(in, StandardCharsets.UTF_8);
                }

                // Manually append prelude if it is not found in the first few lines.
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
                List<?> modifiedLines;

                try (InputStream in = new FileInputStream(clean)) {
                    modifiedLines = DiffUtils.patch(IOUtils.readLines(in, StandardCharsets.UTF_8), parsedPatch);
                }

                BufferedWriter bw = new BufferedWriter(new FileWriter(t));
                for (Object line : modifiedLines) {
                    bw.write((String) line);
                    bw.newLine();
                }
                bw.close();
            }
            tmpNms = new File(GitRepository.CRAFT_BUKKIT.getRepoDir(), "tmp-nms");
            FileUtils.copyDirectory(nmsDir, tmpNms);

            craftBukkitGit.branchDelete().setBranchNames("patched").setForce(true).call();
            craftBukkitGit.checkout().setCreateBranch(true).setForceRefUpdate(true).setName("patched").call();
            craftBukkitGit.add().addFilepattern("src/main/java/net/").call();
            craftBukkitGit.commit().setSign(false).setMessage("CraftBukkit $ " + new Date()).call();
            craftBukkitGit.checkout().setName(buildInfo.getRefs().getCraftBukkit()).call();
        }

        FileUtils.moveDirectory(tmpNms, nmsDir);

        if (versionInfo.getToolsVersion() < 93) {
            File spigotApi = new File(GitRepository.SPIGOT.getRepoDir(), "Bukkit");
            if (!spigotApi.exists()) {
                cloneGitRepo("file://" + GitRepository.BUKKIT.getRepoDir().getAbsolutePath(), spigotApi);
            }
            File spigotServer = new File(GitRepository.SPIGOT.getRepoDir(), "CraftBukkit");
            if (!spigotServer.exists()) {
                cloneGitRepo("file://" + GitRepository.CRAFT_BUKKIT.getRepoDir().getAbsolutePath(), spigotServer);
            }
        }

        if (bootstrap.compile.contains(Compile.CRAFTBUKKIT)) {
            System.out.println("Compiling Bukkit");
            if (bootstrap.dev) {
                Utils.runCommand(GitRepository.BUKKIT.getRepoDir(), mvnCmd, "-B", "-P", "development", "clean", "install");
            } else {
                Utils.runCommand(GitRepository.BUKKIT.getRepoDir(), mvnCmd, "-B", "clean", "install");
            }
            if (bootstrap.generateDoc) {
                Utils.runCommand(GitRepository.BUKKIT.getRepoDir(), mvnCmd, "-B", "javadoc:jar");
            }
            if (bootstrap.generateSrc) {
                Utils.runCommand(GitRepository.BUKKIT.getRepoDir(), mvnCmd, "-B", "source:jar");
            }

            System.out.println("Compiling CraftBukkit");
            if (bootstrap.dev) {
                Utils.runCommand(GitRepository.CRAFT_BUKKIT.getRepoDir(), mvnCmd, "-B", "-P", "development", "clean", "install");
            } else {
                Utils.runCommand(GitRepository.CRAFT_BUKKIT.getRepoDir(), mvnCmd, "-B", "clean", "install");
            }
        }

        try {
            Utils.runCommand(GitRepository.SPIGOT.getRepoDir(), "." + File.separatorChar + "applyPatches.sh");
            System.out.println("*** Spigot patches applied!");

            if (bootstrap.compile.contains(Compile.SPIGOT)) {
                System.out.println("Compiling Spigot & Spigot-API");
                if (bootstrap.dev) {
                    Utils.runCommand(GitRepository.SPIGOT.getRepoDir(), mvnCmd, "-B", "-P", "development", "clean", "install");
                } else {
                    Utils.runCommand(GitRepository.SPIGOT.getRepoDir(), mvnCmd, "-B", "clean", "install");
                }
            }
        } catch (Exception ex) {
            System.err.println("Error compiling Spigot. Please check the wiki for FAQs.");
            System.err.println("If this does not resolve your issue then please pastebin the entire BuildTools.log.txt file when seeking support.");
            ex.printStackTrace();
            System.exit(1);
        }

        for (int i = 0; i < 35; ++i) {
            System.out.println();
        }

        System.out.println("Success! Everything completed successfully." +
                (bootstrap.compile.contains(Compile.NONE) ? "" : " Copying final .jar files now."));
        if (bootstrap.compile.contains(Compile.CRAFTBUKKIT) && (versionInfo.getToolsVersion() < 101 || versionInfo.getToolsVersion() > 104)) {
            copyJar(new File(GitRepository.CRAFT_BUKKIT.getRepoDir(), "target").getAbsolutePath(),
                    "craftbukkit", new File(bootstrap.outputDir, "craftbukkit-" + versionInfo.getMinecraftVersion() + ".jar"));
        }
        if (bootstrap.compile.contains(Compile.SPIGOT)) {
            copyJar(new File(new File(GitRepository.SPIGOT.getRepoDir(), "Spigot-Server"), "target").getAbsolutePath(),
                    "spigot", new File(bootstrap.outputDir, "spigot-" + versionInfo.getMinecraftVersion() + ".jar"));
        }
    }

    /**
     * @return true, if at least one repository did not exist yet, false otherwise
     */
    private static boolean setupWorkingDir() throws IOException, GitAPIException {
        boolean result = false;

        Files.createDirectories(new File(CWD, "work").toPath());

        for (GitRepository repo : GitRepository.values()) {
            if (!new File(repo.getRepoDir(), ".git").exists()) {
                cloneGitRepo(repo.gitUrl, repo.getRepoDir());
                result = true;
            }
        }

        return result;
    }

    private static boolean checkHash(File vanillaJar, VersionInfo versionInfo, boolean dev) throws IOException, NoSuchAlgorithmException {
        String hash;

        try (InputStream in = new FileInputStream(vanillaJar)) {
            hash = new HasherMD5().getHash(IOUtils.toByteArray(in));
        }

        if (!dev && versionInfo.getMinecraftHash() != null && !hash.equals(versionInfo.getMinecraftHash())) {
            System.err.println("**** Warning, Minecraft jar hash of " + hash + " does not match stored hash of " + versionInfo.getMinecraftHash());
            return false;
        } else {
            System.out.println("Found good Minecraft hash (" + hash + ")");
            return true;
        }
    }

    public static String get(String url) throws IOException {
        URLConnection con = new URL(url).openConnection();
        con.setConnectTimeout(5000);
        con.setReadTimeout(5000);

        try (InputStream in = con.getInputStream()) {
            return IOUtils.toString(in, StandardCharsets.UTF_8);
        }
    }

    public static void copyJar(String path, final String jarPrefix, File outJar) throws Exception {
        File[] files = new File(path)
                .listFiles((dir, name) -> name.startsWith(jarPrefix) && name.endsWith(".jar"));

        if (!outJar.getParentFile().isDirectory()) {
            Files.createDirectories(outJar.getParentFile().toPath());
        }

        if (files != null) {
            for (File file : files) {
                System.out.println("Copying " + file.getName() + " to " + outJar.getAbsolutePath());
                Files.copy(file.toPath(), outJar.toPath(), StandardCopyOption.REPLACE_EXISTING);
                System.out.println("  - Saved as " + outJar);
            }
        }
    }

    public static boolean pull(Git repo, String ref) throws Exception {
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

                    System.out.println("Extracted: " + outFile);
                } else {
                    Files.createDirectories(outFile.toPath());
                }
            }
        }
    }

    public static void cloneGitRepo(String url, File target) throws GitAPIException, IOException {
        System.out.println("Starting clone of " + url + " to " + target.getAbsolutePath());

        try (Git result = Git.cloneRepository().setURI(url).setDirectory(target).call()) {
            StoredConfig config = result.getRepository().getConfig();
            config.setBoolean("core", null, "autocrlf", autocrlf);
            config.save();

            System.out.println("Cloned git repository " + url + " to " + target.getAbsolutePath() + ". Current HEAD: " + commitHash(result));
        }
    }

    public static String commitHash(Git repo) throws GitAPIException {
        return repo.log().setMaxCount(1).call().iterator().next().getName();
    }

    public static void download(String url, File target, Hasher hashFormat, String goodHash, boolean dev) throws IOException {
        System.out.println("Starting download of " + url);

        byte[] bytes = IOUtils.toByteArray(new URL(url));
        String hash = hashFormat.getHash(bytes);

        System.out.println("Downloaded file: " + target + " with hash: " + hash);

        if (!dev && goodHash != null && !goodHash.equals(hash)) {
            throw new IllegalStateException("Downloaded file: " + target + " did not match expected hash: " + goodHash);
        }

        Files.write(target.toPath(), bytes);
    }
}

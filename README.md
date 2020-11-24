<p align="center">
  <a href="https://sprax.me/discord">
    <img alt="Get Support on Discord" src="https://img.shields.io/discord/344982818863972352.svg?label=Get%20Support&logo=Discord&color=blue">
  </a>
  <a href="https://www.patreon.com/sprax">
    <img alt="Support me on Patreon"
         src="https://img.shields.io/badge/-Support%20me%20on%20Patreon-%23FF424D?logo=patreon&logoColor=white">
  </a>
</p>

<p align="center">
  <a href="https://github.com/SpraxDev/Spigot-BuildTools/actions?query=workflow%3ABuild">
    <img alt="Build" src="https://github.com/SpraxDev/Spigot-BuildTools/workflows/Build/badge.svg">
  </a>
  <a href="https://sonarcloud.io/dashboard?id=SpraxDev_Spigot-BuildTools">
    <img alt="Quality Gate Status"
         src="https://sonarcloud.io/api/project_badges/measure?project=SpraxDev_Spigot-BuildTools&metric=alert_status">
  </a>
</p>

# Spigot-BuildTools
This is a fork of the [original SpigotMC/BuildTools](https://hub.spigotmc.org/stash/projects/SPIGOT/repos/buildtools).

It introduces some minor improvements and is used in [SpraxDev/Action-SpigotMC](https://github.com/SpraxDev/Action-SpigotMC).


# (Breaking) Changes
* Flag `--skip-compile` is no longer supported - Use `--compile NONE` instead
* Use Flag `--exit-after-fetch` if you want BuildTools to exit after fetching all repositories
* Maven and Git are only downloaded if their command could not be executed (installed version has priority)
* Prints the seconds needed to finish the build process
* Multi-Threaded downloads (git clone, git fetch, git pull, ...)
* Allows special characters in file path that were forbidden before: `'#~()`
* Not warning the user about running in file path containing the words `OneDrive` or `Dropbox`


# Usage
You need Java 8 or newer.

Download the latest version from
[https://github.com/SpraxDev/Spigot-BuildTools/releases/latest](https://github.com/SpraxDev/Spigot-BuildTools/releases/latest).
It is recommended to check for a new release every couple weeks.

Open your Terminal (or *cmd.exe*) and type `java -jar BuildTools.jar`

|                       Argument                      |               Description               |
| :-------------------------------------------------: | :-------------------------------------: |
| `--help`, `-?`                                      | Show the help                           |
| `--disable-certificate-check`                       | Disable HTTPS certificate check         |
| `--disable-java-check`                              | Disable Java version check              |
| `--skip-update`                                     | Don't pull updates from Git             |
| `--exit-after-fetch`                                | Everything *--rev*  unrelated is downloaded (No de-/compiling) |
| `--generate-src`                                    | Generate source jar                     |
| `--generate-doc`                                    | Generate Javadoc jar                    |
| `--dev`                                             | Development mode                        |
| `--o <Path>`, `--output-dir <Path>`                 | Final jar output directory (defaults to current directory) |
| `--rev <Version>`                                   | Version to build (defaults to `latest`) |
| `--compile <[None,CraftBukkit,Spigot]>`             | Comma separated list of software to compile (defaults to `Spigot`) |
| `--only-compile-on-changed`, `--compile-if-changed` | Run BuildTools only when changes are detected in the repository |

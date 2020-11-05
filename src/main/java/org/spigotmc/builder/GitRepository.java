package org.spigotmc.builder;

public enum GitRepository {
    BUKKIT("Bukkit", "https://hub.spigotmc.org/stash/scm/spigot/bukkit.git"),
    CRAFT_BUKKIT("CraftBukkit", "https://hub.spigotmc.org/stash/scm/spigot/craftbukkit.git"),
    SPIGOT("Spigot", "https://hub.spigotmc.org/stash/scm/spigot/spigot.git"),
    BUILD_DATA("BuildData", "https://hub.spigotmc.org/stash/scm/spigot/builddata.git");

    public final String repoName;
    public final String gitUrl;

    GitRepository(String repoName, String gitUrl) {
        this.repoName = repoName;
        this.gitUrl = gitUrl;
    }
}

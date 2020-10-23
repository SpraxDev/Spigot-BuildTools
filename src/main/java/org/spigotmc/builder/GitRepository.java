package org.spigotmc.builder;

import java.io.File;

import static org.spigotmc.builder.Bootstrap.CWD;

public enum GitRepository {
    BUKKIT("Bukkit", "https://hub.spigotmc.org/stash/scm/spigot/bukkit.git"),
    CRAFT_BUKKIT("CraftBukkit", "https://hub.spigotmc.org/stash/scm/spigot/craftbukkit.git"),
    SPIGOT("Spigot", "https://hub.spigotmc.org/stash/scm/spigot/spigot.git"),
    BUILD_DATA("BuildData", "https://hub.spigotmc.org/stash/scm/spigot/builddata.git");

    public final String identifier, gitUrl;

    GitRepository(String identifier, String gitUrl) {
        this.identifier = identifier;
        this.gitUrl = gitUrl;
    }

    public File getRepoDir() {
        return new File(CWD, this.identifier);
    }
}

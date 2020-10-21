package org.spigotmc.builder;

public class BuildInfo {
    private final String name;
    private final String description;
    private int toolsVersion = -1;
    private int[] javaVersions;
    private final Refs refs;

    public BuildInfo(String name, String description, int toolsVersion, int[] javaVersions, Refs refs) {
        this.name = name;
        this.description = description;
        this.toolsVersion = toolsVersion;
        this.javaVersions = javaVersions;
        this.refs = refs;
    }

    public int getToolsVersion() {
        return this.toolsVersion;
    }

    public int[] getJavaVersions() {
        return this.javaVersions;
    }

    public void setJavaVersions(int[] javaVersions) {
        this.javaVersions = javaVersions;
    }

    public Refs getRefs() {
        return this.refs;
    }

    public static class Refs {
        private final String BuildData;
        private final String Bukkit;
        private final String CraftBukkit;
        private final String Spigot;

        public Refs(String BuildData, String Bukkit, String CraftBukkit, String Spigot) {
            this.BuildData = BuildData;
            this.Bukkit = Bukkit;
            this.CraftBukkit = CraftBukkit;
            this.Spigot = Spigot;
        }

        public String getBuildData() {
            return BuildData;
        }

        public String getBukkit() {
            return Bukkit;
        }

        public String getCraftBukkit() {
            return CraftBukkit;
        }

        public String getSpigot() {
            return Spigot;
        }
    }
}
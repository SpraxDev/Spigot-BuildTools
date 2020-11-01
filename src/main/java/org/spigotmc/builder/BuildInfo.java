package org.spigotmc.builder;

import java.util.Arrays;
import java.util.Objects;

@SuppressWarnings("UnusedAssignment")
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
        return toolsVersion;
    }

    public int[] getJavaVersions() {
        return javaVersions;
    }

    public void setJavaVersions(int[] javaVersions) {
        this.javaVersions = javaVersions;
    }

    public Refs getRefs() {
        return refs;
    }

    @Override
    public String toString() {
        return "BuildInfo{" +
                "name='" + name + '\'' +
                ", description='" + description + '\'' +
                ", toolsVersion=" + toolsVersion +
                ", javaVersions=" + Arrays.toString(javaVersions) +
                ", refs=" + refs +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        BuildInfo buildInfo = (BuildInfo) o;

        return toolsVersion == buildInfo.toolsVersion &&
                Objects.equals(name, buildInfo.name) &&
                Objects.equals(description, buildInfo.description) &&
                Arrays.equals(javaVersions, buildInfo.javaVersions) &&
                Objects.equals(refs, buildInfo.refs);
    }

    @Override
    public int hashCode() {
        int result = Objects.hash(name, description, toolsVersion, refs);
        result = 31 * result + Arrays.hashCode(javaVersions);

        return result;
    }

    public static class Refs {
        private final String BuildData;
        private final String Bukkit;
        private final String CraftBukkit;
        private final String Spigot;

        public Refs(String buildData, String bukkit, String craftBukkit, String spigot) {
            BuildData = buildData;
            Bukkit = bukkit;
            CraftBukkit = craftBukkit;
            Spigot = spigot;
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

        @Override
        public String toString() {
            return "Refs{" +
                    "BuildData='" + BuildData + '\'' +
                    ", Bukkit='" + Bukkit + '\'' +
                    ", CraftBukkit='" + CraftBukkit + '\'' +
                    ", Spigot='" + Spigot + '\'' +
                    '}';
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;

            Refs refs = (Refs) o;

            return Objects.equals(BuildData, refs.BuildData) &&
                    Objects.equals(Bukkit, refs.Bukkit) &&
                    Objects.equals(CraftBukkit, refs.CraftBukkit) &&
                    Objects.equals(Spigot, refs.Spigot);
        }

        @Override
        public int hashCode() {
            return Objects.hash(BuildData, Bukkit, CraftBukkit, Spigot);
        }
    }
}
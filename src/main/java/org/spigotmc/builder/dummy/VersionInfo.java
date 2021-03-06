package org.spigotmc.builder.dummy;

import java.util.Objects;

/**
 * This is a dummy class used when parsing JSON
 */
@SuppressWarnings({"FieldCanBeLocal"})
public class VersionInfo {
    private final String minecraftVersion;
    private final String accessTransforms;
    private final String classMappings;
    private final String memberMappings;
    private final String packageMappings;
    private final String minecraftHash;

    private String classMapCommand;
    private String memberMapCommand;
    private String finalMapCommand;
    private String decompileCommand;

    private String serverUrl;
    private int toolsVersion = -1;

    public VersionInfo(String minecraftVersion, String accessTransforms, String classMappings, String memberMappings, String packageMappings, String minecraftHash) {
        this.minecraftVersion = minecraftVersion;
        this.accessTransforms = accessTransforms;
        this.classMappings = classMappings;
        this.memberMappings = memberMappings;
        this.packageMappings = packageMappings;
        this.minecraftHash = minecraftHash;
    }

    @SuppressWarnings("unused")
    public VersionInfo(String minecraftVersion, String accessTransforms, String classMappings, String memberMappings, String packageMappings, String minecraftHash, String decompileCommand) {
        this.minecraftVersion = minecraftVersion;
        this.accessTransforms = accessTransforms;
        this.classMappings = classMappings;
        this.memberMappings = memberMappings;
        this.packageMappings = packageMappings;
        this.minecraftHash = minecraftHash;
        this.decompileCommand = decompileCommand;
    }

    @SuppressWarnings("unused")
    public VersionInfo(String minecraftVersion, String accessTransforms, String classMappings, String memberMappings,
                       String packageMappings, String minecraftHash, String classMapCommand, String memberMapCommand,
                       String finalMapCommand, String decompileCommand, String serverUrl, int toolsVersion) {
        this.minecraftVersion = minecraftVersion;
        this.accessTransforms = accessTransforms;
        this.classMappings = classMappings;
        this.memberMappings = memberMappings;
        this.packageMappings = packageMappings;
        this.minecraftHash = minecraftHash;
        this.classMapCommand = classMapCommand;
        this.memberMapCommand = memberMapCommand;
        this.finalMapCommand = finalMapCommand;
        this.decompileCommand = decompileCommand;
        this.serverUrl = serverUrl;
        this.toolsVersion = toolsVersion;
    }

    public String getMinecraftVersion() {
        return minecraftVersion;
    }

    public String getAccessTransforms() {
        return accessTransforms;
    }

    public String getClassMappings() {
        return classMappings;
    }

    public String getMemberMappings() {
        return memberMappings;
    }

    public String getPackageMappings() {
        return packageMappings;
    }

    public String getMinecraftHash() {
        return minecraftHash;
    }

    public String getClassMapCommand() {
        if (classMapCommand == null) {
            classMapCommand = "java -jar BuildData/bin/SpecialSource-2.jar map -i {0} -m {1} -o {2}";
        }

        return classMapCommand;
    }

    public String getMemberMapCommand() {
        if (memberMapCommand == null) {
            return "java -jar BuildData/bin/SpecialSource-2.jar map -i {0} -m {1} -o {2}";
        }

        return memberMapCommand;
    }

    public String getFinalMapCommand() {
        if (finalMapCommand == null) {
            return "java -jar BuildData/bin/SpecialSource.jar --kill-lvt -i {0} --access-transformer {1} -m {2} -o {3}";
        }

        return finalMapCommand;
    }

    public String getDecompileCommand() {
        return decompileCommand;
    }

    public void setDecompileCommand(String decompileCommand) {
        this.decompileCommand = decompileCommand;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public int getToolsVersion() {
        return toolsVersion;
    }

    @Override
    public String toString() {
        return "VersionInfo{" +
                "minecraftVersion='" + minecraftVersion + '\'' +
                ", accessTransforms='" + accessTransforms + '\'' +
                ", classMappings='" + classMappings + '\'' +
                ", memberMappings='" + memberMappings + '\'' +
                ", packageMappings='" + packageMappings + '\'' +
                ", minecraftHash='" + minecraftHash + '\'' +
                ", classMapCommand='" + classMapCommand + '\'' +
                ", memberMapCommand='" + memberMapCommand + '\'' +
                ", finalMapCommand='" + finalMapCommand + '\'' +
                ", decompileCommand='" + decompileCommand + '\'' +
                ", serverUrl='" + serverUrl + '\'' +
                ", toolsVersion=" + toolsVersion +
                '}';
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        VersionInfo that = (VersionInfo) o;

        return toolsVersion == that.toolsVersion &&
                Objects.equals(minecraftVersion, that.minecraftVersion) &&
                Objects.equals(accessTransforms, that.accessTransforms) &&
                Objects.equals(classMappings, that.classMappings) &&
                Objects.equals(memberMappings, that.memberMappings) &&
                Objects.equals(packageMappings, that.packageMappings) &&
                Objects.equals(minecraftHash, that.minecraftHash) &&
                Objects.equals(classMapCommand, that.classMapCommand) &&
                Objects.equals(memberMapCommand, that.memberMapCommand) &&
                Objects.equals(finalMapCommand, that.finalMapCommand) &&
                Objects.equals(decompileCommand, that.decompileCommand) &&
                Objects.equals(serverUrl, that.serverUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(minecraftVersion, accessTransforms, classMappings, memberMappings, packageMappings,
                minecraftHash, classMapCommand, memberMapCommand, finalMapCommand, decompileCommand,
                serverUrl, toolsVersion);
    }
}
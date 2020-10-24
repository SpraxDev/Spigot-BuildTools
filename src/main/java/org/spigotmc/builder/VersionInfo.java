package org.spigotmc.builder;

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
    private final int toolsVersion = -1;

    public VersionInfo(String minecraftVersion, String accessTransforms, String classMappings, String memberMappings, String packageMappings, String minecraftHash) {
        this.minecraftVersion = minecraftVersion;
        this.accessTransforms = accessTransforms;
        this.classMappings = classMappings;
        this.memberMappings = memberMappings;
        this.packageMappings = packageMappings;
        this.minecraftHash = minecraftHash;
    }

    public VersionInfo(String minecraftVersion, String accessTransforms, String classMappings, String memberMappings, String packageMappings, String minecraftHash, String decompileCommand) {
        this.minecraftVersion = minecraftVersion;
        this.accessTransforms = accessTransforms;
        this.classMappings = classMappings;
        this.memberMappings = memberMappings;
        this.packageMappings = packageMappings;
        this.minecraftHash = minecraftHash;
        this.decompileCommand = decompileCommand;
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
        return classMapCommand;
    }

    public void setClassMapCommand(String classMapCommand) {
        this.classMapCommand = classMapCommand;
    }

    public String getMemberMapCommand() {
        return memberMapCommand;
    }

    public void setMemberMapCommand(String memberMapCommand) {
        this.memberMapCommand = memberMapCommand;
    }

    public String getFinalMapCommand() {
        return finalMapCommand;
    }

    public void setFinalMapCommand(String finalMapCommand) {
        this.finalMapCommand = finalMapCommand;
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
                ", minecraftHash='" + minecraftHash + '\'' +
                ", serverUrl='" + serverUrl + '\'' +
                ", toolsVersion=" + toolsVersion +
                '}';
    }
}
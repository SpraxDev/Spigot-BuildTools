package org.spigotmc.builder;

import lombok.Data;

@Data
public class VersionInfo
{

    private String minecraftVersion;
    private String accessTransforms;
    private String classMappings;
    private String memberMappings;
    private String packageMappings;
    private String minecraftHash;
    private String decompileCommand = "java -jar BuildData/bin/fernflower.jar -dgs=1 -hdc=0 -rbr=0 -asc=1 -udv=0 {0} {1}";

    public VersionInfo(String minecraftVersion, String accessTransforms, String classMappings, String memberMappings, String packageMappings, String minecraftHash)
    {
        this.minecraftVersion = minecraftVersion;
        this.accessTransforms = accessTransforms;
        this.classMappings = classMappings;
        this.memberMappings = memberMappings;
        this.packageMappings = packageMappings;
        this.minecraftHash = minecraftHash;
    }
}

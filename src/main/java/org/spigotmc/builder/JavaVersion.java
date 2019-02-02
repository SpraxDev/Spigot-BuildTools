package org.spigotmc.builder;

import java.util.HashMap;
import java.util.Map;
import lombok.Getter;

@Getter
public class JavaVersion
{

    private static final Map<Integer, JavaVersion> byVersion = new HashMap<Integer, JavaVersion>();
    //
    public static final JavaVersion JAVA_5 = new JavaVersion( "Java 5", 49 );
    public static final JavaVersion JAVA_6 = new JavaVersion( "Java 6", 50 );
    public static final JavaVersion JAVA_7 = new JavaVersion( "Java 7", 51 );
    public static final JavaVersion JAVA_8 = new JavaVersion( "Java 8", 52 );
    public static final JavaVersion JAVA_9 = new JavaVersion( "Java 9", 53 );
    public static final JavaVersion JAVA_10 = new JavaVersion( "Java 10", 54 );
    public static final JavaVersion JAVA_11 = new JavaVersion( "Java 11", 55 );
    //
    private final String name;
    private final int version;
    private final boolean unknown;

    private JavaVersion(String name, int version)
    {
        this( name, version, false );
    }

    private JavaVersion(String name, int version, boolean unknown)
    {
        this.name = name;
        this.version = version;
        this.unknown = unknown;

        byVersion.put( version, this );
    }

    @Override
    public String toString()
    {
        return getName();
    }

    public static JavaVersion getByVersion(int version)
    {
        JavaVersion java = byVersion.get( version );
        if ( java == null )
        {
            java = new JavaVersion( "Java " + ( version - 44 ) + "*", version, true );
        }

        return java;
    }

    public static JavaVersion getCurrentVersion()
    {
        return getByVersion( (int) Float.parseFloat( System.getProperty( "java.class.version" ) ) );
    }

    public static String printVersions(int[] versions)
    {
        StringBuilder sb = new StringBuilder();

        sb.append( '[' );
        for ( int v : versions )
        {
            JavaVersion found = getByVersion( v );
            sb.append( found );
            sb.append( ", " );
        }
        sb.setLength( sb.length() - 2 );
        sb.append( ']' );

        return sb.toString();
    }
}

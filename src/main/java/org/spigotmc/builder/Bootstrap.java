package org.spigotmc.builder;

import com.google.common.base.Joiner;

public class Bootstrap
{

    public static void main(String[] args) throws Exception
    {
        JavaVersion javaVersion = JavaVersion.getCurrentVersion();

        if ( javaVersion.getVersion() < JavaVersion.JAVA_7.getVersion() )
        {
            System.err.println( "Outdated Java detected (" + javaVersion + "). BuildTools requires at least Java 7. Please update Java and try again." );
            System.err.println( "You may use java -version to double check your Java version." );
            System.exit( 1 );
        }

        if ( javaVersion.getVersion() < JavaVersion.JAVA_8.getVersion() )
        {
            System.err.println( "*** WARNING *** Outdated Java detected (" + javaVersion + "). Minecraft >= 1.12 requires at least Java 8." );
            System.err.println( "*** WARNING *** You may use java -version to double check your Java version." );
        }

        if ( javaVersion.isUnknown() )
        {
            System.err.println( "*** WARNING *** Unsupported Java detected (" + System.getProperty( "java.class.version" ) + "). BuildTools has only been tested up to Java 11. Use of development Java version is not supported." );
            System.err.println( "*** WARNING *** You may use java -version to double check your Java version." );
        }

        long memoryMb = Runtime.getRuntime().maxMemory() >> 20;
        if ( memoryMb < 448 ) // Older JVMs (including Java 8) report less than Xmx here. Allow some slack for people actually using -Xmx512M
        {
            System.err.println( "BuildTools requires at least 512M of memory to run (1024M recommended), but has only detected " + memoryMb + "M." );
            System.err.println( "This can often occur if you are running a 32-bit system, or one with low RAM." );
            System.err.println( "Please re-run BuildTools with manually specified memory, e.g: java -Xmx1024M -jar BuildTools.jar " + Joiner.on( ' ' ).join( args ) );
            System.exit( 1 );
        }

        Builder.main( args );
    }
}

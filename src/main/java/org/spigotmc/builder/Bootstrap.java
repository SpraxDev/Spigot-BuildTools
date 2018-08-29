package org.spigotmc.builder;

public class Bootstrap
{

    public static void main(String[] args) throws Exception
    {
        JavaVersion javaVersion = JavaVersion.getCurrentVersion();

        if ( javaVersion.getVersion() < JavaVersion.JAVA_7.getVersion() )
        {
            System.err.println( "Outdated Java detected (" + javaVersion + "). BuildTools requires at least Java 7. Please update Java and try again." );
            System.err.println( "You may use java -version to double check your Java version." );
            return;
        }

        if ( javaVersion.getVersion() < JavaVersion.JAVA_8.getVersion() )
        {
            System.err.println( "*** WARNING *** Outdated Java detected (" + javaVersion + "). Minecraft >= 1.12 requires at least Java 8." );
            System.err.println( "*** WARNING *** You may use java -version to double check your Java version." );
        }

        if ( javaVersion == JavaVersion.UNKNOWN )
        {
            System.err.println( "*** WARNING *** Unsupported Java detected (" + System.getProperty( "java.class.version" ) + "). BuildTools has only been tested up to Java 11. Use of development Java version is not supported." );
            System.err.println( "*** WARNING *** You may use java -version to double check your Java version." );
        }

        Builder.main( args );
    }
}

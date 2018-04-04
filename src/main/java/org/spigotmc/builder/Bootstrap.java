package org.spigotmc.builder;

public class Bootstrap
{

    public static void main(String[] args) throws Exception
    {
        float javaVersion = Float.parseFloat( System.getProperty( "java.class.version" ) );

        if ( javaVersion < 51.0 )
        {
            System.err.println( "Outdated Java detected (" + javaVersion + "). BuildTools requires at least Java 7. Please update Java and try again." );
            System.err.println( "You may use java -version to double check your Java version." );
            return;
        }

        if ( javaVersion < 52.0 )
        {
            System.err.println( "*** WARNING *** Outdated Java detected (" + javaVersion + "). Minecraft >= 1.12 requires at least Java 8." );
            System.err.println( "*** WARNING *** You may use java -version to double check your Java version." );
        }

        if ( javaVersion < 54.0 )
        {
            System.err.println( "*** WARNING *** Unsupported Java detected (" + javaVersion + "). BuildTools has only been tested up to Java 10. Use of development Java version is not supported." );
            System.err.println( "*** WARNING *** You may use java -version to double check your Java version." );
        }

        Builder.main( args );
    }
}

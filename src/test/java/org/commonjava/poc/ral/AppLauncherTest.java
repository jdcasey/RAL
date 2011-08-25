package org.commonjava.poc.ral;

import org.junit.Test;

public class AppLauncherTest
{

    @Test
    public void printLauncherHelp()
        throws Exception
    {
        AppLauncher.main( new String[] { "-h" } );
    }

    @Test
    public void printLauncherHelp_TrailingArgSeparator()
        throws Exception
    {
        AppLauncher.main( new String[] { "-h", "--" } );
    }

    @Test
    public void printLauncherHelp_TrailingAppArgs()
        throws Exception
    {
        AppLauncher.main( new String[] { "-h", "--", "-v" } );
    }

    @Test
    public void simpleAppResolution_mainMethod()
        throws Exception
    {
        AppLauncher.main( new String[] { "-P", "maven.home=/tmp/maven.home", "-N", "-c",
            "org.apache.maven.cli.MavenCli", "org.apache.maven:maven-embedder:3.0.3", "--", "-v" } );
    }

}

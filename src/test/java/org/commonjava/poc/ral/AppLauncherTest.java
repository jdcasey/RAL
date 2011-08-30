/*******************************************************************************
 * Copyright (C) 2011  John Casey
 * 
 * This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 * 
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Affero General Public License for more details.
 * 
 * You should have received a copy of the GNU Affero General Public License along with this program.  If not, see <http://www.gnu.org/licenses/>.
 ******************************************************************************/
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

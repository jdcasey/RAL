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

import static org.apache.maven.artifact.ArtifactUtils.key;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.security.Permission;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import org.apache.log4j.ConsoleAppender;
import org.apache.log4j.Level;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.apache.log4j.SimpleLayout;
import org.apache.log4j.spi.Configurator;
import org.apache.log4j.spi.LoggerRepository;
import org.apache.maven.RepositoryUtils;
import org.apache.maven.mae.MAEException;
import org.apache.maven.mae.app.AbstractMAEApplication;
import org.apache.maven.mae.depgraph.DepGraphNode;
import org.apache.maven.mae.depgraph.DependencyGraph;
import org.apache.maven.mae.depgraph.impl.DependencyGraphResolver;
import org.apache.maven.mae.depgraph.impl.FlexibleScopeDependencySelector;
import org.apache.maven.mae.project.ProjectLoader;
import org.apache.maven.mae.project.ProjectToolsException;
import org.apache.maven.mae.project.session.ProjectToolsSession;
import org.apache.maven.mae.project.session.SessionInitializer;
import org.apache.maven.mae.project.session.SimpleProjectToolsSession;
import org.apache.maven.model.building.ModelBuildingRequest;
import org.apache.maven.project.DefaultProjectBuildingRequest;
import org.apache.maven.project.MavenProject;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.CmdLineException;
import org.kohsuke.args4j.CmdLineParser;
import org.kohsuke.args4j.Option;
import org.kohsuke.args4j.spi.MapOptionHandler;
import org.sonatype.aether.RepositorySystem;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.resolution.ArtifactRequest;
import org.sonatype.aether.resolution.ArtifactResolutionException;
import org.sonatype.aether.resolution.ArtifactResult;
import org.sonatype.aether.util.DefaultRepositorySystemSession;

@Component( role = AppLauncher.class )
public class AppLauncher
    extends AbstractMAEApplication
{

    private static final Logger LOGGER = Logger.getLogger( AppLauncher.class );

    private static final String ARG_SEPARATOR = "--";

    public static final class Options
    {
        @Argument( metaVar = "G:A:V", usage = "Maven G:A:V for the artifact containing the main class to execute" )
        public String coordinate;

        @Option( name = "-c", aliases = "--Main-Class", usage = "Specify the main class to execute (for cases where the Main-Class Manifest attribute isn't available)" )
        public String mainClassName;

        @Option( name = "-m", aliases = "--Main-Method", usage = "Specify the main method to execute (default: 'main')" )
        public String mainMethodName = "main";

        @Option( name = "-N", aliases = "--no-exit", usage = "Skip calling System.exit" )
        public boolean noExit;

        @Option( name = "-P", multiValued = true, handler = MapOptionHandler.class, aliases = "--property", usage = "Define one or more system properties before launching" )
        public Map<String, String> properties;

        @Option( name = "-h", aliases = "--help", usage = "Print this message and exit" )
        public boolean help;
    }

    public static void main( final String[] args )
        throws AppLauncherException, CmdLineException
    {
        AppLauncherSecurityManager secManager = new AppLauncherSecurityManager();
        System.setSecurityManager( secManager );

        setupLogging( Level.INFO );

        try
        {
            int sepIdx = -1;
            for ( int idx = 0; idx < args.length; idx++ )
            {
                if ( ARG_SEPARATOR.equals( args[idx] ) )
                {
                    sepIdx = idx;
                    break;
                }
            }

            String[] launchArgs;
            String[] appArgs;
            if ( sepIdx == -1 )
            {
                launchArgs = args;
                appArgs = new String[0];
            }
            else
            {
                launchArgs = new String[sepIdx];
                appArgs = new String[args.length - ( sepIdx + 1 )];

                System.arraycopy( args, 0, launchArgs, 0, launchArgs.length );
                System.arraycopy( args, sepIdx + 1, appArgs, 0, appArgs.length );
            }

            Options options = new Options();
            CmdLineParser parser = new CmdLineParser( options );

            parser.parseArgument( launchArgs );

            if ( options.help )
            {
                parser.setUsageWidth( 100 );
                parser.printUsage( System.out );
            }
            else
            {
                int exit = 0;
                try
                {
                    new AppLauncher( options ).run( appArgs );
                }
                catch ( ExitException e )
                {
                    exit = e.getExit();
                }

                secManager.allowExit();
                if ( !options.noExit )
                {
                    System.exit( exit );
                }
            }
        }
        finally
        {
            secManager.allowExit();
        }
    }

    @Requirement
    private SessionInitializer sessionInitializer;

    @Requirement
    private DependencyGraphResolver graphResolver;

    @Requirement
    private ProjectLoader projectLoader;

    @Requirement
    private RepositorySystem repositorySystem;

    private final URLClassLoader classLoader;

    private final URL mainJar;

    private final Options options;

    public AppLauncher( final Options options )
        throws AppLauncherException
    {
        setupLogging( Level.INFO );

        this.options = options;

        try
        {
            load();
        }
        catch ( MAEException e )
        {
            throw new AppLauncherException( "Failed to initialize MAE subsystem: %s", e,
                                            e.getMessage() );
        }

        SimpleProjectToolsSession session = new SimpleProjectToolsSession();
        session.setPomValidationLevel( ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL );
        session.setDependencySelector( new FlexibleScopeDependencySelector(
                                                                            org.apache.maven.artifact.Artifact.SCOPE_TEST,
                                                                            org.apache.maven.artifact.Artifact.SCOPE_PROVIDED ).setTransitive( true ) );

        MavenProject project = loadProject( options.coordinate, session );

        DependencyGraph depGraph = resolveDependencies( project, session );

        List<URL> urls = new ArrayList<URL>();
        try
        {
            ArtifactResult result =
                repositorySystem.resolveArtifact( session.getRepositorySystemSession(),
                                                  new ArtifactRequest(
                                                                       RepositoryUtils.toArtifact( project.getArtifact() ),
                                                                       session.getRemoteRepositoriesForResolution(),
                                                                       null ) );

            mainJar = result.getArtifact().getFile().toURI().normalize().toURL();
            urls.add( mainJar );
        }
        catch ( ArtifactResolutionException e )
        {
            throw new AppLauncherException(
                                            "Failed to resolve main project artifact: %s. Reason: %s",
                                            e, project.getId(), e.getMessage() );
        }
        catch ( MalformedURLException e )
        {
            throw new AppLauncherException( "Cannot format classpath URL from: %s. Reason: %s", e,
                                            project.getId(), e.getMessage() );
        }

        for ( DepGraphNode node : depGraph )
        {
            try
            {
                ArtifactResult result = node.getLatestResult();
                if ( result == null )
                {
                    if ( node.getKey().equals( key( project.getGroupId(), project.getArtifactId(),
                                                    project.getVersion() ) ) )
                    {
                        continue;
                    }
                    else
                    {
                        throw new AppLauncherException( "Failed to resolve: %s", node.getKey() );
                    }
                }

                Artifact artifact = result.getArtifact();
                File file = artifact.getFile();
                URL url = file.toURI().normalize().toURL();
                urls.add( url );
            }
            catch ( MalformedURLException e )
            {
                throw new AppLauncherException( "Cannot format classpath URL from: %s. Reason: %s",
                                                e, node.getKey(), e.getMessage() );
            }
        }

        classLoader =
            new URLClassLoader( urls.toArray( new URL[] {} ), ClassLoader.getSystemClassLoader() );
    }

    public void run( final String[] appArgs )
        throws AppLauncherException
    {
        String mainClassName = getMainClassName();
        Properties original = setProperties();
        try
        {
            invoke( mainClassName, appArgs );
        }
        finally
        {
            System.setProperties( original );
        }
    }

    private Properties setProperties()
    {
        Properties original = System.getProperties();
        if ( options.properties != null )
        {
            Properties props = new Properties( original );
            for ( Map.Entry<String, String> propEntry : options.properties.entrySet() )
            {
                props.setProperty( propEntry.getKey(), propEntry.getValue() );
            }

            System.setProperties( props );
        }

        return original;
    }

    private void invoke( final String mainClassName, final String[] appArgs )
        throws AppLauncherException
    {
        try
        {
            Class<?> mainClass;
            mainClass = classLoader.loadClass( mainClassName );

            Method mainMethod =
                mainClass.getMethod( options.mainMethodName, new String[] {}.getClass() );

            mainMethod.invoke( null, new Object[] { appArgs } );
        }
        catch ( ClassNotFoundException e )
        {
            throw new AppLauncherException( "Cannot load main-class: %s.", e, mainClassName );
        }
        catch ( NoSuchMethodException e )
        {
            throw new AppLauncherException( "Cannot find 'main' method in: %s.", e, mainClassName );
        }
        catch ( IllegalAccessException e )
        {
            throw new AppLauncherException( "Cannot invoke 'main' method in: %s. Reason: %s", e,
                                            mainClassName, e.getMessage() );
        }
        catch ( InvocationTargetException e )
        {
            if ( e.getTargetException() instanceof ExitException )
            {
                throw (ExitException) e.getTargetException();
            }
            else
            {
                throw new AppLauncherException( "Cannot invoke 'main' method in: %s. Reason: %s",
                                                e.getTargetException(), mainClassName,
                                                e.getTargetException().getMessage() );
            }
        }
    }

    private String getMainClassName()
        throws AppLauncherException
    {
        String mainClassName = options.mainClassName;
        if ( mainClassName == null )
        {
            JarInputStream jis;
            try
            {
                jis = new JarInputStream( mainJar.openStream() );
            }
            catch ( IOException e )
            {
                throw new AppLauncherException( "Cannot load main-class from manifest of: %s.", e,
                                                mainJar );
            }

            Manifest manifest = jis.getManifest();
            mainClassName = manifest.getMainAttributes().getValue( "Main-Class" );
        }

        if ( mainClassName == null )
        {
            throw new AppLauncherException( "Cannot find Main-Class for: %s", mainJar );
        }

        return mainClassName;
    }

    @Override
    public String getId()
    {
        return "ral";
    }

    @Override
    public String getName()
    {
        return "Remote Application Launcher";
    }

    private static boolean loggingSet = false;

    private static void setupLogging( final Level logLevel )
    {
        if ( loggingSet )
        {
            return;
        }

        final Configurator log4jConfigurator = new Configurator()
        {
            @Override
            public void doConfigure( final URL notUsed, final LoggerRepository repo )
            {
                final ConsoleAppender cAppender = new ConsoleAppender( new SimpleLayout() );
                cAppender.setThreshold( logLevel );

                repo.setThreshold( logLevel );
                repo.getRootLogger().removeAllAppenders();
                repo.getRootLogger().setLevel( logLevel );
                repo.getRootLogger().addAppender( cAppender );

                @SuppressWarnings( "unchecked" )
                List<Logger> loggers = Collections.list( repo.getCurrentLoggers() );

                for ( final Logger logger : loggers )
                {
                    logger.setLevel( Level.TRACE );
                }
            }
        };

        log4jConfigurator.doConfigure( null, LogManager.getLoggerRepository() );
        loggingSet = true;
    }

    private DependencyGraph resolveDependencies( final MavenProject project,
                                                 final SimpleProjectToolsSession session )
        throws AppLauncherException
    {
        if ( LOGGER.isDebugEnabled() )
        {
            if ( LOGGER.isDebugEnabled() )
            {
                LOGGER.debug( "Setting up repository session to resolve dependencies..." );
            }
        }
        final DefaultProjectBuildingRequest pbr;
        try
        {
            sessionInitializer.initializeSessionComponents( session );

            pbr = new DefaultProjectBuildingRequest( session.getProjectBuildingRequest() );
            pbr.setValidationLevel( ModelBuildingRequest.VALIDATION_LEVEL_MINIMAL );
            session.setProjectBuildingRequest( pbr );
        }
        catch ( final MAEException e )
        {
            throw new AppLauncherException(
                                            "Failed to initialize workspace for capture analysis: %s",
                                            e, e.getMessage() );
        }

        final DefaultRepositorySystemSession rss =
            (DefaultRepositorySystemSession) pbr.getRepositorySession();

        if ( LOGGER.isDebugEnabled() )
        {
            if ( LOGGER.isDebugEnabled() )
            {
                LOGGER.debug( "Resolving dependency graph..." );
            }
        }

        Collection<MavenProject> projects = Collections.singleton( project );

        final DependencyGraph depGraph = graphResolver.accumulateGraph( projects, rss, session );
        graphResolver.resolveGraph( depGraph, projects, rss, session );

        return depGraph;
    }

    private MavenProject loadProject( final String coordinate, final ProjectToolsSession session )
        throws AppLauncherException
    {
        final String[] parts = coordinate.split( ":" );
        if ( parts.length != 3 )
        {
            throw new AppLauncherException(
                                            "Invalid project coordinate: '%s'. Cannot load MavenProject instance.",
                                            coordinate );
        }

        try
        {
            return projectLoader.buildProjectInstance( parts[0], parts[1], parts[2], session );
        }
        catch ( final ProjectToolsException e )
        {
            throw new AppLauncherException(
                                            "Failed to load project instance from coordinate: '%s'. Reason: %s",
                                            e, coordinate, e.getMessage() );
        }
    }

    private static final class ExitException
        extends SecurityException
    {
        private static final long serialVersionUID = 1L;

        private final int exit;

        ExitException( final int exit )
        {
            this.exit = exit;
        }

        int getExit()
        {
            return exit;
        }
    }

    private static final class AppLauncherSecurityManager
        extends SecurityManager
    {

        private final SecurityManager delegate;

        private boolean allowExit;

        public AppLauncherSecurityManager()
        {
            delegate = System.getSecurityManager();
        }

        public void allowExit()
        {
            this.allowExit = true;
        }

        @Override
        public void checkExit( final int status )
        {
            if ( !allowExit )
            {
                throw new ExitException( status );
            }
        }

        @Override
        public void checkPermission( final Permission perm )
        {
            if ( delegate != null )
            {
                delegate.checkPermission( perm );
            }
        }

        @Override
        public void checkPermission( final Permission perm, final Object context )
        {
            if ( delegate != null )
            {
                delegate.checkPermission( perm, context );
            }
        }

        @Override
        public void checkCreateClassLoader()
        {
            if ( delegate != null )
            {
                delegate.checkCreateClassLoader();
            }
        }

        @Override
        public void checkAccess( final Thread t )
        {
            if ( delegate != null )
            {
                delegate.checkAccess( t );
            }
        }

        @Override
        public void checkAccess( final ThreadGroup g )
        {
            if ( delegate != null )
            {
                delegate.checkAccess( g );
            }
        }

        @Override
        public void checkExec( final String cmd )
        {
            if ( delegate != null )
            {
                delegate.checkExec( cmd );
            }
        }

        @Override
        public void checkLink( final String lib )
        {
            if ( delegate != null )
            {
                delegate.checkLink( lib );
            }
        }

        @Override
        public void checkRead( final FileDescriptor fd )
        {
            if ( delegate != null )
            {
                delegate.checkRead( fd );
            }
        }

        @Override
        public void checkRead( final String file )
        {
            if ( delegate != null )
            {
                delegate.checkRead( file );
            }
        }

        @Override
        public void checkRead( final String file, final Object context )
        {
            if ( delegate != null )
            {
                delegate.checkRead( file, context );
            }
        }

        @Override
        public void checkWrite( final FileDescriptor fd )
        {
            if ( delegate != null )
            {
                delegate.checkWrite( fd );
            }
        }

        @Override
        public void checkWrite( final String file )
        {
            if ( delegate != null )
            {
                delegate.checkWrite( file );
            }
        }

        @Override
        public void checkDelete( final String file )
        {
            if ( delegate != null )
            {
                delegate.checkDelete( file );
            }
        }

        @Override
        public void checkConnect( final String host, final int port )
        {
            if ( delegate != null )
            {
                delegate.checkConnect( host, port );
            }
        }

        @Override
        public void checkConnect( final String host, final int port, final Object context )
        {
            if ( delegate != null )
            {
                delegate.checkConnect( host, port, context );
            }
        }

        @Override
        public void checkListen( final int port )
        {
            if ( delegate != null )
            {
                delegate.checkListen( port );
            }
        }

        @Override
        public void checkAccept( final String host, final int port )
        {
            if ( delegate != null )
            {
                delegate.checkAccept( host, port );
            }
        }

        @Override
        public void checkMulticast( final InetAddress maddr )
        {
            if ( delegate != null )
            {
                delegate.checkMulticast( maddr );
            }
        }

        @SuppressWarnings( "deprecation" )
        @Override
        public void checkMulticast( final InetAddress maddr, final byte ttl )
        {
            if ( delegate != null )
            {
                delegate.checkMulticast( maddr, ttl );
            }
        }

        @Override
        public void checkPropertiesAccess()
        {
            if ( delegate != null )
            {
                delegate.checkPropertiesAccess();
            }
        }

        @Override
        public void checkPropertyAccess( final String key )
        {
            if ( delegate != null )
            {
                delegate.checkPropertyAccess( key );
            }
        }

        @Override
        public void checkPrintJobAccess()
        {
            if ( delegate != null )
            {
                delegate.checkPrintJobAccess();
            }
        }

        @Override
        public void checkSystemClipboardAccess()
        {
            if ( delegate != null )
            {
                delegate.checkSystemClipboardAccess();
            }
        }

        @Override
        public void checkAwtEventQueueAccess()
        {
            if ( delegate != null )
            {
                delegate.checkAwtEventQueueAccess();
            }
        }

        @Override
        public void checkPackageAccess( final String pkg )
        {
            if ( delegate != null )
            {
                delegate.checkPackageAccess( pkg );
            }
        }

        @Override
        public void checkPackageDefinition( final String pkg )
        {
            if ( delegate != null )
            {
                delegate.checkPackageDefinition( pkg );
            }
        }

        @Override
        public void checkSetFactory()
        {
            if ( delegate != null )
            {
                delegate.checkSetFactory();
            }
        }

        @Override
        public void checkMemberAccess( final Class<?> clazz, final int which )
        {
            if ( delegate != null )
            {
                delegate.checkMemberAccess( clazz, which );
            }
        }

        @Override
        public void checkSecurityAccess( final String target )
        {
            if ( delegate != null )
            {
                delegate.checkSecurityAccess( target );
            }
        }

    }

}

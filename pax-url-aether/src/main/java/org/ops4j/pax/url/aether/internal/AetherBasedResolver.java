/*
 * Copyright (C) 2010 Okidokiteam
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.url.aether.internal;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.maven.repository.internal.DefaultServiceLocator;
import org.apache.maven.repository.internal.MavenRepositorySystemSession;
import org.sonatype.aether.RepositoryException;
import org.sonatype.aether.RepositorySystem;
import org.sonatype.aether.RepositorySystemSession;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.connector.wagon.WagonProvider;
import org.sonatype.aether.connector.wagon.WagonRepositoryConnectorFactory;
import org.sonatype.aether.repository.LocalRepository;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.resolution.ArtifactRequest;
import org.sonatype.aether.resolution.VersionRangeRequest;
import org.sonatype.aether.resolution.VersionRangeResolutionException;
import org.sonatype.aether.resolution.VersionRangeResult;
import org.sonatype.aether.spi.connector.RepositoryConnectorFactory;
import org.sonatype.aether.spi.log.Logger;
import org.sonatype.aether.util.artifact.DefaultArtifact;
import org.sonatype.aether.version.Version;

/**
 * Aether based, drop in replacement for mvn protocol
 */
public class AetherBasedResolver {

    private static final Log LOG = LogFactory.getLog( AetherBasedResolver.class );
    private static final String LATEST_VERSION_RANGE = "(0.0,]";
    private static final String MAVEN_METADATA_XML = "maven-metadata.xml";
    private static final String FILE_DUMMY_REPO = "file:///dummy";
    private static final String REPO_TYPE = "default";

    final private File m_localRepo;
    final private RepositorySystem m_repoSystem;
    final private List<RemoteRepository> m_remoteRepos;

    public AetherBasedResolver( File local, List<String> repos )
    {
        m_localRepo = local;
        m_repoSystem = newRepositorySystem();

        m_remoteRepos = new ArrayList<RemoteRepository>();
        int i = 0;

        // aether does not really like no remote repo at all ..

        for( String r : repos ) {
            String id = "repo" + ( i++ );

            if( isAvailable( r ) ) {
                m_remoteRepos.add( new RemoteRepository( id, REPO_TYPE, r ) );
            }
            else {
                // to enable cached loading
                m_remoteRepos.add(  new RemoteRepository( id, REPO_TYPE, FILE_DUMMY_REPO ) );
            }
        }

    }

    /**
     * This is a workaround for Aether 1.11 failing if at least one remote repo is not available currently.
     * Which is kind of bad.
     *
     * @param url to test for connection
     *
     * @return true if its available. Otherwise false.
     */
    private boolean isAvailable( String url )
    {
        try {
            new URL( url ).openStream().close();
            return true;
        } catch( IOException e ) {
            // e.printStackTrace();
        }
        return false;
    }

    public InputStream resolve( String groupId, String artifactId, String extension, String version )
        throws IOException
    {
        // version = mapLatestToRange( version );

        RepositorySystemSession session = newSession( m_repoSystem );
        Artifact artifact = new DefaultArtifact( groupId, artifactId, extension, version );
        File resolved = resolve( session, artifact );

        LOG.info( "Resolved (" + artifact.toString() + ") as " + resolved.getAbsolutePath() );
        return new FileInputStream( resolved );

    }

    private File resolve( RepositorySystemSession session, Artifact artifact )
        throws IOException
    {
        try {

            artifact = resolveLatestVersionRange( session, artifact );
            //  Metadata metadata = new DefaultMetadata( artifact.getGroupId(), artifact.getArtifactId(), MAVEN_METADATA_XML, Metadata.Nature.RELEASE_OR_SNAPSHOT );
            //  List<MetadataResult> metadataResults = m_repoSystem.resolveMetadata( session, Arrays.asList( new MetadataRequest( metadata ) ) );

            return m_repoSystem.resolveArtifact( session, new ArtifactRequest( artifact, m_remoteRepos, null ) ).getArtifact().getFile();
        } catch( RepositoryException e ) {
            throw new IOException( "Aether Error.", e );
        }
    }

    private Artifact resolveLatestVersionRange( RepositorySystemSession session, Artifact artifact )
        throws VersionRangeResolutionException
    {
        if( artifact.getVersion().equals( "LATEST" ) ) {
            artifact = artifact.setVersion( LATEST_VERSION_RANGE );

            VersionRangeResult versionResult = m_repoSystem.resolveVersionRange( session, new VersionRangeRequest( artifact, m_remoteRepos, null ) );
            if( versionResult != null ) {
                Version v = versionResult.getHighestVersion();
                artifact = artifact.setVersion( v.toString() );
            }
        }
        return artifact;
    }

    private RepositorySystemSession newSession( RepositorySystem system )
    {
        assert m_localRepo != null : "local repository cannot be null";
        assert m_localRepo.exists() : "local repository must exist (" + m_localRepo + ").";

        MavenRepositorySystemSession session = new MavenRepositorySystemSession();

        //session.setOffline( true );

        LocalRepository localRepo = new LocalRepository( m_localRepo );

        session.setLocalRepositoryManager( system.newLocalRepositoryManager( localRepo ) );

        return session;
    }

    private RepositorySystem newRepositorySystem()
    {
        DefaultServiceLocator locator = new DefaultServiceLocator();

        locator.setServices( WagonProvider.class, new ManualWagonProvider() );
        locator.addService( RepositoryConnectorFactory.class, WagonRepositoryConnectorFactory.class );
        locator.setService( Logger.class, LogAdapter.class );

        return locator.getService( RepositorySystem.class );
    }
}

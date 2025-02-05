/*
 * Copyright © Red Gate Software Ltd 2010-2021
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.flywaydb.core.internal.resolver.sql;

import org.flywaydb.core.api.MigrationType;
import org.flywaydb.core.api.ResourceProvider;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.Configuration;
import org.flywaydb.core.api.logging.Log;
import org.flywaydb.core.api.logging.LogFactory;
import org.flywaydb.core.api.resolver.Context;
import org.flywaydb.core.api.resolver.MigrationResolver;
import org.flywaydb.core.api.resolver.ResolvedMigration;
import org.flywaydb.core.api.resource.LoadableResource;
import org.flywaydb.core.internal.parser.ParsingContext;
import org.flywaydb.core.internal.parser.PlaceholderReplacingReader;
import org.flywaydb.core.internal.resolver.ChecksumCalculator;
import org.flywaydb.core.internal.resolver.ResolvedMigrationComparator;
import org.flywaydb.core.internal.resolver.ResolvedMigrationImpl;
import org.flywaydb.core.internal.resource.ResourceName;
import org.flywaydb.core.internal.resource.ResourceNameParser;
import org.flywaydb.core.internal.sqlscript.SqlScript;
import org.flywaydb.core.internal.sqlscript.SqlScriptExecutorFactory;
import org.flywaydb.core.internal.sqlscript.SqlScriptFactory;

import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * Migration resolver for SQL files on the classpath. The SQL files must have names like
 * V1__Description.sql, V1_1__Description.sql, or R__description.sql.
 */
public class SqlMigrationResolver implements MigrationResolver {
    private static final Log                      LOG = LogFactory.getLog( SqlMigrationResolver.class );
    private final        SqlScriptExecutorFactory sqlScriptExecutorFactory;
    private final        ResourceProvider         resourceProvider;
    private final        SqlScriptFactory         sqlScriptFactory;
    private final        Configuration            configuration;
    private final        ParsingContext           parsingContext;

    public SqlMigrationResolver(
        ResourceProvider resourceProvider, SqlScriptExecutorFactory sqlScriptExecutorFactory,
        SqlScriptFactory sqlScriptFactory, Configuration configuration, ParsingContext parsingContext
    ) {
        this.sqlScriptExecutorFactory = sqlScriptExecutorFactory;
        this.resourceProvider = resourceProvider;
        this.sqlScriptFactory = sqlScriptFactory;
        this.configuration = configuration;
        this.parsingContext = parsingContext;
    }

    public List<ResolvedMigration> resolveMigrations( Context context ) {
        List<ResolvedMigration> migrations = new ArrayList<>();
        String[]                suffixes   = configuration.getSqlMigrationSuffixes();

        addMigrations( migrations, configuration.getSqlMigrationPrefix(), suffixes,
            false


        );


        addMigrations( migrations, configuration.getRepeatableSqlMigrationPrefix(), suffixes,
            true


        );

        migrations.sort( new ResolvedMigrationComparator() );
        return migrations;
    }

    private LoadableResource createPlaceholderReplacingLoadableResource( LoadableResource loadableResource ) {
        return new LoadableResource() {
            @Override
            public Reader read() {
                return PlaceholderReplacingReader.create( configuration, parsingContext, loadableResource.read() );
            }

            @Override
            public String getAbsolutePath() {
                return loadableResource.getAbsolutePath();
            }

            @Override
            public String getAbsolutePathOnDisk() {
                return loadableResource.getAbsolutePathOnDisk();
            }

            @Override
            public String getFilename() {
                return loadableResource.getFilename();
            }

            @Override
            public String getRelativePath() {
                return loadableResource.getRelativePath();
            }
        };
    }

    private Integer getChecksumForLoadableResource( boolean repeatable, LoadableResource loadableResource ) {
        if ( repeatable && configuration.isPlaceholderReplacement() ) {
            return ChecksumCalculator.calculateForSql( createPlaceholderReplacingLoadableResource( loadableResource ) );
        }

        return ChecksumCalculator.calculateForSql( loadableResource );
    }

    private Integer getEquivalentChecksumForLoadableResource( boolean repeatable, LoadableResource loadableResource ) {
        if ( repeatable ) {
            return ChecksumCalculator.calculateForSql( loadableResource );
        }

        return null;
    }

    private void addMigrations(
        List<ResolvedMigration> migrations, String prefix, String[] suffixes,
        boolean repeatable
    ) {
        ResourceNameParser resourceNameParser = new ResourceNameParser( configuration );

        for ( LoadableResource resource : resourceProvider.getResources( prefix, suffixes ) ) {
            String       filename = resource.getFilename();
            ResourceName result   = resourceNameParser.parse( filename );

            if ( !result.isValid() || isSqlCallback( result ) || !prefix.equals( result.getPrefix() ) ) {
                continue;
            }

            SqlScript sqlScript = sqlScriptFactory.createSqlScript(
                resource,
                configuration.isMixed(),
                resourceProvider
            );

            Integer checksum           = getChecksumForLoadableResource( repeatable, resource );
            Integer equivalentChecksum = getEquivalentChecksumForLoadableResource( repeatable, resource );

            migrations.add( new ResolvedMigrationImpl(
                result.getVersion(),
                result.getDescription(),
                resource.getRelativePath(),
                checksum,
                equivalentChecksum,
                MigrationType.SQL,
                resource.getAbsolutePathOnDisk(),
                new SqlMigrationExecutor( sqlScriptExecutorFactory, sqlScript, false, false )
            ) {
                @Override
                public void validate() {
                }
            } );
        }
    }

    /**
     * Checks whether this filename is actually a sql-based callback instead of a regular migration.
     *
     * @param result The parsing result to check.
     */
    protected static boolean isSqlCallback( ResourceName result ) {
        return Event.fromId( result.getPrefix() ) != null;
    }
}
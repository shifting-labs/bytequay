/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.persistence.sqlite.config;

import com.google.common.collect.ImmutableList;
import org.hibernate.boot.Metadata;
import org.hibernate.boot.spi.BootstrapContext;
import org.hibernate.cfg.AvailableSettings;
import org.hibernate.engine.jdbc.connections.spi.JdbcConnectionAccess;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.integrator.spi.Integrator;
import org.hibernate.mapping.Table;
import org.hibernate.service.spi.SessionFactoryServiceRegistry;
import org.hibernate.tool.schema.spi.Exporter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.Set;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static com.google.common.collect.ImmutableSet.toImmutableSet;
import static java.util.Locale.ENGLISH;

/**
 * Hibernate's schema update leaves removed columns and unique indexes behind, and keeps the value list an enum
 * column's CHECK got when its table was created. Remove the obsolete constraints that prevent inserts before
 * Hibernate updates the current schema.
 */
final class SqliteSchemaCleanup
        implements Integrator
{
    private static final Logger log = LoggerFactory.getLogger(SqliteSchemaCleanup.class);
    private static final Pattern VALUE_CHECK = Pattern.compile("check \\((\\w+) in \\([^()]*\\)\\)", Pattern.CASE_INSENSITIVE);

    @Override
    public void integrate(Metadata metadata, BootstrapContext bootstrapContext, SessionFactoryImplementor sessionFactory)
    {
        if (!"update".equals(sessionFactory.getProperties().get(AvailableSettings.HBM2DDL_AUTO))) {
            return;
        }
        Exporter<Table> exporter = sessionFactory.getJdbcServices().getDialect().getTableExporter();
        JdbcConnectionAccess access = sessionFactory.getJdbcServices().getBootstrapJdbcConnectionAccess();
        try {
            Connection connection = access.obtainConnection();
            try {
                for (Table table : metadata.collectTableMappings()) {
                    if (table.isPhysicalTable()) {
                        dropUnmappedRequiredColumns(connection, table);
                        String[] create = exporter.getSqlCreateStrings(table, metadata, sessionFactory.getSqlStringGenerationContext());
                        matchValueChecks(connection, table.getName(), String.join("\n", create));
                    }
                }
            }
            finally {
                access.releaseConnection(connection);
            }
        }
        catch (SQLException e) {
            log.warn("Could not check for obsolete schema constraints", e);
        }
    }

    private static void dropUnmappedRequiredColumns(Connection connection, Table table)
            throws SQLException
    {
        Set<String> mapped = table.getColumns().stream().map(column -> column.getName().toLowerCase(ENGLISH)).collect(toImmutableSet());
        ImmutableList.Builder<String> unmapped = ImmutableList.builder();
        try (PreparedStatement query = connection.prepareStatement("SELECT name FROM pragma_table_info(?) WHERE \"notnull\" AND dflt_value IS NULL AND pk = 0")) {
            query.setString(1, table.getName());
            try (ResultSet rows = query.executeQuery()) {
                while (rows.next()) {
                    String column = rows.getString(1);
                    if (!mapped.contains(column.toLowerCase(ENGLISH))) {
                        unmapped.add(column);
                    }
                }
            }
        }
        for (String column : unmapped.build()) {
            try (Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE \"" + table.getName() + "\" DROP COLUMN \"" + column + "\"");
                log.info("Dropped unmapped NOT NULL column {}.{}", table.getName(), column);
            }
            catch (SQLException e) {
                log.warn("Could not drop unmapped NOT NULL column {}.{}", table.getName(), column, e);
            }
        }
    }

    private static void matchValueChecks(Connection connection, String table, String createSql)
            throws SQLException
    {
        String stored;
        try (PreparedStatement query = connection.prepareStatement("SELECT sql FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            query.setString(1, table);
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) {
                    return;
                }
                stored = rows.getString(1);
            }
        }
        Map<String, String> wanted = VALUE_CHECK.matcher(createSql).results().collect(toImmutableMap(check -> check.group(1).toLowerCase(ENGLISH), MatchResult::group));
        String matched = VALUE_CHECK.matcher(stored).replaceAll(check -> {
            String current = wanted.getOrDefault(check.group(1).toLowerCase(ENGLISH), "");
            return Matcher.quoteReplacement(check.group().equalsIgnoreCase(current) ? check.group() : current);
        });
        if (matched.equals(stored)) {
            return;
        }
        try {
            replaceTableSql(connection, table, matched);
            log.info("Matched the value CHECK constraints of {} to the current model", table);
        }
        catch (SQLException e) {
            log.warn("Could not match the value CHECK constraints of {} to the current model", table, e);
        }
    }

    // SQLite cannot alter a CHECK. Its documented way to change one without rebuilding the table rewrites the
    // stored CREATE TABLE and bumps the schema version so every open connection reloads it.
    private static void replaceTableSql(Connection connection, String table, String sql)
            throws SQLException
    {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try (Statement statement = connection.createStatement()) {
            int version;
            try (ResultSet rows = statement.executeQuery("PRAGMA schema_version")) {
                rows.next();
                version = rows.getInt(1);
            }
            statement.execute("PRAGMA writable_schema = ON");
            try (PreparedStatement update = connection.prepareStatement("UPDATE sqlite_master SET sql = ? WHERE type = 'table' AND name = ?")) {
                update.setString(1, sql);
                update.setString(2, table);
                update.executeUpdate();
                statement.execute("PRAGMA schema_version = " + (version + 1));
            }
            finally {
                statement.execute("PRAGMA writable_schema = OFF");
            }
            connection.commit();
        }
        catch (SQLException e) {
            connection.rollback();
            throw e;
        }
        finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    @Override
    public void disintegrate(SessionFactoryImplementor sessionFactory, SessionFactoryServiceRegistry serviceRegistry) {}
}

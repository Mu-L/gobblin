/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.gobblin.metastore.testing;

import java.io.Closeable;
import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;


import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.MySQLContainer;


import com.google.common.base.Optional;
import com.google.common.collect.ImmutableMap;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import com.zaxxer.hikari.HikariDataSource;

import org.apache.gobblin.configuration.ConfigurationKeys;
import org.apache.gobblin.metastore.util.DatabaseJobHistoryStoreSchemaManager;
import org.apache.gobblin.metastore.util.MySqlJdbcUrl;


class TestMetastoreDatabaseServer implements Closeable {

  private static final String INFORMATION_SCHEMA = "information_schema";
  private static final String ROOT_USER = "root";
  private static final String DROP_DATABASE_TEMPLATE = "DROP DATABASE IF EXISTS %s;";
  private static final String CREATE_DATABASE_TEMPLATE = "CREATE DATABASE %s CHARACTER SET = %s COLLATE = %s;";
  private static final String ADD_USER_TEMPLATE = "GRANT ALL ON %s.* TO '%s'@'%%';";

  public static final String CONFIG_PREFIX = "gobblin.metastore.testing";
  public static final String EMBEDDED_MYSQL_ENABLED_KEY = "embeddedMysqlEnabled";
  public static final String EMBEDDED_MYSQL_ENABLED_FULL_KEY = CONFIG_PREFIX + "." + EMBEDDED_MYSQL_ENABLED_KEY;
  public static final String DBUSER_NAME_KEY = "testUser";
  public static final String DBUSER_NAME_FULL_KEY = CONFIG_PREFIX + "." + DBUSER_NAME_KEY;
  public static final String DBUSER_PASSWORD_KEY = "testPassword";
  public static final String DBUSER_PASSWORD_FULL_KEY = CONFIG_PREFIX + "." + DBUSER_PASSWORD_KEY;
  public static final String DBHOST_KEY = "dbHost";
  public static final String DBHOST_FULL_KEY = CONFIG_PREFIX + "." + DBHOST_KEY;
  public static final String DBPORT_KEY = "dbPort";
  public static final String DBPORT_FULL_KEY = CONFIG_PREFIX + "." + DBPORT_KEY;
  public static final String MYSQL_VERSION = "8.0.20";
  public static final String MYSQL_ROOT_PASSWORD_KEY = "MYSQL_ROOT_PASSWORD";
  private static final String CHARSET_UTF_8_MB_4 = "utf8mb4";
  private static final String COLLATE_UTF_8_MB_4_UNICODE_CI = "utf8mb4_unicode_ci";

  private final Logger log = LoggerFactory.getLogger(TestMetastoreDatabaseServer.class);
  private final MySQLContainer mySQLContainer;
  private final boolean embeddedMysqlEnabled;
  private final String dbUserName;
  private final String dbUserPassword;
  private final String dbHost;
  private final int dbPort;

  TestMetastoreDatabaseServer(Config dbConfig) throws Exception {
    Config realConfig = dbConfig.withFallback(getDefaultConfig()).getConfig(CONFIG_PREFIX);
    this.embeddedMysqlEnabled = realConfig.getBoolean(EMBEDDED_MYSQL_ENABLED_KEY);
    this.dbUserName = realConfig.getString(DBUSER_NAME_KEY);
    this.dbUserPassword = realConfig.getString(DBUSER_PASSWORD_KEY);
    if (this.embeddedMysqlEnabled) {
      mySQLContainer = new MySQLContainer<>("mysql:" + MYSQL_VERSION)
          .withUsername(this.dbUserName)
          .withPassword(this.dbUserPassword);
      mySQLContainer.withEnv(MYSQL_ROOT_PASSWORD_KEY, this.dbUserPassword);
      mySQLContainer.start();

      this.dbHost = mySQLContainer.getHost();
      this.dbPort = mySQLContainer.getFirstMappedPort();

    } else {
      mySQLContainer = null;
      this.dbHost =  realConfig.getString(DBHOST_KEY);
      this.dbPort =  realConfig.getInt(DBPORT_KEY);
    }
    this.log.info("Started with config: embeddedMysqlEnabled={} dbUserName={} dbHost={} dbPort={}",
        this.embeddedMysqlEnabled, this.dbUserName, this.dbHost, this.dbPort);
  }

  static Config getDefaultConfig() {
    return ConfigFactory.parseMap(ImmutableMap.<String, Object>builder()
           .put(EMBEDDED_MYSQL_ENABLED_FULL_KEY, true)
           .put(DBUSER_NAME_FULL_KEY, "testUser")
           .put(DBUSER_PASSWORD_FULL_KEY, "testPassword")
           .put(DBHOST_FULL_KEY, "localhost")
           .put(DBPORT_FULL_KEY, 3306)
           .build());
  }

  public void drop(String database) throws SQLException, URISyntaxException {
    Optional<Connection> connectionOptional = Optional.absent();
    try {
      connectionOptional = getConnector(getInformationSchemaJdbcUrl());
      Connection connection = connectionOptional.get();
      executeStatement(connection, String.format(DROP_DATABASE_TEMPLATE, database));
    } finally {
      if (connectionOptional.isPresent()) {
        connectionOptional.get().close();
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (mySQLContainer != null) {
      mySQLContainer.stop();
    }
  }

  MySqlJdbcUrl getJdbcUrl(String database) throws URISyntaxException {
    return getBaseJdbcUrl()
        .setPath(database)
        .setUser(this.dbUserName)
        .setPassword(this.dbUserPassword)
        .setParameter("useLegacyDatetimeCode", "false")
        .setParameter("rewriteBatchedStatements", "true");
  }

  private MySqlJdbcUrl getBaseJdbcUrl() throws URISyntaxException {
    return MySqlJdbcUrl.create()
        .setHost(this.dbHost)
        .setPort(this.dbPort);
  }

  private MySqlJdbcUrl getInformationSchemaJdbcUrl() throws URISyntaxException {
    // embedded mysql has an empty password by default
    String password =  this.embeddedMysqlEnabled ? "" : this.dbUserPassword;
    return getBaseJdbcUrl()
        .setPath(INFORMATION_SCHEMA)
        .setUser(ROOT_USER)
        .setPassword(password);
  }

  private Optional<Connection> getConnector(MySqlJdbcUrl jdbcUrl) throws SQLException {
    Properties properties = new Properties();
    properties.setProperty(ConfigurationKeys.JOB_HISTORY_STORE_URL_KEY, jdbcUrl.toString());
    HikariDataSource dataSource = new HikariDataSource();
    dataSource.setJdbcUrl(jdbcUrl.toString());
    dataSource.setPassword(this.dbUserPassword);
    return Optional.of(dataSource.getConnection());
  }

  private void ensureDatabaseExists(String database) throws SQLException, URISyntaxException {
    Optional<Connection> connectionOptional = Optional.absent();
    try {
      connectionOptional = getConnector(getInformationSchemaJdbcUrl());
      Connection connection = connectionOptional.get();
      executeStatements(connection,
          String.format(DROP_DATABASE_TEMPLATE, database),
          String.format(CREATE_DATABASE_TEMPLATE, database,
                 this.CHARSET_UTF_8_MB_4, this.COLLATE_UTF_8_MB_4_UNICODE_CI),
          String.format(ADD_USER_TEMPLATE, database, this.dbUserName));
    } finally {
      if (connectionOptional.isPresent()) {
        connectionOptional.get().close();
      }
    }
  }

  void prepareDatabase(String database, String version) throws Exception {
    // Drop/create the database
    this.ensureDatabaseExists(database);

    // Deploy the schema
    DatabaseJobHistoryStoreSchemaManager schemaManager =
        DatabaseJobHistoryStoreSchemaManager.builder()
            .setDataSource(getJdbcUrl(database).toString(), this.dbUserName, this.dbUserPassword)
            .setVersion(version)
            .build();
    schemaManager.migrate();
  }

  private void executeStatements(Connection connection, String... statements) throws SQLException {
    for (String statement : statements) {
      executeStatement(connection, statement);
    }
  }

  private void executeStatement(Connection connection, String statement) throws SQLException {
    try (PreparedStatement preparedStatement = connection.prepareStatement(statement)) {
      preparedStatement.execute();
    }
  }
}

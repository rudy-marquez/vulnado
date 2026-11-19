package com.scalesec.vulnado;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.DriverPropertyInfo;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * Verifies that User.fetch binds the username as a parameter of a
 * PreparedStatement instead of concatenating it into the SQL text.
 *
 * Postgres.connection() is static, so a fake JDBC driver that accepts the
 * "jdbc:postgresql:" URL is registered to hand back a mocked Connection.
 */
public class UserTest {

  private Connection connection;
  private PreparedStatement preparedStatement;
  private ResultSet resultSet;
  private Driver fakeDriver;
  private final List<Driver> removedDrivers = new ArrayList<>();

  @Before
  public void setUp() throws SQLException {
    connection = mock(Connection.class);
    preparedStatement = mock(PreparedStatement.class);
    resultSet = mock(ResultSet.class);
    when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
    when(preparedStatement.executeQuery()).thenReturn(resultSet);

    // Temporarily replace the real PostgreSQL driver with a fake one
    for (Driver d : Collections.list(DriverManager.getDrivers())) {
      if (d.getClass().getName().equals("org.postgresql.Driver")) {
        removedDrivers.add(d);
        DriverManager.deregisterDriver(d);
      }
    }
    fakeDriver = new FakeDriver(connection);
    DriverManager.registerDriver(fakeDriver);
  }

  @After
  public void tearDown() throws SQLException {
    DriverManager.deregisterDriver(fakeDriver);
    for (Driver d : removedDrivers) {
      DriverManager.registerDriver(d);
    }
  }

  @Test
  public void fetchBindsUsernameAsParameter() throws SQLException {
    when(resultSet.next()).thenReturn(true);
    when(resultSet.getString("user_id")).thenReturn("id-1");
    when(resultSet.getString("username")).thenReturn("alice");
    when(resultSet.getString("password")).thenReturn("hash");

    User user = User.fetch("alice");

    assertNotNull(user);
    assertEquals("alice", user.username);
    verify(connection).prepareStatement("select * from users where username = ? limit 1");
    verify(preparedStatement).setString(1, "alice");
    verify(connection, never()).createStatement();
  }

  @Test
  public void fetchDoesNotEmbedMaliciousInputInQuery() throws SQLException {
    String payload = "' OR '1'='1' --";
    when(resultSet.next()).thenReturn(false);

    User user = User.fetch(payload);

    assertNull(user);
    // The SQL text must be constant and must not contain the payload
    org.mockito.ArgumentCaptor<String> sql = org.mockito.ArgumentCaptor.forClass(String.class);
    verify(connection).prepareStatement(sql.capture());
    assertFalse(sql.getValue().contains(payload));
    assertEquals("select * from users where username = ? limit 1", sql.getValue());
    // The payload is passed purely as data
    verify(preparedStatement).setString(1, payload);
    verify(connection, never()).createStatement();
  }

  /** Minimal JDBC driver returning a pre-built connection for PostgreSQL URLs. */
  private static class FakeDriver implements Driver {
    private final Connection connection;

    FakeDriver(Connection connection) {
      this.connection = connection;
    }

    @Override
    public Connection connect(String url, Properties info) {
      return acceptsURL(url) ? connection : null;
    }

    @Override
    public boolean acceptsURL(String url) {
      return url != null && url.startsWith("jdbc:postgresql:");
    }

    @Override
    public DriverPropertyInfo[] getPropertyInfo(String url, Properties info) {
      return new DriverPropertyInfo[0];
    }

    @Override
    public int getMajorVersion() {
      return 1;
    }

    @Override
    public int getMinorVersion() {
      return 0;
    }

    @Override
    public boolean jdbcCompliant() {
      return false;
    }

    @Override
    public Logger getParentLogger() {
      return Logger.getGlobal();
    }
  }
}

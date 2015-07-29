package com.adobe.examples.db.resourceprovider.impl;

import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class DBResourceDataFactory implements ResourceDataFactory {

    private static final Logger LOG = LoggerFactory.getLogger(DBResourceDataFactory.class);

    private final Connection connection;

    private final String rootPath;

    private boolean closed;

    //TODO: temporary in-memory persistence - DB is ignored for now
    private final Map<String, ResourceData> persistedData = new ConcurrentHashMap<String, ResourceData>();

    public DBResourceDataFactory(final Connection connection, final String rootPath) throws SQLException {
        this.connection = connection;
        this.rootPath = ensureTrailingSlash(rootPath);
        this.closed = false;

        initDatabase(this.connection);
        initPersistence(this.persistedData, this.rootPath);
    }

    private static void initPersistence(final Map<String, ResourceData> persistedData, final String rootPath) {
        final HashMap<String, Object> accountsProperties = new HashMap<String, Object>();
        accountsProperties.put("tableName", "accounts");
        persistedData.put("accounts", new DBTableResourceData(rootPath + "accounts", accountsProperties));
    }

    private static void initDatabase(final Connection connection) throws SQLException {
        final String sql = "CREATE TABLE IF NOT EXISTS ACCOUNTS(USERID VARCHAR(63) PRIMARY KEY, NAME VARCHAR(255), EMAIL VARCHAR2(255), BALANCE INT);";
        final PreparedStatement preparedStatement = connection.prepareStatement(sql);
        preparedStatement.execute();
    }

    public ResourceData getResourceData(final String path) {
        final String relPath = relativizePath(rootPath, path);
        final String[] parts = relPath.split("/");
        LOG.info("getResourceData for relPath {}", relPath);
        switch (parts.length) {
            case 1:
                final String tableName = parts[0].toUpperCase(Locale.ENGLISH);
                if (!"ACCOUNTS".equals(tableName)) {
                    return null;
                }
                final Map<String, Object> properties = new HashMap<String, Object>();
                properties.put("tableName", tableName);
                return new DBTableResourceData(path, properties);
            case 2:
                final String userid = parts[1];
                final PreparedStatement preparedStatement;
                try {
                    preparedStatement = connection.prepareStatement("SELECT * FROM ACCOUNTS WHERE USERID = ?");
                    preparedStatement.setString(1, userid);
                    preparedStatement.execute();
                    final Map<String, Object> props = resultToProperties(preparedStatement.getResultSet());
                    return new DBRecordResourceData(path, props);
                } catch (SQLException e) {
                    LOG.error("Failed to talk to DB", e);
                }
        }
        return null;
    }

    private Map<String, Object> resultToProperties(final ResultSet resultSet) throws SQLException {
        if (resultSet.first()) {
            final HashMap<String, Object> props = new HashMap<String, Object>();
            props.put("userid", resultSet.getString("USERID"));
            props.put("name", resultSet.getString("NAME"));
            props.put("email", resultSet.getString("EMAIL"));
            props.put("balance", resultSet.getInt("BALANCE"));
            return props;
        }
        return Collections.emptyMap();
    }

    public void putResourceData(final String path, final ResourceData resourceData) {
        final String relPath = relativizePath(rootPath, path);
        final String[] parts = relPath.split("/");
        if (parts.length != 2) {
            LOG.warn("Tried to add/update record on wrong level [{}]", relPath);
            return;
        }
        final String tableName = parts[0].toUpperCase(Locale.ENGLISH);
        final String userid = parts[1];

        if (!"ACCOUNTS".equals(tableName)) {
            LOG.warn("Tried to add/update record in unsupported table [{}]", tableName);
            return;
        }

        if (resourceData == null) {
            try {
                final PreparedStatement preparedStatement = connection.prepareStatement("DELETE FROM ACCOUNTS WHERE USERID = ?");
                preparedStatement.setString(1, userid);
                preparedStatement.execute();
                LOG.info("Deleted record with userid = {}", userid);
            } catch (SQLException e) {
                LOG.error("Failed to talk to DB", e);
            }
            //persistedData.remove(relPath);
        } else {
            try {
                // TODO: handle and differentiate UPDATE
                // connection.prepareStatement("UPDATE ACCOUNTS SET USERID=?, NAME=?, EMAIL=?, BALANCE=? WHERE USERID = ?");
                final PreparedStatement preparedStatement = connection.prepareStatement("INSERT INTO ACCOUNTS VALUES(?, ?, ?, ?)");
                final ValueMap properties = resourceData.getValueMap();
                preparedStatement.setString(1, userid);
                preparedStatement.setString(2, properties.get("name", "[no name]"));
                preparedStatement.setString(3, properties.get("email", "[no email]"));
                preparedStatement.setInt(4, properties.get("balance", 0));
                preparedStatement.execute();
                LOG.info("Added/updated record with userid = {}", userid);
            } catch (SQLException e) {
                LOG.error("Failed to talk to DB", e);
            }
            // persistedData.put(relPath, resourceData);
        }
    }

    public Iterable<String> getChildPaths(final String path) {
        final String relPath = relativizePath(rootPath, path);

        if (relPath.contains("/")) { // records don't have children
            return Collections.emptySet();
        }

        final String tableName = relPath.toUpperCase(Locale.ENGLISH);
        if (!"ACCOUNTS".equals(tableName)) {
            return Collections.emptySet();
        }

        try {
            final CallableStatement callableStatement = connection.prepareCall("SELECT USERID FROM ACCOUNTS");
            callableStatement.execute();
            final ResultSet resultSet = callableStatement.getResultSet();
            final List<String> children = new ArrayList<String>();
            while (resultSet.next()) {
                children.add(path + "/" + resultSet.getString("USERID"));
            }
            return children;
        } catch (SQLException e) {
            LOG.error("Failed to talk to DB", e);
        }
        return Collections.emptySet();
    }

    public boolean isLive() {
        return closed;
    }

    public void close() {
        closed = true;
    }

    private static String relativizePath(final String rootPath, final String path) {
        if (path.startsWith(rootPath)) {
            return path.replace(rootPath, "");
        }
        return path;
    }

    private static String ensureTrailingSlash(final String path) {
        if (path.endsWith("/")) {
            return path;
        }
        return path + "/";
    }
}

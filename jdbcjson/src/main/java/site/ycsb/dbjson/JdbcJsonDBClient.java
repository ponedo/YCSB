/**
 * Copyright (c) 2010 - 2016 Yahoo! Inc., 2016, 2019 YCSB contributors. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */
package site.ycsb.dbjson;

import site.ycsb.DB;
import site.ycsb.DBException;
import site.ycsb.ByteIterator;
import site.ycsb.Status;
import site.ycsb.StringByteIterator;
import org.json.simple.JSONObject;
import org.json.simple.JSONValue;


import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * A class that wraps a JDBC compliant database to allow it to be interfaced
 * with YCSB. This class extends {@link DB} and implements the database
 * interface used by YCSB client.
 *
 * <br>
 * Each client will have its own instance of this class. This client is not
 * thread safe.
 *
 * <br>
 * Changed by Kaifei Peng.
 * This interface expects a schema <key> <value>, in which <value> is a json object. 
 * Each <value> is composed of <field1>, <field2>, ... . All accesses are 
 * through the primary key. Therefore, only one index on the primary key is needed.
 */
public class JdbcJsonDBClient extends DB {

  /** The class to use as the jdbc driver. */
  public static final String DRIVER_CLASS = "db.driver";

  /** The URL to connect to the database. */
  public static final String CONNECTION_URL = "db.url";

  /** The user name to use to connect to the database. */
  public static final String CONNECTION_USER = "db.user";

  /** The password to use for establishing the connection. */
  public static final String CONNECTION_PASSWD = "db.passwd";

  /** The batch size for batched inserts. Set to >0 to use batching */
  public static final String DB_BATCH_SIZE = "db.batchsize";

  /** The JDBC fetch size hinted to the driver. */
  public static final String JDBC_FETCH_SIZE = "jdbc.fetchsize";

  /** The JDBC connection auto-commit property for the driver. */
  public static final String JDBC_AUTO_COMMIT = "jdbc.autocommit";

  public static final String JDBC_BATCH_UPDATES = "jdbc.batchupdateapi";

  /** The name of the property for the number of fields in a record. */
  public static final String FIELD_COUNT_PROPERTY = "fieldcount";

  /** Default number of fields in a record. */
  public static final String FIELD_COUNT_PROPERTY_DEFAULT = "10";

  /** Representing a NULL value. */
  public static final String NULL_VALUE = "NULL";

  /** The primary key in the user table. */
  public static final String PRIMARY_KEY = "YCSB_KEY";

  /** The field name prefix in the table. */
  public static final String COLUMN_NAME = "YCSB_VALUE";

  /** The field name prefix in the table. */
  public static final String COLUMN_PREFIX = "FIELD";

  private boolean sqlserver = false;
  private List<Connection> conns;
  private boolean initialized = false;
  private Properties props;
  private int jdbcFetchSize;
  private int batchSize;
  private boolean autoCommit;
  private boolean batchUpdates;
  private static final String DEFAULT_PROP = "";
  private ConcurrentMap<StatementType, PreparedStatement> cachedStatements;
  private long numRowsInBatch = 0;
  /** DB flavor defines DB-specific syntax and behavior for the
   * particular database. Current database flavors are: {default, phoenix} */

  /**
   * Ordered field information for insert and update statements.
   */
  private static class OrderedFieldInfo {
    private String fieldKeys;
    private List<String> fieldValues;

    OrderedFieldInfo(String fieldKeys, List<String> fieldValues) {
      this.fieldKeys = fieldKeys;
      this.fieldValues = fieldValues;
    }

    String getFieldKeys() {
      return fieldKeys;
    }

    List<String> getFieldValues() {
      return fieldValues;
    }
  }

  /**
   * For the given key, returns what shard contains data for this key.
   *
   * @param key Data key to do operation on
   * @return Shard index
   */
  private int getShardIndexByKey(String key) {
    int ret = Math.abs(key.hashCode()) % conns.size();
    return ret;
  }

  /**
   * For the given key, returns Connection object that holds connection to the
   * shard that contains this key.
   *
   * @param key Data key to get information for
   * @return Connection object
   */
  private Connection getShardConnectionByKey(String key) {
    return conns.get(getShardIndexByKey(key));
  }

  private void cleanupAllConnections() throws SQLException {
    for (Connection conn : conns) {
      if (!autoCommit) {
        conn.commit();
      }
      conn.close();
    }
  }

  /** Returns parsed int value from the properties if set, otherwise returns -1. */
  private static int getIntProperty(Properties props, String key) throws DBException {
    String valueStr = props.getProperty(key);
    if (valueStr != null) {
      try {
        return Integer.parseInt(valueStr);
      } catch (NumberFormatException nfe) {
        System.err.println("Invalid " + key + " specified: " + valueStr);
        throw new DBException(nfe);
      }
    }
    return -1;
  }

  /** Returns parsed boolean value from the properties if set, otherwise returns defaultVal. */
  private static boolean getBoolProperty(Properties props, String key, boolean defaultVal) {
    String valueStr = props.getProperty(key);
    if (valueStr != null) {
      return Boolean.parseBoolean(valueStr);
    }
    return defaultVal;
  }

  @Override
  public void init() throws DBException {
    if (initialized) {
      System.err.println("Client connection already initialized.");
      return;
    }
    props = getProperties();
    String urls = props.getProperty(CONNECTION_URL, DEFAULT_PROP);
    String user = props.getProperty(CONNECTION_USER, DEFAULT_PROP);
    String passwd = props.getProperty(CONNECTION_PASSWD, DEFAULT_PROP);
    String driver = props.getProperty(DRIVER_CLASS);

    if (driver.contains("sqlserver")) {
      sqlserver = true;
    }

    this.jdbcFetchSize = getIntProperty(props, JDBC_FETCH_SIZE);
    this.batchSize = getIntProperty(props, DB_BATCH_SIZE);

    this.autoCommit = getBoolProperty(props, JDBC_AUTO_COMMIT, true);
    this.batchUpdates = getBoolProperty(props, JDBC_BATCH_UPDATES, false);

    try {
      if (driver != null) {
        Class.forName(driver);
      }
      int shardCount = 0;
      conns = new ArrayList<Connection>(3);
      // for a longer explanation see the README.md
      // semicolons aren't present in JDBC urls, so we use them to delimit
      // multiple JDBC connections to shard across.
      final String[] urlArr = urls.split(";");
      for (String url : urlArr) {
        System.out.println("Adding shard node URL: " + url);
        Connection conn = DriverManager.getConnection(url, user, passwd);

        // Since there is no explicit commit method in the DB interface, all
        // operations should auto commit, except when explicitly told not to
        // (this is necessary in cases such as for PostgreSQL when running a
        // scan workload with fetchSize)
        conn.setAutoCommit(autoCommit);

        shardCount++;
        conns.add(conn);
      }

      System.out.println("Using shards: " + shardCount + ", batchSize:" + batchSize + ", fetchSize: " + jdbcFetchSize);

      cachedStatements = new ConcurrentHashMap<StatementType, PreparedStatement>();

    } catch (ClassNotFoundException e) {
      System.err.println("Error in initializing the JDBS driver: " + e);
      throw new DBException(e);
    } catch (SQLException e) {
      System.err.println("Error in database operation: " + e);
      throw new DBException(e);
    } catch (NumberFormatException e) {
      System.err.println("Invalid value for fieldcount property. " + e);
      throw new DBException(e);
    }

    initialized = true;
  }

  @Override
  public void cleanup() throws DBException {
    if (batchSize > 0) {
      try {
        // commit un-finished batches
        for (PreparedStatement st : cachedStatements.values()) {
          if (!st.getConnection().isClosed() && !st.isClosed() && (numRowsInBatch % batchSize != 0)) {
            st.executeBatch();
          }
        }
      } catch (SQLException e) {
        System.err.println("Error in cleanup execution. " + e);
        throw new DBException(e);
      }
    }

    try {
      cleanupAllConnections();
    } catch (SQLException e) {
      System.err.println("Error in closing the connection. " + e);
      throw new DBException(e);
    }
  }

  @Override
  public Status read(String tableName, String key, Set<String> fields, Map<String, ByteIterator> result) {
    try {
      List<String> fieldsList;
      if (fields == null) {
        fieldsList = new ArrayList<>();
      } else {
        fieldsList = new ArrayList<>(fields);
      }
      StatementType type = new StatementType(StatementType.Type.READ, tableName, 
          1, fieldsList, getShardIndexByKey(key));
      PreparedStatement readStatement = cachedStatements.get(type);
      if (readStatement == null) {
        readStatement = createAndCacheReadStatement(type, key);
      }
      readStatement.setString(1, key);
      ResultSet resultSet = readStatement.executeQuery();
      if (!resultSet.next()) {
        resultSet.close();
        return Status.NOT_FOUND;
      }
      // change starts
      if (result != null) {
        if (fields == null) {
          String jsonString = resultSet.getString(2);
          JSONObject jsonObject = (JSONObject)JSONValue.parse(jsonString);
          for (Iterator iterator = jsonObject.entrySet().iterator(); iterator.hasNext();) {
            Map.Entry entry = (Map.Entry)iterator.next();
            String field = (String)entry.getKey();
            String value = (String)entry.getValue();
            result.put(field, new StringByteIterator(value));
          }
        } else {
          for (String field : fields) {
            String value = resultSet.getString(field);
            result.put(field, new StringByteIterator(value));
          }
        }
      }
      // change ends
      resultSet.close();
      return Status.OK;
    } catch (SQLException e) {
      System.err.println("Error in processing read of table " + tableName + ": " + e);
      return Status.ERROR;
    }
  }

  private PreparedStatement createAndCacheReadStatement(StatementType readType, String key)
      throws SQLException {
    String read = createReadStatement(readType);
    PreparedStatement readStatement = getShardConnectionByKey(key).prepareStatement(read);
    PreparedStatement stmt = cachedStatements.putIfAbsent(readType, readStatement);
    if (stmt == null) {
      return readStatement;
    }
    return stmt;
  }

  private String createReadStatement(StatementType readType){
    StringBuilder read = new StringBuilder("SELECT " + PRIMARY_KEY + " AS " + PRIMARY_KEY);
    // change starts
    if (readType.getFields().isEmpty()) {
      read.append(", " + COLUMN_NAME + "");
    } else {
      for (String field:readType.getFields()){
        read.append(", " + COLUMN_NAME + "->>'$." + field + "' AS " + field);
      }
    }
    // change ends
    read.append(" FROM " + readType.getTableName());
    read.append(" WHERE ");
    read.append(PRIMARY_KEY);
    read.append(" = ");
    read.append("?");
    return read.toString();
  }

  @Override
  public Status scan(String tableName, String startKey, int recordcount, Set<String> fields,
                     Vector<HashMap<String, ByteIterator>> result) {
    try {
      List<String> fieldsList;
      if (fields == null) {
        fieldsList = new ArrayList<>();
      } else {
        fieldsList = new ArrayList<>(fields);
      }
      StatementType type = new StatementType(StatementType.Type.SCAN, tableName, 1, 
          fieldsList, getShardIndexByKey(startKey));
      PreparedStatement scanStatement = cachedStatements.get(type);
      if (scanStatement == null) {
        scanStatement = createAndCacheScanStatement(type, startKey);
      }
      if (sqlserver) {
        scanStatement.setInt(1, recordcount);
        scanStatement.setString(2, startKey);
      } else {
        scanStatement.setString(1, startKey);
        scanStatement.setInt(2, recordcount);
      }
      ResultSet resultSet = scanStatement.executeQuery();
      for (int i = 0; i < recordcount && resultSet.next(); i++) {
        // change starts
        if (result != null) {
          HashMap<String, ByteIterator> values = new HashMap<String, ByteIterator>();
          if (fields == null) {
            String jsonString = resultSet.getString(2);
            JSONObject jsonObject = (JSONObject)JSONValue.parse(jsonString);
            for (Iterator iterator = jsonObject.entrySet().iterator(); iterator.hasNext();) {
              Map.Entry entry = (Map.Entry)iterator.next();
              String field = (String)entry.getKey();
              String value = (String)entry.getValue();
              values.put(field, new StringByteIterator(value));
            }
          } else {
            for (String field : fields) {
              String value = resultSet.getString(field);
              values.put(field, new StringByteIterator(value));
            }
          }
          result.add(values);
        }
        // change ends
      }
      resultSet.close();
      return Status.OK;
    } catch (SQLException e) {
      System.err.println("Error in processing scan of table: " + tableName + e);
      return Status.ERROR;
    }
  }

  private PreparedStatement createAndCacheScanStatement(StatementType scanType, String key)
      throws SQLException {
    String select = createScanStatement(scanType, key);
    PreparedStatement scanStatement = getShardConnectionByKey(key).prepareStatement(select);
    if (this.jdbcFetchSize > 0) {
      scanStatement.setFetchSize(this.jdbcFetchSize);
    }
    PreparedStatement stmt = cachedStatements.putIfAbsent(scanType, scanStatement);
    if (stmt == null) {
      return scanStatement;
    }
    return stmt;
  }

  private String createScanStatement(StatementType scanType, String key) {
    // StringBuilder scan = new StringBuilder("SELECT " + PRIMARY_KEY + " AS " + PRIMARY_KEY);
    StringBuilder scan;
    if (sqlserver) {
      scan = new StringBuilder("SELECT TOP (?) " + PRIMARY_KEY + " AS " + PRIMARY_KEY);
    } else {
      scan = new StringBuilder("SELECT " + PRIMARY_KEY + " AS " + PRIMARY_KEY);
    }
    if (scanType.getFields().isEmpty()) {
      scan.append(", " + COLUMN_NAME + "");
    } else {
      for (String field : scanType.getFields()){
        scan.append(", " + COLUMN_NAME + "->>'$." + field + "' AS " + field);
      }
    }
    scan.append(" FROM " + scanType.getTableName());
    scan.append(" WHERE ");
    scan.append(PRIMARY_KEY);
    scan.append(" >= ?");
    scan.append(" ORDER BY ");
    scan.append(PRIMARY_KEY);
    if (!sqlserver) {
      scan.append(" LIMIT ?");
    }

    return scan.toString();
  }

  @Override
  public Status update(String tableName, String key, Map<String, ByteIterator> values) {
    try {
      int numFields = values.size();
      OrderedFieldInfo fieldInfo = getFieldInfo(values);
      // change starts
      String[] arr = fieldInfo.getFieldKeys().split(",");
      List<String> fields = Arrays.asList(arr);
      StatementType type = new StatementType(StatementType.Type.UPDATE, tableName,
          numFields, fields, getShardIndexByKey(key));
      // change ends
      PreparedStatement updateStatement = cachedStatements.get(type);
      if (updateStatement == null) {
        updateStatement = createAndCacheUpdateStatement(type, key);
      }
      int index = 1;
      for (String value: fieldInfo.getFieldValues()) {
        updateStatement.setString(index++, value);
      }
      updateStatement.setString(index, key);
      int result = updateStatement.executeUpdate();
      if (result == 1) {
        return Status.OK;
      }
      return Status.UNEXPECTED_STATE;
    } catch (SQLException e) {
      System.err.println("Error in processing update to table: " + tableName + e);
      return Status.ERROR;
    }
  }

  private PreparedStatement createAndCacheUpdateStatement(StatementType updateType, String key)
      throws SQLException {
    String update = createUpdateStatement(updateType);
    PreparedStatement insertStatement = getShardConnectionByKey(key).prepareStatement(update);
    PreparedStatement stmt = cachedStatements.putIfAbsent(updateType, insertStatement);
    if (stmt == null) {
      return insertStatement;
    }
    return stmt;
  }

  private String createUpdateStatement(StatementType updateType){
    StringBuilder update = new StringBuilder("UPDATE ");
    update.append(updateType.getTableName());
    update.append(" SET ");
    // change starts
    update.append(COLUMN_NAME + " = JSON_SET(" + COLUMN_NAME);
    if (updateType.getFields() != null) {
      for (String field : updateType.getFields()){
        update.append(", '$." + field + "', ?");
      }
    }
    // change ends
    update.append(") WHERE ");
    update.append(PRIMARY_KEY);
    update.append(" = ?");
    return update.toString();
  }
  
  @Override
  public Status insert(String tableName, String key, Map<String, ByteIterator> values) {
    JSONObject jsonObject = new JSONObject();
    try {
      int numFields = values.size();
      OrderedFieldInfo fieldInfo = getFieldInfo(values);
      // change starts
      String[] arr = fieldInfo.getFieldKeys().split(",");
      List<String> fields = Arrays.asList(arr);
      StatementType type = new StatementType(StatementType.Type.INSERT, tableName,
          numFields, fields, getShardIndexByKey(key));
      // change ends
      PreparedStatement insertStatement = cachedStatements.get(type);
      if (insertStatement == null) {
        insertStatement = createAndCacheInsertStatement(type, key);
      }

      // change starts
      for (Map.Entry<String, ByteIterator> entry : values.entrySet()) {
        jsonObject.put(entry.getKey(), entry.getValue().toString());
      }
      insertStatement.setString(1, key);
      // insertStatement.setString(2, JSONObject.toJSONString(jsonObject));
      int index = 1;
      for (String value: fieldInfo.getFieldValues()) {
        insertStatement.setString(++index, value);
      }
      // change ends
      
      // Using the batch insert API
      if (batchUpdates) {
        insertStatement.addBatch();
        // Check for a sane batch size
        if (batchSize > 0) {
          // Commit the batch after it grows beyond the configured size
          if (++numRowsInBatch % batchSize == 0) {
            int[] results = insertStatement.executeBatch();
            for (int r : results) {
              // Acceptable values are 1 and SUCCESS_NO_INFO (-2) from reWriteBatchedInserts=true
              if (r != 1 && r != -2) { 
                return Status.ERROR;
              }
            }
            // If autoCommit is off, make sure we commit the batch
            if (!autoCommit) {
              getShardConnectionByKey(key).commit();
            }
            return Status.OK;
          } // else, the default value of -1 or a nonsense. Treat it as an infinitely large batch.
        } // else, we let the batch accumulate
        // Added element to the batch, potentially committing the batch too.
        return Status.BATCHED_OK;
      } else {
        // Normal update
        int result = insertStatement.executeUpdate();
        // If we are not autoCommit, we might have to commit now
        if (!autoCommit) {
          // Let updates be batcher locally
          if (batchSize > 0) {
            if (++numRowsInBatch % batchSize == 0) {
              // Send the batch of updates
              getShardConnectionByKey(key).commit();
            }
            // uhh
            return Status.OK;
          } else {
            // Commit each update
            getShardConnectionByKey(key).commit();
          }
        }
        if (result == 1) {
          return Status.OK;
        }
      }
      return Status.UNEXPECTED_STATE;
    } catch (SQLException e) {
      System.err.println("Error in processing insert to table: " + tableName + e);
      return Status.ERROR;
    }
  }

  private PreparedStatement createAndCacheInsertStatement(StatementType insertType, String key)
      throws SQLException {
    String insert = createInsertStatement(insertType);
    PreparedStatement insertStatement = getShardConnectionByKey(key).prepareStatement(insert);
    PreparedStatement stmt = cachedStatements.putIfAbsent(insertType, insertStatement);
    if (stmt == null) {
      return insertStatement;
    }
    return stmt;
  }

  private String createInsertStatement(StatementType insertType){
    StringBuilder insert = new StringBuilder("INSERT INTO ");
    boolean firstField = true;
    insert.append(insertType.getTableName());
    // change starts
    insert.append(" (" + PRIMARY_KEY + ", " + COLUMN_NAME + ")");
    // change starts
    insert.append(" VALUES(?, JSON_OBJECT(");
    for (String field : insertType.getFields()) {
      if (firstField) {
        insert.append("'" + field + "', ?");
        firstField = false;
      } else {
        insert.append(", '" + field + "', ?");
      }
    }
    insert.append("))");
    return insert.toString();
  }

  @Override
  public Status delete(String tableName, String key) {
    try {
      StatementType type = new StatementType(StatementType.Type.DELETE, tableName, 1, null, getShardIndexByKey(key));
      PreparedStatement deleteStatement = cachedStatements.get(type);
      if (deleteStatement == null) {
        deleteStatement = createAndCacheDeleteStatement(type, key);
      }
      deleteStatement.setString(1, key);
      int result = deleteStatement.executeUpdate();
      if (result == 1) {
        return Status.OK;
      }
      return Status.UNEXPECTED_STATE;
    } catch (SQLException e) {
      System.err.println("Error in processing delete to table: " + tableName + e);
      return Status.ERROR;
    }
  }

  private PreparedStatement createAndCacheDeleteStatement(StatementType deleteType, String key)
      throws SQLException {
    String delete = createDeleteStatement(deleteType);
    PreparedStatement deleteStatement = getShardConnectionByKey(key).prepareStatement(delete);
    PreparedStatement stmt = cachedStatements.putIfAbsent(deleteType, deleteStatement);
    if (stmt == null) {
      return deleteStatement;
    }
    return stmt;
  }

  private String createDeleteStatement(StatementType deleteType){
    StringBuilder delete = new StringBuilder("DELETE FROM ");
    delete.append(deleteType.getTableName());
    delete.append(" WHERE ");
    delete.append(PRIMARY_KEY);
    delete.append(" = ?");
    return delete.toString();
  }

  private OrderedFieldInfo getFieldInfo(Map<String, ByteIterator> values) {
    String fieldKeys = "";
    List<String> fieldValues = new ArrayList<>();
    int count = 0;
    for (Map.Entry<String, ByteIterator> entry : values.entrySet()) {
      fieldKeys += entry.getKey();
      if (count < values.size() - 1) {
        fieldKeys += ",";
      }
      fieldValues.add(count, entry.getValue().toString());
      count++;
    }

    return new OrderedFieldInfo(fieldKeys, fieldValues);
  }

}
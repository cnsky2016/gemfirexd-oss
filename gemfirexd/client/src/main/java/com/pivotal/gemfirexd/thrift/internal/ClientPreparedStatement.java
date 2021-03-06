/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.thrift.internal;

import java.io.InputStream;
import java.io.Reader;
import java.math.BigDecimal;
import java.net.URL;
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Date;
import java.sql.NClob;
import java.sql.ParameterMetaData;
import java.sql.PreparedStatement;
import java.sql.Ref;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.RowId;
import java.sql.SQLException;
import java.sql.SQLXML;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import com.pivotal.gemfirexd.internal.shared.common.reference.SQLState;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.thrift.ColumnDescriptor;
import com.pivotal.gemfirexd.thrift.GFXDException;
import com.pivotal.gemfirexd.thrift.GFXDType;
import com.pivotal.gemfirexd.thrift.OutputParameter;
import com.pivotal.gemfirexd.thrift.PrepareResult;
import com.pivotal.gemfirexd.thrift.Row;
import com.pivotal.gemfirexd.thrift.RowSet;
import com.pivotal.gemfirexd.thrift.StatementResult;
import com.pivotal.gemfirexd.thrift.UpdateResult;
import com.pivotal.gemfirexd.thrift.gfxdConstants;
import com.pivotal.gemfirexd.thrift.common.Converters;
import com.pivotal.gemfirexd.thrift.common.PrepareResultHolder;
import com.pivotal.gemfirexd.thrift.common.ThriftExceptionUtil;

/**
 * An implementation of the JDBC {@link PreparedStatement} interface using the
 * thrift driver.
 * 
 * @author swale
 * @since gfxd 1.1
 */
public class ClientPreparedStatement extends ClientStatement implements
    PreparedStatement, PrepareResultHolder {

  protected final String preparedSQL;
  protected final int numParams;
  protected Row paramsList;
  protected ArrayList<Row> paramsBatch;
  protected List<ColumnDescriptor> parameterMetaData;
  protected List<ColumnDescriptor> resultSetMetaData;
  protected Calendar defaultCalendar;

  protected static final Row EMPTY_ROW = new Row();
  protected static final List<ColumnDescriptor> NULL_METADATA =
      new ArrayList<ColumnDescriptor>(0);

  ClientPreparedStatement(ClientConnection conn, String sql)
      throws SQLException {
    super(conn);
    this.preparedSQL = sql;
    this.numParams = prepare();
  }

  ClientPreparedStatement(ClientConnection conn, String sql, int rsType,
      int rsConcurrency, int rsHoldability) throws SQLException {
    super(conn, rsType, rsConcurrency, rsHoldability);
    this.preparedSQL = sql;
    this.numParams = prepare();
  }

  ClientPreparedStatement(ClientConnection conn, String sql, boolean getAutoInc)
      throws SQLException {
    super(conn);
    this.preparedSQL = sql;
    if (getAutoInc) {
      this.attrs.setRequireAutoIncCols(true);
    }
    this.numParams = prepare();
  }

  ClientPreparedStatement(ClientConnection conn, String sql,
      int[] autoIncColumns) throws SQLException {
    super(conn);
    this.preparedSQL = sql;
    if (autoIncColumns != null && autoIncColumns.length > 0) {
      setAutoIncAttributes(true, autoIncColumns, null);
    }
    this.numParams = prepare();
  }

  ClientPreparedStatement(ClientConnection conn, String sql,
      String[] autoIncColumnNames) throws SQLException {
    super(conn);
    this.preparedSQL = sql;
    if (autoIncColumnNames != null && autoIncColumnNames.length > 0) {
      setAutoIncAttributes(true, null, autoIncColumnNames);
    }
    this.numParams = prepare();
  }

  protected Map<Integer, OutputParameter> getOutputParameters() {
    return Collections.emptyMap();
  }

  protected final int prepare() throws SQLException {
    try {
      PrepareResult pr = this.service.prepareStatement(this.preparedSQL, null,
          getAttributes());
      clearPendingTransactionAttrs();
      return setPrepareResult(pr);
    } catch (GFXDException gfxde) {
      throw ThriftExceptionUtil.newSQLException(gfxde);
    }
  }

  private int setPrepareResult(PrepareResult pr) {
    final List<ColumnDescriptor> pmd = pr.getParameterMetaData();
    int numParams;
    if (pmd != null && (numParams = pmd.size()) > 0) {
      this.paramsList = new Row(pmd);
      this.parameterMetaData = pmd;
    }
    else {
      this.paramsList = EMPTY_ROW;
      this.parameterMetaData = NULL_METADATA;
      numParams = 0;
    }
    final List<ColumnDescriptor> rsmd = pr.getResultSetMetaData();
    if (rsmd != null && rsmd.size() > 0) {
      this.resultSetMetaData = rsmd;
    }
    else {
      this.resultSetMetaData = NULL_METADATA;
    }
    this.statementId = pr.statementId;
    setCurrentSource(gfxdConstants.BULK_CLOSE_STATEMENT, pr.statementId, null);
    this.warnings = pr.getWarnings();
    return numParams;
  }

  @Override
  protected final void setCurrentRowSet(RowSet rs) {
    if (rs != null) {
      final int stmtId = rs.getStatementId();
      if (stmtId != gfxdConstants.INVALID_ID) {
        this.statementId = stmtId;
      }
      this.currentRowSet = rs;
      // source host connection cannot change for prepared statements on
      // execution (only on re-prepare)
    }
    else {
      this.currentRowSet = null;
    }
  }

  protected final void checkValidParameterIndex(int parameterIndex)
      throws SQLException {
    if (parameterIndex >= 1 && parameterIndex <= this.numParams) {
      return;
    }
    else {
      throw ThriftExceptionUtil.newSQLException(
          SQLState.LANG_INVALID_COLUMN_POSITION, null,
          Integer.valueOf(parameterIndex), Integer.valueOf(this.numParams));
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public boolean execute() throws SQLException {
    checkClosed();
    reset();
    try {
      StatementResult sr = this.service.executePrepared(
          // don't throw exception in getLobSource rather return null and
          // service will failover to new node and do re-prepare as required
          getLobSource(false, "executePrepared"), statementId,
          this.paramsList, getOutputParameters(), this);
      this.warnings = sr.getWarnings();
      if (this.attrs.isRequireAutoIncCols()) {
        this.currentGeneratedKeys = sr.getGeneratedKeys();
      }
      initializeProcedureOutParams(sr);
      final RowSet rs = sr.getResultSet();
      if (rs != null) {
        setCurrentRowSet(rs);
        return true;
      }
      else {
        this.currentUpdateCount = sr.getUpdateCount();
        return false;
      }
    } catch (GFXDException gfxde) {
      throw ThriftExceptionUtil.newSQLException(gfxde);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSet executeQuery() throws SQLException {
    checkClosed();
    reset();
    try {
      // don't throw exception in getLobSource rather return null and
      // service will failover to new node and do re-prepare as required
      RowSet rs = this.service.executePreparedQuery(
          getLobSource(false, "executeQuery"), statementId, this.paramsList,
          this);
      setCurrentRowSet(rs);
      this.warnings = rs.getWarnings();
      return new ClientResultSet(this.conn, this, rs);
    } catch (GFXDException gfxde) {
      throw ThriftExceptionUtil.newSQLException(gfxde);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int executeUpdate() throws SQLException {
    checkClosed();
    reset();
    try {
      // don't throw exception in getLobSource rather return null and
      // service will failover to new node and do re-prepare as required
      UpdateResult ur = this.service.executePreparedUpdate(
          getLobSource(false, "executeUpdate"), statementId, this.paramsList,
          this);
      if (this.attrs.isRequireAutoIncCols()) {
        this.currentGeneratedKeys = ur.getGeneratedKeys();
      }
      this.warnings = ur.getWarnings();
      return (this.currentUpdateCount = ur.getUpdateCount());
    } catch (GFXDException gfxde) {
      throw ThriftExceptionUtil.newSQLException(gfxde);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int[] executeBatch() throws SQLException {
    checkClosed();
    reset();
    final ArrayList<Row> batch = this.paramsBatch;
    if (batch != null && batch.size() > 0) {
      try {
        // don't throw exception in getLobSource rather return null and
        // service will failover to new node and do re-prepare as required
        UpdateResult ur = this.service.executePreparedBatch(
            getLobSource(false, "executeBatch"), statementId, batch, this);
        this.warnings = ur.getWarnings();
        if (this.attrs.isRequireAutoIncCols()) {
          this.currentGeneratedKeys = ur.getGeneratedKeys();
        }
        List<Integer> updateCounts = ur.getBatchUpdateCounts();
        if (updateCounts != null) {
          int[] result = new int[updateCounts.size()];
          for (int i = 0; i < result.length; i++) {
            result[i] = updateCounts.get(i).intValue();
          }
          return result;
        }
      } catch (GFXDException gfxde) {
        throw ThriftExceptionUtil.newSQLException(gfxde);
      }
    }
    return new int[0];
  }

  protected final GFXDType getType(int parameterIndex) {
    return this.parameterMetaData.get(parameterIndex - 1).type;
  }

  // throw exceptions for unprepared operations
  @Override
  public final boolean execute(String sql) throws SQLException {
    throw ThriftExceptionUtil.newSQLException(
        SQLState.NOT_FOR_PREPARED_STATEMENT, null, "execute(String)");
  }
  @Override
  public final boolean execute(String sql, int autoGeneratedKeys)
      throws SQLException {
    throw ThriftExceptionUtil.newSQLException(
        SQLState.NOT_FOR_PREPARED_STATEMENT, null, "execute(String, int)");
  }
  @Override
  public final boolean execute(String sql, int[] columnIndexes)
      throws SQLException {
    throw ThriftExceptionUtil.newSQLException(
        SQLState.NOT_FOR_PREPARED_STATEMENT, null, "execute(String, int[])");
  }
  @Override
  public final boolean execute(String sql, String[] columnNames)
      throws SQLException {
    throw ThriftExceptionUtil.newSQLException(
        SQLState.NOT_FOR_PREPARED_STATEMENT, null, "execute(String, String[])");
  }
  @Override
  public final ResultSet executeQuery(String sql) throws SQLException {
    throw ThriftExceptionUtil.newSQLException(
        SQLState.NOT_FOR_PREPARED_STATEMENT, null, "executeQuery(String)");
  }
  @Override
  public final int executeUpdate(String sql) throws SQLException {
    throw ThriftExceptionUtil.newSQLException(
        SQLState.NOT_FOR_PREPARED_STATEMENT, null, "executeUpdate(String)");
  }
  @Override
  public final int executeUpdate(String sql, int autoGeneratedKeys)
      throws SQLException {
    throw ThriftExceptionUtil
        .newSQLException(SQLState.NOT_FOR_PREPARED_STATEMENT, null,
            "executeUpdate(String, int)");
  }
  @Override
  public final int executeUpdate(String sql, int[] columnIndexes)
      throws SQLException {
    throw ThriftExceptionUtil.newSQLException(
        SQLState.NOT_FOR_PREPARED_STATEMENT, null,
        "executeUpdate(String, int[])");
  }
  @Override
  public final int executeUpdate(String sql, String[] columnNames)
      throws SQLException {
    throw ThriftExceptionUtil.newSQLException(
        SQLState.NOT_FOR_PREPARED_STATEMENT, null,
        "executeUpdate(String, String[])");
  }
  @Override
  public final void addBatch(String sql) throws SQLException {
    throw ThriftExceptionUtil.newSQLException(
        SQLState.NOT_FOR_PREPARED_STATEMENT, null, "addBatch(String)");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setNull(int parameterIndex, int sqlType)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    // ignore sqlType
    this.paramsList.setNull(parameterIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setBoolean(int parameterIndex, boolean x)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "boolean").setBoolean(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setByte(int parameterIndex, byte x) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "byte").setByte(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setShort(int parameterIndex, short x) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "short").setShort(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setInt(int parameterIndex, int x) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "int").setInteger(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setLong(int parameterIndex, long x) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "long").setLong(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setFloat(int parameterIndex, float x) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "float").setFloat(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setDouble(int parameterIndex, double x)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "double").setDouble(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setBigDecimal(int parameterIndex, BigDecimal x)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "BigDecimal")
        .setBigDecimal(this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setString(int parameterIndex, String x)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "String").setString(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setBytes(int parameterIndex, byte[] x) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "byte[]").setBytes(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setDate(int parameterIndex, Date x) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "java.sql.Date").setDate(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setTime(int parameterIndex, Time x) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "java.sql.Time").setTime(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setTimestamp(int parameterIndex, Timestamp x)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "java.sql.Timestamp")
        .setTimestamp(this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void clearParameters() throws SQLException {
    if (this.paramsList != EMPTY_ROW) {
      this.paramsList.clear();
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setObject(int parameterIndex, Object x, int targetSqlType)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(Converters.getThriftSQLType(targetSqlType),
        "Object").setObject(this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setObject(int parameterIndex, Object x) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    Converters.getConverter(getType(parameterIndex), "Object").setObject(
        this.paramsList, parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void addBatch() throws SQLException {
    checkClosed();

    if (this.parameterMetaData != null && this.parameterMetaData.size() > 0) {
      if (this.paramsBatch == null) {
        this.paramsBatch = new ArrayList<Row>();
      }
      this.paramsBatch.add(this.paramsList);
      this.paramsList = new Row(this.parameterMetaData);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setRef(int parameterIndex, Ref x) throws SQLException {
    throw ThriftExceptionUtil.notImplemented("PreparedStatement.setRef");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setArray(int parameterIndex, Array x) throws SQLException {
    throw ThriftExceptionUtil.notImplemented("PreparedStatement.setArray");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ResultSetMetaData getMetaData() throws SQLException {
    checkClosed();

    return new ClientRSMetaData(this.resultSetMetaData);
  }

  protected final long getTimeZoneOffset(long timeInMillis, Calendar cal) {
    Calendar targetCalendar = Calendar.getInstance(cal.getTimeZone());
    targetCalendar.clear();
    targetCalendar.setTimeInMillis(timeInMillis);
    cal.equals(targetCalendar);
    if (this.defaultCalendar == null) {
      this.defaultCalendar = Calendar.getInstance();
    }
    this.defaultCalendar.clear();
    this.defaultCalendar.setTimeInMillis(timeInMillis);
    return targetCalendar.get(Calendar.ZONE_OFFSET)
        - this.defaultCalendar.get(Calendar.ZONE_OFFSET)
        + targetCalendar.get(Calendar.DST_OFFSET)
        - this.defaultCalendar.get(Calendar.DST_OFFSET);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setDate(int parameterIndex, Date x, Calendar cal)
      throws SQLException {
    if (cal != null && x != null) {
      long timeInMillis = x.getTime();
      long timeZoneOffset = getTimeZoneOffset(timeInMillis, cal);
      if (timeZoneOffset != 0) {
        x = new Date(timeInMillis + timeZoneOffset);
      }
    }
    setDate(parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setTime(int parameterIndex, Time x, Calendar cal)
      throws SQLException {
    if (cal != null && x != null) {
      long timeInMillis = x.getTime();
      long timeZoneOffset = getTimeZoneOffset(timeInMillis, cal);
      if (timeZoneOffset != 0) {
        x = new Time(timeInMillis + timeZoneOffset);
      }
    }
    setTime(parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setTimestamp(int parameterIndex, Timestamp x, Calendar cal)
      throws SQLException {
    if (cal != null && x != null) {
      long timeInMillis = x.getTime();
      long timeZoneOffset = getTimeZoneOffset(timeInMillis, cal);
      if (timeZoneOffset != 0) {
        x = new Timestamp(timeInMillis + timeZoneOffset);
      }
    }
    setTimestamp(parameterIndex, x);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setNull(int parameterIndex, int sqlType, String typeName)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    // ignore sqlType and typeName
    this.paramsList.setNull(parameterIndex - 1);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setURL(int parameterIndex, URL x) throws SQLException {
    throw ThriftExceptionUtil.notImplemented("PreparedStatement.setURL");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public ParameterMetaData getParameterMetaData() throws SQLException {
    checkClosed();

    return new ClientParameterMetaData(this.parameterMetaData);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setRowId(int parameterIndex, RowId x) throws SQLException {
    throw ThriftExceptionUtil.notImplemented("PreparedStatement.setRowId");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setSQLXML(int parameterIndex, SQLXML xmlObject)
      throws SQLException {
    throw ThriftExceptionUtil.notImplemented("PreparedStatement.setSQLXML");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setObject(int parameterIndex, Object x, int targetSqlType,
      int scaleOrLength) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    if (x instanceof BigDecimal) {
      BigDecimal bd = (BigDecimal)x;
      if (bd.scale() != scaleOrLength) {
        // rounding as per server side EmbedResultSet20
        bd = new BigDecimal(bd.unscaledValue(), bd.scale());
        bd.setScale(scaleOrLength, BigDecimal.ROUND_HALF_DOWN);
      }
      setBigDecimal(parameterIndex, bd);
    }
    else if (x instanceof InputStream) {
      setBinaryStream(parameterIndex, (InputStream)x, scaleOrLength);
    }
    else if (x instanceof Reader) {
      setCharacterStream(parameterIndex, (Reader)x, scaleOrLength);
    }
    else {
      Converters.getConverter(Converters.getThriftSQLType(targetSqlType),
          "Object").setObject(this.paramsList, parameterIndex, x);
    }
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setBinaryStream(int parameterIndex, InputStream x,
      long length) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    // TODO Auto-generated method stub

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setBinaryStream(int parameterIndex, InputStream x)
      throws SQLException {
    setBinaryStream(parameterIndex, x, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setBinaryStream(int parameterIndex, InputStream x,
      int length) throws SQLException {
    setBinaryStream(parameterIndex, x, (long)length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setCharacterStream(int parameterIndex, Reader reader,
      long length) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    // TODO Auto-generated method stub

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setCharacterStream(int parameterIndex, Reader reader)
      throws SQLException {
    setCharacterStream(parameterIndex, reader, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setCharacterStream(int parameterIndex, Reader reader,
      int length) throws SQLException {
    setCharacterStream(parameterIndex, reader, (long)length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setAsciiStream(int parameterIndex, InputStream x,
      long length) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    // TODO Auto-generated method stub

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setAsciiStream(int parameterIndex, InputStream x)
      throws SQLException {
    setAsciiStream(parameterIndex, x, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setAsciiStream(int parameterIndex, InputStream x,
      int length) throws SQLException {
    setAsciiStream(parameterIndex, x, (long)length);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setUnicodeStream(int parameterIndex, InputStream x,
      int length) throws SQLException {
    throw ThriftExceptionUtil
        .notImplemented("PreparedStatement.setUnicodeStream");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setBlob(int parameterIndex, InputStream inputStream,
      long length) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    // TODO Auto-generated method stub

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setBlob(int parameterIndex, Blob x) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    // TODO Auto-generated method stub

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setBlob(int parameterIndex, InputStream inputStream)
      throws SQLException {
    setBlob(parameterIndex, inputStream, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setClob(int parameterIndex, Reader reader, long length)
      throws SQLException {
    checkValidParameterIndex(parameterIndex);

    // TODO Auto-generated method stub

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setClob(int parameterIndex, Clob x) throws SQLException {
    checkValidParameterIndex(parameterIndex);

    // TODO Auto-generated method stub

  }

  /**
   * {@inheritDoc}
   */
  @Override
  public final void setClob(int parameterIndex, Reader reader)
      throws SQLException {
    setClob(parameterIndex, reader, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNString(int parameterIndex, String value) throws SQLException {
    throw ThriftExceptionUtil.notImplemented("PreparedStatement.setNString");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNCharacterStream(int parameterIndex, Reader value, long length)
      throws SQLException {
    throw ThriftExceptionUtil
        .notImplemented("PreparedStatement.setNCharacterStream");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNCharacterStream(int parameterIndex, Reader value)
      throws SQLException {
    setNCharacterStream(parameterIndex, value, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNClob(int parameterIndex, Reader reader, long length)
      throws SQLException {
    throw ThriftExceptionUtil.notImplemented("PreparedStatement.setNClob");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNClob(int parameterIndex, NClob value) throws SQLException {
    throw ThriftExceptionUtil.notImplemented("PreparedStatement.setNClob");
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void setNClob(int parameterIndex, Reader reader) throws SQLException {
    setNClob(parameterIndex, reader, -1L);
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public String getSQL() {
    return this.preparedSQL;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public int getStatementId() {
    return this.statementId;
  }

  /**
   * {@inheritDoc}
   */
  @Override
  public void updatePrepareResult(PrepareResult pr) {
    setPrepareResult(pr);
  }
}

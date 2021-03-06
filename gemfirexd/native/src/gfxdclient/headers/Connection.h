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

/**
 * Connection.h
 *
 * The Connection abstraction allows connecting to a GemFireXD system and
 * creating/firing SQL statements. This is the basic entry point into the
 * GemFireXD client-side APIs.
 *
 *      Author: swale
 */

#ifndef CONNECTION_H_
#define CONNECTION_H_

#include "Types.h"
#include "DatabaseMetaData.h"
#include "Result.h"
#include "ResultSet.h"
#include "StatementAttributes.h"
#include "Row.h"

#include <map>
#include <set>
#include <vector>

namespace com
{
  namespace pivotal
  {
    namespace gemfirexd
    {
      namespace client
      {

        /**
         * Encapsulates the information for a connection attribute including
         * the attribute name passed when creating a {@link Connection},
         * help message, possible values and flags for the attribute.
         */
        class ConnectionProperty
        {
        private:
          /** name of the connection property */
          const std::string m_propName;
          /** help message for the property */
          const std::string m_helpMessage;
          /**
           * list of all possible values for the property,
           * if the property can only take on a set of fixed values
           */
          std::vector<std::string> m_possibleValues;
          /** any {@link Flags} for the property */
          const int m_flags;

          static std::map<std::string, ConnectionProperty> s_properties;

          static bool s_init;
          static bool init();

          // disable assignment operator
          const ConnectionProperty& operator=(const ConnectionProperty&);

        public:
          /** flags for the property */
          enum Flags
          {
            /** no special flags */
            F_NONE = 0x0,
            /** true if this is a system property and not a connection property */
            F_IS_SYSTEM_PROP = 0x1,
            /** if the value can be UTF-8 encoded */
            F_IS_UTF8 = 0x2,
            /** if this property is the "Driver" attribute */
            F_IS_DRIVER = 0x4,
            /** if this property is the "Server" attribute */
            F_IS_SERVER = 0x8,
            /** if this property is the "Port" attribute */
            F_IS_PORT = 0x10,
            /** if this property is the "User" attribute */
            F_IS_USER = 0x20,
            /** if this property is the "Password" attribute */
            F_IS_PASSWD = 0x40,
            /** if this property is a password field that should be hidden */
            F_IS_PASSWD_FIELD = 0x80,
            /** if the property can take on only boolean true/false values */
            F_IS_BOOLEAN = 0x100,
            /** if the property is a file or directory name */
            F_IS_FILENAME = 0x200
          };

          ConnectionProperty(const std::string& propName,
              const char* helpMessage, const char** possibleValues,
              const int flags);

          static void addProperty_(const std::string& propName,
              const char* helpMessage, const char** possibleValues,
              const int flags);

          static const std::set<std::string>& getValidPropertyNames();

          static const ConnectionProperty& getProperty(
              const std::string& propertyName);

          inline const std::string& getPropertyName() const throw () {
            return m_propName;
          }

          inline const std::string& getHelpMessage() const throw () {
            return m_helpMessage;
          }

          inline const std::vector<std::string>* getPossibleValues() const throw () {
            return &m_possibleValues;
          }

          const int getFlags() const throw () {
            return m_flags;
          }
        };

        class Connection
        {
        private:
          impl::ClientService* m_service;
          void* m_serviceId;
          AutoPtr<SQLWarning> m_warnings;
          AutoPtr<DatabaseMetaData> m_metadata;
          ResultSetHoldability::type m_defaultHoldability;

          // no copy constructor or assignment operator due to obvious issues
          // with usage of same connection by multiple thread concurrently
          Connection(const Connection&);
          Connection& operator=(const Connection&);

          void open(const std::string& host, const int port,
              thrift::OpenConnectionArgs& connArgs);

          void checkProperty(const std::string& propertyName);

          void convertOutputParameters(
              const std::map<int32_t, OutputParameter>& outputParams,
              std::map<int32_t, thrift::OutputParameter>& result);

        public:
          Connection() :
              m_service(NULL), m_serviceId(NULL), m_warnings(NULL), m_metadata(),
              m_defaultHoldability(ResultSetHoldability::NONE) {
          }

          void open(const std::string& host, const int port);

          void open(const std::string& host, const int port,
              const std::string& user, const std::string& password);

          void open(const std::string& host, const int port,
              const std::map<std::string, std::string>& properties);

          void open(const std::string& host, const int port,
              const std::string& user, const std::string& password,
              const std::map<std::string, std::string>& properties);

          inline bool isOpen() const throw () {
            return m_service != NULL;
          }

          inline impl::ClientService& checkAndGetService() const {
            impl::ClientService* service = m_service;
            if (service != NULL) {
              return *service;
            }
            else {
              throw GET_SQLEXCEPTION2(
                  SQLStateMessage::NO_CURRENT_CONNECTION_MSG);
            }
          }

          void setSendBufferSize(uint32_t sz);

          void setReceiveBufferSize(uint32_t sz);

          uint32_t getSendBufferSize() const;

          uint32_t getReceiveBufferSize() const;

          void setConnectTimeout(int millis);

          void setSendTimeout(int millis);

          void setReceiveTimeout(int millis);

          int getConnectTimeout() const;

          int getSendTimeout() const;

          int getReceiveTimeout() const;

          AutoPtr<Result> execute(const std::string& sql,
              const std::map<int32_t, OutputParameter>& outputParams =
                  EMPTY_OUTPUT_PARAMS, const StatementAttributes& attrs =
                  StatementAttributes::EMPTY);

          AutoPtr<ResultSet> executeQuery(const std::string& sql,
              const StatementAttributes& attrs = StatementAttributes::EMPTY);

          int32_t executeUpdate(const std::string& sql,
              const StatementAttributes& attrs = StatementAttributes::EMPTY);

          AutoPtr<std::vector<int32_t> > executeBatch(
              const std::vector<std::string>& batchSQLs,
              const StatementAttributes& attrs = StatementAttributes::EMPTY);

          AutoPtr<SQLWarning> getWarnings();

          AutoPtr<PreparedStatement> prepareStatement(const std::string& sql,
              const std::map<int32_t, OutputParameter>& outputParams =
                  EMPTY_OUTPUT_PARAMS, const StatementAttributes& attrs =
                  StatementAttributes::EMPTY);

          AutoPtr<Result> prepareAndExecute(const std::string& sql,
              const Parameters& params,
              const std::map<int32_t, OutputParameter>& outputParams =
                  EMPTY_OUTPUT_PARAMS, const StatementAttributes& attrs =
                  StatementAttributes::EMPTY);

          AutoPtr<Result> prepareAndExecuteBatch(const std::string& sql,
              const ParametersBatch& paramsBatch,
              const std::map<int32_t, OutputParameter>& outputParams =
                  EMPTY_OUTPUT_PARAMS, const StatementAttributes& attrs =
                  StatementAttributes::EMPTY);

          // transactions

          void beginTransaction(const IsolationLevel::type isolationLevel);

          IsolationLevel::type getCurrentIsolationLevel() const;

          void setTransactionAttribute(const TransactionAttribute::type flag,
              bool isTrue);

          bool getTransactionAttribute(const TransactionAttribute::type flag);

          void commitTransaction(bool startNewTransaction);

          void rollbackTransaction(bool startNewTransaction);

          void prepareCommitTransaction();

          const std::string getNativeSQL(const std::string& sql) const;

          ResultSetHoldability::type getResultSetHoldability() const throw ();

          // TODO: holdability is not yet carried through to thrift API calls
          // GFXD server does not yet implement HOLD_CURSORS so its okay for now
          void setResultSetHoldability(
              ResultSetHoldability::type holdability) throw ();

          // metadata API

          AutoPtr<DatabaseMetaData> getServiceMetaData();

          AutoPtr<ResultSet> getSchemaMetaData(
              const DatabaseMetaDataCall::type method,
              DatabaseMetaDataArgs& args, const DriverType::type driverType =
                  DriverType::ODBC);

          AutoPtr<ResultSet> getIndexInfo(DatabaseMetaDataArgs& args,
              bool unique, bool approximate,
              const DriverType::type driverType = DriverType::ODBC);

          AutoPtr<ResultSet> getUDTs(DatabaseMetaDataArgs& args,
              const std::string& typeNamePattern,
              const std::vector<SQLType::type>& types,
              const DriverType::type driverType = DriverType::ODBC);

          AutoPtr<ResultSet> getBestRowIdentifier(
              DatabaseMetaDataArgs& metadaArgs, int32_t scope, bool nullable,
              const DriverType::type driverType = DriverType::ODBC);

          // end metadata API

          void close();

          ~Connection() throw ();
        };

      } /* namespace client */
    } /* namespace gemfirexd */
  } /* namespace pivotal */
} /* namespace com */

#endif /* CONNECTION_H_ */

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
 * ClientService.cpp
 *
 *      Author: swale
 */

#include "ClientService.h"

#include <boost/thread/lock_guard.hpp>
#include <boost/algorithm/string.hpp>
#include <boost/make_shared.hpp>
#include <boost/asio.hpp>
#include <boost/log/attributes/current_process_id.hpp>
#include <boost/lexical_cast.hpp>
#include <boost/date_time/posix_time/posix_time.hpp>
#include <boost/chrono/process_cpu_clocks.hpp>

#include <thrift/transport/TTransportException.h>
#include <thrift/transport/TSSLSocket.h>
#include <thrift/transport/TBufferTransports.h>
#include <thrift/protocol/TBinaryProtocol.h>
#include <thrift/protocol/TCompactProtocol.h>
#include <thrift/protocol/TProtocolException.h>

#include "common/SystemProperties.h"

#include "ClientProperty.h"
#include "ClientAttribute.h"

#include "SQLException.h"
#include "LogWriter.h"
#include "Utils.h"

#include "BufferedSocketTransport.h"
#include "DNSCacheService.h"
#include "InternalLogger.h"
#include "InternalUtils.h"

using namespace com::pivotal::gemfirexd;
using namespace com::pivotal::gemfirexd::client;
using namespace com::pivotal::gemfirexd::client::impl;

namespace _gfxd_impl
{
  struct CollectHostAddresses
  {
    std::vector<thrift::HostAddress>& m_connHosts;

    void operator()(const std::string& str) {
      std::string host;
      int port;
      thrift::HostAddress hostAddr;

      Utils::getHostPort(str, host, port);
      Utils::getHostAddress(host, port, hostAddr);
      m_connHosts.push_back(hostAddr);
    }
  };
}

bool thrift::HostAddress::operator <(const HostAddress& other) const {
  const int32_t myPort = port;
  const int32_t otherPort = other.port;
  if (myPort != otherPort) {
    return (myPort < otherPort);
  }

  return (hostName < other.hostName);
}

std::string ClientService::s_hostName;
std::string ClientService::s_hostId;
boost::mutex ClientService::s_globalLock;

void DEFAULT_OUTPUT_FN(const char *str) {
  LogWriter::INFO() << str << _GFXD_NEWLINE;
}

void ClientService::staticInitialize(
    std::map<std::string, std::string>& props) {
  boost::lock_guard<boost::mutex> sync(s_globalLock);

  if (s_hostName.empty()) {

    // first initialize any utilities used by other parts of product
    InternalUtils::staticInitialize();
    // then initialize the common message library
    SQLStateMessage::staticInitialize();
    // and the logger
    LogWriter::staticInitialize();

    s_hostName = boost::asio::ip::host_name();
    // use process ID and timestamp for ID
    boost::log::process_id::native_type pid =
        boost::log::attributes::current_process_id().get().native_id();
    s_hostId = boost::lexical_cast<std::string>(pid);
    s_hostId.append(1, '|');
    boost::posix_time::ptime currentTime =
        boost::posix_time::microsec_clock::universal_time();
    s_hostId.append(boost::posix_time::to_simple_string(currentTime));

    LogWriter& globalLogger = LogWriter::global();
    std::string logFile, logLevelStr;
    LogLevel::type logLevel = globalLogger.getLogLevel();
    std::map<std::string, std::string>::iterator search;

    search = props.find(ClientAttribute::LOG_FILE);
    if (search != props.end()) {
      logFile = search->second;
      props.erase(search);
    }
    search = props.find(ClientAttribute::LOG_LEVEL);
    if (search != props.end()) {
      logLevel = LogLevel::fromString(search->second, globalLogger);
      logLevelStr = search->second;
      props.erase(search);
    }
    // now check the SystemProperties
    SystemProperties::getProperty(ClientProperty::LOG_FILE_NAME, logFile,
        logFile);
    if (SystemProperties::getProperty(ClientProperty::LOG_LEVEL_NAME,
        logLevelStr, logLevelStr)) {
      logLevel = LogLevel::fromString(logLevelStr, globalLogger);
    }

    globalLogger.initialize(logFile, logLevel);
    apache::thrift::GlobalOutput.setOutputFunction(DEFAULT_OUTPUT_FN);

    if (LogWriter::ERROR_ENABLED()) {
      LogWriter::CONFIG() << "Starting client on '" << s_hostName
          << "' with ID='" << s_hostId << '\'' << _GFXD_NEWLINE;
    }
  }
}

void ClientService::handleStdException(const char* op,
    const std::exception& stde) {
  std::ostringstream reason;
  reason << "(Server=" << m_currentHostAddr << ", operation=" << op << ") ";
  Utils::printFullMessage(stde, reason);
  throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, reason.str());
}

void ClientService::handleUnknownException(const char* op) {
  std::string reason;
  reason.append("Unknown exception in operation ").append(op);
  throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, reason.c_str());
}

void ClientService::handleGFXDException(const thrift::GFXDException& sqle) {
  throw GET_SQLEXCEPTION(sqle);
}

void ClientService::handleTTransportException(const char* op,
    const TTransportException& tte) {
  throwSQLExceptionForNodeFailure(op, tte);
}

void ClientService::handleTProtocolException(const char* op,
    const protocol::TProtocolException& tpe) {
  throw GET_SQLEXCEPTION2(SQLStateMessage::THRIFT_PROTOCOL_ERROR_MSG,
      tpe.what(), op);
}

void ClientService::handleTException(const char* op, const TException& te) {
  handleStdException(op, te);
}

/*
void ClientService::handleException(const TException* te, const thrift::GFXDException* gfxde,
    const std::set<thrift::HostAddress>& failedServers, bool tryFailover,
    bool createNewConnection, const std::string& op)
{
    if (!m_isOpen && createNewConnection) {
      if (gfxde != NULL) {
        throw GET_SQLEXCEPTION2(gfxde);
      }
      else {
        throw GET_SQLEXCEPTION2(SQLState::NO_CURRENT_CONNECTION, te);
      }
    }
  if (!m_loadBalance
  // no failover for transactions yet
      || m_isolationLevel != IsolationLevel::NONE) {
    tryFailover = false;
  }
    if (gfxde != NULL) {
      const thrift::GFXDExceptionData& gfxdeData = gfxde->exceptionData;
      const std::string& sqlState = gfxdeData.sqlState;
      NetConnection::FailoverStatus status;
      if ((status = NetConnection.getFailoverStatus(sqlState,
          gfxdeData.getSeverity(), gfxde)).isNone()) {
        // convert DATA_CONTAINTER_CLOSED to "X0Z01" for non-transactional case
        if (this.isolationLevel == Connection.TRANSACTION_NONE
            && SQLState.DATA_CONTAINER_CLOSED.equals(sqlState)) {
          throw newGFXDExceptionForNodeFailure(op,
              ClientSharedUtils.newRuntimeException(gfxdeData.getReason(),
                  gfxde.getCause()));
        }
        else {
          throw gfxde;
        }
      }
      else if (!tryFailover) {
        throw newGFXDExceptionForNodeFailure(op, gfxde);
      }
      else if (status == NetConnection.FailoverStatus.RETRY) {
        return failedServers;
      }
    }
    else if (t instanceof TException) {
      if (!tryFailover) {
        throw newGFXDExceptionForNodeFailure(op, t);
      }
    }
    else {
      throw ClientExceptionUtil.newGFXDException(SQLState.JAVA_EXCEPTION, t,
          t.getClass(), t.getMessage() + " (Server=" + this.currentHostAddr
              + ')');
    }
    // need to do failover to new server, so get the next one
    if (failedServers == null) {
      @SuppressWarnings("unchecked")
      Set<HostAddress> servers = new THashSet(2);
      failedServers = servers;
    }
    failedServers.add(this.currentHostAddr);

    if (createNewConnection) {
      openConnection(this.currentHostAddr, failedServers);
    }
    return failedServers;
}
*/

void ClientService::throwSQLExceptionForNodeFailure(const char* op,
    const std::exception& se) {
  std::ostringstream hostAddrStr;
  hostAddrStr << m_currentHostAddr;
  if (m_isolationLevel == IsolationLevel::NONE) {
    // throw X0Z01 for this case
    throw GET_SQLEXCEPTION2(SQLStateMessage::GFXD_NODE_SHUTDOWN_MSG,
        hostAddrStr.str().c_str(), se, op);
  }
  else {
    // throw 40XD0 for this case
    throw GET_SQLEXCEPTION2(SQLStateMessage::DATA_CONTAINER_CLOSED_MSG,
        hostAddrStr.str().c_str(), se, op);
  }
}

void ClientService::clearPendingTransactionAttrs() {
  if (m_hasPendingTXAttrs) {
    m_pendingTXAttrs.clear();
    m_hasPendingTXAttrs = false;
    m_currentTXAttrs.clear();
  }
}

void ClientService::flushPendingTransactionAttrs() {
  // TODO: we could just do a send_set... here and for the subsequent
  // operation, then recv_ both in order taking care to catch exception
  // from first and invoke second in any case (server side is expected
  // fail second one too with "piggybacked=true") and then throw back
  // the exception from first at the end
  m_client.setTransactionAttributes(m_connId, m_pendingTXAttrs, m_token);
  clearPendingTransactionAttrs();
}

void ClientService::setPendingTransactionAttrs(
    thrift::StatementAttrs& stmtAttrs) {
  stmtAttrs.__set_pendingTransactionAttrs(m_pendingTXAttrs);
}

// using TBufferedTransport with TCompactProtocol to match the server
// settings; this could become configurable in future
ClientService::ClientService(const std::string& host, const int port,
    thrift::OpenConnectionArgs& connArgs, void** outServiceId) :
    // default for load-balance is true
    m_connArgs(initConnectionArgs(connArgs)), m_loadBalance(true),
    m_reqdServerType(thrift::ServerType::THRIFT_GFXD_CP), m_serverGroups(),
    m_transport(), m_client(createDummyProtocol()), m_serviceId(0),
    m_connHosts(1), m_connId(0), m_token(), m_isOpen(false),
    m_pendingTXAttrs(), m_hasPendingTXAttrs(false),
    m_isolationLevel(IsolationLevel::NONE) {
  std::map<std::string, std::string>& props = connArgs.properties;
  std::map<std::string, std::string>::iterator propValue;

  thrift::HostAddress hostAddr;
  Utils::getHostAddress(host, port, hostAddr);

  m_connHosts.push_back(hostAddr);

  if (!props.empty()) {
    if ((propValue = props.find(ClientAttribute::LOAD_BALANCE))
        != props.end()) {
      m_loadBalance = boost::iequals("false", propValue->second);
      props.erase(propValue);
    }

    // setup the original host list
    if ((propValue = props.find(ClientAttribute::SECONDARY_LOCATORS))
        != props.end()) {
      _gfxd_impl::CollectHostAddresses addHostAddresses = { m_connHosts };
      InternalUtils::splitCSV(propValue->second, addHostAddresses);
      props.erase(propValue);
    }

    // read the server groups to use for connection
    if ((propValue = props.find(ClientAttribute::SERVER_GROUPS))
        != props.end()) {
      InternalUtils::CollectStrings<typename std::set<std::string> > cs(
          m_serverGroups);
      InternalUtils::splitCSV(propValue->second, cs);
      props.erase(propValue);
    }

    // now check for the protocol details like SSL etc
    // and reqd gfxdServerType
    bool binaryProtocol = false;
    bool useSSL = false;
    //SSLSocketParameters sslParams = null;
    std::map<std::string, std::string>::iterator propValue;

    std::map<std::string, std::string>& props = connArgs.properties;
    if ((propValue = props.find(ClientAttribute::THRIFT_USE_BINARY_PROTOCOL))
        != props.end()) {
      binaryProtocol = boost::iequals(propValue->second, "true");
      props.erase(propValue);
    }
    if ((propValue = props.find(ClientAttribute::SSL)) != props.end()) {
      useSSL = boost::iequals(propValue->second, "true");
      props.erase(propValue);
    }
    if ((propValue = props.find(ClientAttribute::THRIFT_SSL_PROPERTIES))
        != props.end()) {
      useSSL = true;
      //sslParams = Utils::getSSLParameters(propValue->second);
      props.erase(propValue);
    }
    m_reqdServerType = getServerType(true, binaryProtocol, useSSL);
  }

  std::set<thrift::HostAddress> failedServers;
  openConnection(hostAddr, failedServers);

  if (outServiceId != NULL) {
    *outServiceId = ClientServiceHolder::instance().registerInstance(*this);
  }
}

void ClientService::openConnection(thrift::HostAddress& hostAddr,
    std::set<thrift::HostAddress>& failedServers) {
  // open the connection
  boost::thread::id tid;
  NanoTimeThread start;
  NanoDurationThread elapsed;
  if (TraceFlag::ClientStatementHA.global() | TraceFlag::ClientConn.global()) {
    start = InternalUtils::nanoTimeThread();
    tid = boost::this_thread::get_id();
    AutoPtr<SQLException> ex(
        TraceFlag::ClientConn.global() ? new GET_SQLEXCEPTION(
            SQLState::UNKNOWN_EXCEPTION, "stack"): NULL);
    InternalLogger::TRACE_COMPACT(tid, "openConnection_S", NULL, 0, true, 0,
        m_connId, m_token, ex.get());
  }

  m_currentHostAddr = hostAddr;
  while (true) {
    /*
     if (m_loadBalance) {
     ControlConnection controlService = ControlConnection
     .getOrCreateControlConnection(this.connHosts.get(0), this);
     // at this point query the control service for preferred server
     hostAddr = controlService.getPreferredServer(failedServers,
     this.serverGroups, false);
     }
     */

    try {
      // first close any existing transport
      destroyTransport();

      boost::shared_ptr<protocol::TProtocol> protocol(
          createProtocol(hostAddr, m_reqdServerType, m_transport));
      m_client.resetProtocols(protocol, protocol);

      thrift::ConnectionProperties connProps;
      m_client.openConnection(connProps, m_connArgs);
      m_connId = connProps.connId;
      if (connProps.__isset.token) {
        m_token = connProps.token;
      }
      m_currentHostAddr = hostAddr;
      m_isOpen = true;

      if (TraceFlag::ClientStatementHA.global()
          | TraceFlag::ClientConn.global()) {

        elapsed = (InternalUtils::nanoTimeThread() - start);
        InternalLogger::TRACE_COMPACT(tid, "openConnection_E", NULL, 0,
        false, elapsed.count(), m_connId, m_token);

        if (TraceFlag::ClientHA.global()) {
          if (m_token.empty()) {
            LogWriter::TRACE(TraceFlag::ClientHA) << "Opened connection @"
                << (int64_t)this << " ID=" << m_connId;
          }
          else {
            LogWriter::TRACE(TraceFlag::ClientHA) << "Opened connection @"
                << (int64_t)this << " ID=" << m_connId << " @"
                << hexstr(m_token);
          }
        }
      }
      return;
    } catch (const thrift::GFXDException& sqle) {
      handleGFXDException(sqle);
    } catch (const TTransportException& tte) {
      handleTTransportException("openConnection", tte);
    } catch (const protocol::TProtocolException& tpe) {
      handleTProtocolException("openConnection", tpe);
    } catch (const TException& te) {
      handleTException("openConnection", te);
    } catch (const std::exception& stde) {
      handleStdException("openConnection", stde);
    } catch (...) {
      handleUnknownException("openConnection");
    }
  }
}

void ClientService::destroyTransport() throw () {
  // destructor should *never* throw an exception
  try {
    BufferedSocketTransport* transport = m_transport.get();
    if (transport != NULL) {
      if (transport->isOpen()) {
        transport->close();
      }
      m_transport.reset();
    }
  } catch (const SQLException& sqle) {
    Utils::handleExceptionInDestructor("connection service", sqle);
  } catch (const std::exception& stde) {
    Utils::handleExceptionInDestructor("connection service", stde);
  } catch (...) {
    Utils::handleExceptionInDestructor("connection service");
  }
}

ClientService::~ClientService() throw () {
  // destructor should *never* throw an exception
  destroyTransport();
}

thrift::OpenConnectionArgs& ClientService::initConnectionArgs(
    thrift::OpenConnectionArgs& connArgs) {
  // first initialize the library if required
  staticInitialize(connArgs.properties);

  // set the global hostName and hostId into connArgs
  connArgs.__set_clientHostName(s_hostName);
  std::ostringstream hostId;
  hostId << s_hostId << '|' << Utils::threadName << "<0x" << std::hex
      << boost::this_thread::get_id() << std::dec << '>';
  connArgs.__set_clientID(hostId.str());
  // TODO: fixed security mechanism for now
  connArgs.__set_security(thrift::SecurityMechanism::PLAIN);
  return connArgs;
}

thrift::ServerType::type ClientService::getServerType(bool isServer,
bool useBinaryProtocol, bool useSSL) {
  if (isServer) {
    if (useSSL) {
      return
          useBinaryProtocol ? thrift::ServerType::THRIFT_GFXD_BP_SSL :
              thrift::ServerType::THRIFT_GFXD_CP_SSL;
    }
    else {
      return
          useBinaryProtocol ? thrift::ServerType::THRIFT_GFXD_BP :
              thrift::ServerType::THRIFT_GFXD_CP;
    }
  }
  else if (useSSL) {
    return
        useBinaryProtocol ? thrift::ServerType::THRIFT_LOCATOR_BP_SSL :
            thrift::ServerType::THRIFT_LOCATOR_CP_SSL;
  }
  else {
    return
        useBinaryProtocol ? thrift::ServerType::THRIFT_LOCATOR_BP :
            thrift::ServerType::THRIFT_LOCATOR_CP;
  }
}

protocol::TProtocol* ClientService::createDummyProtocol() {
  boost::shared_ptr<TMemoryBuffer> dummyTransport(new TMemoryBuffer(0));
  return new protocol::TBinaryProtocol(dummyTransport);
}

protocol::TProtocol* ClientService::createProtocol(
    thrift::HostAddress& hostAddr, const thrift::ServerType::type serverType,
    //const SSLSocketParameters& sslParams,
    boost::shared_ptr<BufferedSocketTransport>& returnTransport) {
  bool useBinaryProtocol;
  bool useSSL;
  switch (serverType) {
    case thrift::ServerType::THRIFT_GFXD_CP:
    case thrift::ServerType::THRIFT_LOCATOR_CP:
      // these are default settings
      useBinaryProtocol = false;
      useSSL = false;
      break;
    case thrift::ServerType::THRIFT_GFXD_BP:
    case thrift::ServerType::THRIFT_LOCATOR_BP:
      useBinaryProtocol = true;
      useSSL = false;
      break;
    case thrift::ServerType::THRIFT_GFXD_CP_SSL:
    case thrift::ServerType::THRIFT_LOCATOR_CP_SSL:
      useBinaryProtocol = false;
      useSSL = true;
      break;
    case thrift::ServerType::THRIFT_GFXD_BP_SSL:
    case thrift::ServerType::THRIFT_LOCATOR_BP_SSL:
      useBinaryProtocol = true;
      useSSL = true;
      break;
    default:
      std::string reason("unexpected server type for thrift driver = ");
      reason.append(boost::lexical_cast<std::string>((int)serverType));
      throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, reason);
  }

  int32_t rsz = SystemProperties::getInteger(
      ClientProperty::SOCKET_INPUT_BUFFER_SIZE_NAME,
      ClientProperty::DEFAULT_INPUT_BUFFER_SIZE);
  int32_t wsz = SystemProperties::getInteger(
      ClientProperty::SOCKET_OUTPUT_BUFFER_SIZE_NAME,
      ClientProperty::DEFAULT_OUTPUT_BUFFER_SIZE);

  // resolve the hostAddr using DNSCacheService to minimize DNS lookup
  // from hostname (when hostnames are being used)
  // it is also required in case hostname lookups are not working from
  // client-side and only IP addresses provided by servers are supposed
  // to work
  DNSCacheService::instance().resolve(hostAddr);

  if (useSSL) {
    // TODO: SW: SSL params support
    TSSLSocketFactory sslSocketFactory;
    sslSocketFactory.authenticate(false);
    boost::shared_ptr<TSocket> sslSocket = sslSocketFactory.createSocket(
        hostAddr.hostName, hostAddr.port);
    returnTransport.reset(
        new BufferedSocketTransport(sslSocket, rsz, wsz, false));
  }
  else {
    boost::shared_ptr<TSocket> socket(
        new TSocket(hostAddr.hostName, hostAddr.port));
    returnTransport.reset(new BufferedSocketTransport(socket, rsz, wsz, true));
  }
  if (useBinaryProtocol) {
    return new protocol::TBinaryProtocol(returnTransport);
  }
  else {
    return new protocol::TCompactProtocol(returnTransport);
  }
}

void ClientService::execute(thrift::StatementResult& _return,
    const std::string& sql,
    const std::map<int32_t, thrift::OutputParameter>& outputParams,
    const thrift::StatementAttrs& attrs) {
  try {
    if (!m_hasPendingTXAttrs) {
      m_client.execute(_return, m_connId, sql, outputParams, attrs, m_token);
    }
    else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.execute(_return, m_connId, sql, outputParams, newAttrs, m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("execute", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("execute", tpe);
  } catch (const TException& te) {
    handleTException("execute", te);
  } catch (const std::exception& stde) {
    handleStdException("execute", stde);
  } catch (...) {
    handleUnknownException("execute");
  }
}

void ClientService::executeUpdate(thrift::UpdateResult& _return,
    const std::vector<std::string>& sqls,
    const thrift::StatementAttrs& attrs) {
  try {
    if (!m_hasPendingTXAttrs) {
      m_client.executeUpdate(_return, m_connId, sqls, attrs, m_token);
    }
    else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.executeUpdate(_return, m_connId, sqls, newAttrs, m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executeUpdate", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executeUpdate", tpe);
  } catch (const TException& te) {
    handleTException("executeUpdate", te);
  } catch (const std::exception& stde) {
    handleStdException("executeUpdate", stde);
  } catch (...) {
    handleUnknownException("executeUpdate");
  }
}

void ClientService::executeQuery(thrift::RowSet& _return,
    const std::string& sql, const thrift::StatementAttrs& attrs) {
  try {
    if (!m_hasPendingTXAttrs) {
      m_client.executeQuery(_return, m_connId, sql, attrs, m_token);
    }
    else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.executeQuery(_return, m_connId, sql, newAttrs, m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executeQuery", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executeQuery", tpe);
  } catch (const TException& te) {
    handleTException("executeQuery", te);
  } catch (const std::exception& stde) {
    handleStdException("executeQuery", stde);
  } catch (...) {
    handleUnknownException("executeQuery");
  }
}

void ClientService::prepareStatement(thrift::PrepareResult& _return,
    const std::string& sql,
    const std::map<int32_t, thrift::OutputParameter>& outputParams,
    const thrift::StatementAttrs& attrs) {
  try {
    if (!m_hasPendingTXAttrs) {
      m_client.prepareStatement(_return, m_connId, sql, outputParams, attrs,
          m_token);
    }
    else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.prepareStatement(_return, m_connId, sql, outputParams, newAttrs,
          m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("prepareStatement", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("prepareStatement", tpe);
  } catch (const TException& te) {
    handleTException("prepareStatement", te);
  } catch (const std::exception& stde) {
    handleStdException("prepareStatement", stde);
  } catch (...) {
    handleUnknownException("prepareStatement");
  }
}

void ClientService::executePrepared(thrift::StatementResult& _return,
    thrift::PrepareResult& prepResult, const thrift::Row& params,
    const std::map<int32_t, thrift::OutputParameter>& outputParams) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePrepared(_return, prepResult.statementId, params,
        outputParams, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePrepared", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePrepared", tpe);
  } catch (const TException& te) {
    handleTException("executePrepared", te);
  } catch (const std::exception& stde) {
    handleStdException("executePrepared", stde);
  } catch (...) {
    handleUnknownException("executePrepared");
  }
}

void ClientService::executePreparedUpdate(thrift::UpdateResult& _return,
    thrift::PrepareResult& prepResult, const thrift::Row& params) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePreparedUpdate(_return, prepResult.statementId, params,
        m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePreparedUpdate", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePreparedUpdate", tpe);
  } catch (const TException& te) {
    handleTException("executePreparedUpdate", te);
  } catch (const std::exception& stde) {
    handleStdException("executePreparedUpdate", stde);
  } catch (...) {
    handleUnknownException("executePreparedUpdate");
  }
}

void ClientService::executePreparedQuery(thrift::RowSet& _return,
    thrift::PrepareResult& prepResult, const thrift::Row& params) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePreparedQuery(_return, prepResult.statementId, params,
        m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePreparedQuery", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePreparedQuery", tpe);
  } catch (const TException& te) {
    handleTException("executePreparedQuery", te);
  } catch (const std::exception& stde) {
    handleStdException("executePreparedQuery", stde);
  } catch (...) {
    handleUnknownException("executePreparedQuery");
  }
}

void ClientService::executePreparedBatch(thrift::UpdateResult& _return,
    thrift::PrepareResult& prepResult,
    const std::vector<thrift::Row>& paramsBatch) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executePreparedBatch(_return, prepResult.statementId, paramsBatch,
        m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executePreparedBatch", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executePreparedBatch", tpe);
  } catch (const TException& te) {
    handleTException("executePreparedBatch", te);
  } catch (const std::exception& stde) {
    handleStdException("executePreparedBatch", stde);
  } catch (...) {
    handleUnknownException("executePreparedBatch");
  }
}

void ClientService::prepareAndExecute(thrift::StatementResult& _return,
    const std::string& sql, const std::vector<thrift::Row>& paramsBatch,
    const std::map<int32_t, thrift::OutputParameter>& outputParams,
    const thrift::StatementAttrs& attrs) {
  try {
    if (!m_hasPendingTXAttrs) {
      m_client.prepareAndExecute(_return, m_connId, sql, paramsBatch,
          outputParams, attrs, m_token);
    }
    else {
      thrift::StatementAttrs newAttrs(attrs);
      setPendingTransactionAttrs(newAttrs);

      m_client.prepareAndExecute(_return, m_connId, sql, paramsBatch,
          outputParams, newAttrs, m_token);

      clearPendingTransactionAttrs();
    }
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("prepareAndExecute", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("prepareAndExecute", tpe);
  } catch (const TException& te) {
    handleTException("prepareAndExecute", te);
  } catch (const std::exception& stde) {
    handleStdException("prepareAndExecute", stde);
  } catch (...) {
    handleUnknownException("prepareAndExecute");
  }
}

void ClientService::getNextResultSet(thrift::RowSet& _return,
    const int32_t cursorId, const int8_t otherResultSetBehaviour) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getNextResultSet(_return, cursorId, otherResultSetBehaviour,
        m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getNextResultSet", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getNextResultSet", tpe);
  } catch (const TException& te) {
    handleTException("getNextResultSet", te);
  } catch (const std::exception& stde) {
    handleStdException("getNextResultSet", stde);
  } catch (...) {
    handleUnknownException("getNextResultSet");
  }
}

void ClientService::getBlobChunk(thrift::BlobChunk& _return,
    const int32_t lobId, const int64_t offset, const int32_t size,
    const bool freeLobAtEnd) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getBlobChunk(_return, m_connId, lobId, offset, size,
        freeLobAtEnd, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getBlobChunk", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getBlobChunk", tpe);
  } catch (const TException& te) {
    handleTException("getBlobChunk", te);
  } catch (const std::exception& stde) {
    handleStdException("getBlobChunk", stde);
  } catch (...) {
    handleUnknownException("getBlobChunk");
  }
}

void ClientService::getClobChunk(thrift::ClobChunk& _return,
    const int32_t lobId, const int64_t offset, const int32_t size,
    const bool freeLobAtEnd) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getClobChunk(_return, m_connId, lobId, offset, size,
        freeLobAtEnd, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getClobChunk", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getClobChunk", tpe);
  } catch (const TException& te) {
    handleTException("getClobChunk", te);
  } catch (const std::exception& stde) {
    handleStdException("getClobChunk", stde);
  } catch (...) {
    handleUnknownException("getClobChunk");
  }
}

int32_t ClientService::sendBlobChunk(thrift::BlobChunk& chunk) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    return m_client.sendBlobChunk(chunk, m_connId, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("sendBlobChunk", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("sendBlobChunk", tpe);
  } catch (const TException& te) {
    handleTException("sendBlobChunk", te);
  } catch (const std::exception& stde) {
    handleStdException("sendBlobChunk", stde);
  } catch (...) {
    handleUnknownException("sendBlobChunk");
  }
  // never reached
  return -1;
}

int32_t ClientService::sendClobChunk(thrift::ClobChunk& chunk) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    return m_client.sendClobChunk(chunk, m_connId, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("sendClobChunk", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("sendClobChunk", tpe);
  } catch (const TException& te) {
    handleTException("sendClobChunk", te);
  } catch (const std::exception& stde) {
    handleStdException("sendClobChunk", stde);
  } catch (...) {
    handleUnknownException("sendClobChunk");
  }
  // never reached
  return -1;
}

void ClientService::freeLob(const int32_t lobId) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.freeLob(m_connId, lobId, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("freeLob", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("freeLob", tpe);
  } catch (const TException& te) {
    handleTException("freeLob", te);
  } catch (const std::exception& stde) {
    handleStdException("freeLob", stde);
  } catch (...) {
    handleUnknownException("freeLob");
  }
}

void ClientService::scrollCursor(thrift::RowSet& _return,
    const int32_t cursorId, const int32_t offset, const bool offsetIsAbsolute,
    const bool fetchReverse, const int32_t fetchSize) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.scrollCursor(_return, cursorId, offset, offsetIsAbsolute,
        fetchReverse, fetchSize, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("scrollCursor", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("scrollCursor", tpe);
  } catch (const TException& te) {
    handleTException("scrollCursor", te);
  } catch (const std::exception& stde) {
    handleStdException("scrollCursor", stde);
  } catch (...) {
    handleUnknownException("scrollCursor");
  }
}

void ClientService::executeCursorUpdate(const int32_t cursorId,
    const int8_t operation, const thrift::Row& changedRow,
    const std::vector<int32_t>& changedColumns, const int32_t changedRowIndex) {
  executeBatchCursorUpdate(cursorId, Utils::singleVector(operation),
      Utils::singleVector(changedRow), Utils::singleVector(changedColumns),
      Utils::singleVector(changedRowIndex));
}

void ClientService::executeBatchCursorUpdate(const int32_t cursorId,
    const std::vector<int8_t>& operations,
    const std::vector<thrift::Row>& changedRows,
    const std::vector<std::vector<int32_t> >& changedColumnsList,
    const std::vector<int32_t>& changedRowIndexes) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.executeCursorUpdate(cursorId, operations, changedRows,
        changedColumnsList, changedRowIndexes, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("executeBatchCursorUpdate", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("executeBatchCursorUpdate", tpe);
  } catch (const TException& te) {
    handleTException("executeBatchCursorUpdate", te);
  } catch (const std::exception& stde) {
    handleStdException("executeBatchCursorUpdate", stde);
  } catch (...) {
    handleUnknownException("executeBatchCursorUpdate");
  }
}

void ClientService::beginTransaction(
    const IsolationLevel::type isolationLevel) {
  try {
    m_client.beginTransaction(m_connId, static_cast<int8_t>(isolationLevel),
        m_pendingTXAttrs, m_token);
    clearPendingTransactionAttrs();
    m_isolationLevel = isolationLevel;
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("beginTransaction", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("beginTransaction", tpe);
  } catch (const TException& te) {
    handleTException("beginTransaction", te);
  } catch (const std::exception& stde) {
    handleStdException("beginTransaction", stde);
  } catch (...) {
    handleUnknownException("beginTransaction");
  }
}

void ClientService::setTransactionAttribute(
    const TransactionAttribute::type flag, bool isTrue) {
  m_pendingTXAttrs[flag] = isTrue;
  m_hasPendingTXAttrs = true;
}

bool ClientService::getTransactionAttribute(
    const TransactionAttribute::type flag) {
  std::map<TransactionAttribute::type, bool>::const_iterator res;
  if (m_pendingTXAttrs.size() > 0
      && (res = m_pendingTXAttrs.find(flag)) != m_pendingTXAttrs.end()) {
    return res->second;
  }
  else {
    if (m_currentTXAttrs.size() > 0
        && (res = m_currentTXAttrs.find(flag)) != m_currentTXAttrs.end()) {
      return res->second;
    }
    else {
      getTransactionAttributes(m_currentTXAttrs);
      return m_currentTXAttrs[flag];
    }
  }
}

void ClientService::getTransactionAttributes(
    std::map<TransactionAttribute::type, bool>& _return) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getTransactionAttributes(_return, m_connId, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getTransactionAttributes", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getTransactionAttributes", tpe);
  } catch (const TException& te) {
    handleTException("getTransactionAttributes", te);
  } catch (const std::exception& stde) {
    handleStdException("getTransactionAttributes", stde);
  } catch (...) {
    handleUnknownException("getTransactionAttributes");
  }
}

void ClientService::commitTransaction(const bool startNewTransaction) {
  try {
    m_client.commitTransaction(m_connId, startNewTransaction, m_pendingTXAttrs,
        m_token);
    clearPendingTransactionAttrs();
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("commitTransaction", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("commitTransaction", tpe);
  } catch (const TException& te) {
    handleTException("commitTransaction", te);
  } catch (const std::exception& stde) {
    handleStdException("commitTransaction", stde);
  } catch (...) {
    handleUnknownException("commitTransaction");
  }
}

void ClientService::rollbackTransaction(const bool startNewTransaction) {
  try {
    m_client.rollbackTransaction(m_connId, startNewTransaction,
        m_pendingTXAttrs, m_token);
    clearPendingTransactionAttrs();
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("rollbackTransaction", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("rollbackTransaction", tpe);
  } catch (const TException& te) {
    handleTException("rollbackTransaction", te);
  } catch (const std::exception& stde) {
    handleStdException("rollbackTransaction", stde);
  } catch (...) {
    handleUnknownException("rollbackTransaction");
  }
}

bool ClientService::prepareCommitTransaction() {
  try {
    return m_client.prepareCommitTransaction(m_connId, m_pendingTXAttrs,
        m_token);
    clearPendingTransactionAttrs();
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("prepareCommitTransaction", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("prepareCommitTransaction", tpe);
  } catch (const TException& te) {
    handleTException("prepareCommitTransaction", te);
  } catch (const std::exception& stde) {
    handleStdException("prepareCommitTransaction", stde);
  } catch (...) {
    handleUnknownException("prepareCommitTransaction");
  }
  // never reached
  return false;
}

void ClientService::fetchActiveConnections(
    std::vector<thrift::ConnectionProperties>& _return) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.fetchActiveConnections(_return, m_connId, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("fetchActiveConnections", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("fetchActiveConnections", tpe);
  } catch (const TException& te) {
    handleTException("fetchActiveConnections", te);
  } catch (const std::exception& stde) {
    handleStdException("fetchActiveConnections", stde);
  } catch (...) {
    handleUnknownException("fetchActiveConnections");
  }
}

void ClientService::fetchActiveStatements(
    std::map<int32_t, std::string>& _return) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.fetchActiveStatements(_return, m_connId, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("fetchActiveStatements", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("fetchActiveStatements", tpe);
  } catch (const TException& te) {
    handleTException("fetchActiveStatements", te);
  } catch (const std::exception& stde) {
    handleStdException("fetchActiveStatements", stde);
  } catch (...) {
    handleUnknownException("fetchActiveStatements");
  }
}

void ClientService::getServiceMetaData(thrift::ServiceMetaData& _return) {
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getServiceMetaData(_return, m_connId, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getServiceMetaData", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getServiceMetaData", tpe);
  } catch (const TException& te) {
    handleTException("getServiceMetaData", te);
  } catch (const std::exception& stde) {
    handleStdException("getServiceMetaData", stde);
  } catch (...) {
    handleUnknownException("getServiceMetaData");
  }
}

void ClientService::getSchemaMetaData(thrift::RowSet& _return,
    const thrift::ServiceMetaDataCall::type schemaCall,
    thrift::ServiceMetaDataArgs& metadataArgs) {
  metadataArgs.connId = m_connId;
  if (m_token.size() > 0) {
    metadataArgs.token = m_token;
  }
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getSchemaMetaData(_return, schemaCall, metadataArgs);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getSchemaMetaData", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getSchemaMetaData", tpe);
  } catch (const TException& te) {
    handleTException("getSchemaMetaData", te);
  } catch (const std::exception& stde) {
    handleStdException("getSchemaMetaData", stde);
  } catch (...) {
    handleUnknownException("getSchemaMetaData");
  }
}

void ClientService::getIndexInfo(thrift::RowSet& _return,
    thrift::ServiceMetaDataArgs& metadataArgs, const bool unique,
    const bool approximate) {
  metadataArgs.connId = m_connId;
  if (m_token.size() > 0) {
    metadataArgs.token = m_token;
  }
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getIndexInfo(_return, metadataArgs, unique, approximate);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getIndexInfo", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getIndexInfo", tpe);
  } catch (const TException& te) {
    handleTException("getIndexInfo", te);
  } catch (const std::exception& stde) {
    handleStdException("getIndexInfo", stde);
  } catch (...) {
    handleUnknownException("getIndexInfo");
  }
}

void ClientService::getUDTs(thrift::RowSet& _return,
    thrift::ServiceMetaDataArgs& metadataArgs,
    const std::vector<thrift::GFXDType::type>& types) {
  metadataArgs.connId = m_connId;
  if (m_token.size() > 0) {
    metadataArgs.token = m_token;
  }
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getUDTs(_return, metadataArgs, types);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getUDTs", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getUDTs", tpe);
  } catch (const TException& te) {
    handleTException("getUDTs", te);
  } catch (const std::exception& stde) {
    handleStdException("getUDTs", stde);
  } catch (...) {
    handleUnknownException("getUDTs");
  }
}

void ClientService::getBestRowIdentifier(thrift::RowSet& _return,
    thrift::ServiceMetaDataArgs& metadataArgs, const int32_t scope,
    const bool nullable) {
  metadataArgs.connId = m_connId;
  if (m_token.size() > 0) {
    metadataArgs.token = m_token;
  }
  try {
    if (m_hasPendingTXAttrs) {
      flushPendingTransactionAttrs();
    }
    m_client.getBestRowIdentifier(_return, metadataArgs, scope, nullable);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("getBestRowIdentifier", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("getBestRowIdentifier", tpe);
  } catch (const TException& te) {
    handleTException("getBestRowIdentifier", te);
  } catch (const std::exception& stde) {
    handleStdException("getBestRowIdentifier", stde);
  } catch (...) {
    handleUnknownException("getBestRowIdentifier");
  }
}

void ClientService::closeResultSet(const int32_t cursorId) {
  try {
    m_client.closeResultSet(cursorId, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("closeResultSet", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("closeResultSet", tpe);
  } catch (const TException& te) {
    handleTException("closeResultSet", te);
  } catch (const std::exception& stde) {
    handleStdException("closeResultSet", stde);
  } catch (...) {
    handleUnknownException("closeResultSet");
  }
}

void ClientService::cancelStatement(const int32_t stmtId) {
  try {
    m_client.cancelStatement(stmtId, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("cancelStatement", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("cancelStatement", tpe);
  } catch (const TException& te) {
    handleTException("cancelStatement", te);
  } catch (const std::exception& stde) {
    handleStdException("cancelStatement", stde);
  } catch (...) {
    handleUnknownException("cancelStatement");
  }
}

void ClientService::closeStatement(const int32_t stmtId) {
  try {
    m_client.closeStatement(stmtId, m_token);
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("closeStatement", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("closeStatement", tpe);
  } catch (const TException& te) {
    handleTException("closeStatement", te);
  } catch (const std::exception& stde) {
    handleStdException("closeStatement", stde);
  } catch (...) {
    handleUnknownException("closeStatement");
  }
}

void ClientService::close() {
  try {
    BufferedSocketTransport* transport = m_transport.get();
    if (transport != NULL) {
      m_client.closeConnection(m_connId, m_token);
      if (transport->isOpen()) {
        transport->close();
      }
      m_transport.reset();
    }
  } catch (const thrift::GFXDException& sqle) {
    handleGFXDException(sqle);
  } catch (const TTransportException& tte) {
    handleTTransportException("close", tte);
  } catch (const protocol::TProtocolException& tpe) {
    handleTProtocolException("close", tpe);
  } catch (const TException& te) {
    handleTException("close", te);
  } catch (const std::exception& stde) {
    handleStdException("close", stde);
  } catch (...) {
    handleUnknownException("close");
  }
}

ClientServiceHolder* ClientServiceHolder::s_instance =
    new ClientServiceHolder();

ClientServiceHolder::ClientServiceHolder() :
    m_services(), m_servicesLock(), m_numServices(0) {
}

void* ClientServiceHolder::registerInstance(ClientService& service) {
  boost::lock_guard<boost::mutex> sync(m_servicesLock);

  const uint32_t serviceId = ++m_numServices;
  std::vector<boost::shared_ptr<ClientService> > list;
  boost::shared_ptr<ClientService> servicePtr(&service);
  list.push_back(servicePtr);
  service.m_serviceId = serviceId;
  return &(m_services[serviceId] = list);
}

void ClientServiceHolder::incrementReferenceCount(void* serviceId) {
  if (serviceId != NULL) {
    // still taking lock to make inc/dec thread-safe though we might consider
    // having per-service mutex instead if its a bottleneck (global lock should
    //   not be a bottleneck given the call is uncommon but needs verification)
    boost::lock_guard<boost::mutex> sync(m_servicesLock);

    service_list& list = *static_cast<service_list*>(serviceId);
    // list should be size > 0 so make a copy of shared_ptr to increase refCount
    list.push_back(list.at(0));
  }
  else {
    // throw an exception
    throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION,
        "Internal error: incrementReferenceCount got null serviceId");
  }
}

void ClientServiceHolder::decrementReferenceCount(void* serviceId) {
  if (serviceId != NULL) {
    // still taking lock to make inc/dec thread-safe though we might consider
    // having per-service mutex instead if its a bottleneck (global lock should
    //   not be a bottleneck given the call is uncommon but needs verification)
    boost::lock_guard<boost::mutex> sync(m_servicesLock);

    service_list& list = *static_cast<service_list*>(serviceId);
    // list should be size > 0 so remove a shared_ptr from list
    const size_t numRefs = list.size();
    if (numRefs > 0) {
      if (numRefs == 1) {
        // list will be empty so remove from map
        m_services.erase(list.at(0)->m_serviceId);
      }
      list.pop_back();
    }
    else {
      // throw an exception
      throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION,
          "Internal error: decrementReferenceCount zero refCount");
    }
  }
  else {
    // throw an exception
    throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION,
        "Internal error: decrementReferenceCount got null serviceId");
  }
}

boost::shared_ptr<ClientService> ClientServiceHolder::getService(
    const void* serviceId) {
  if (serviceId != NULL) {
    boost::lock_guard<boost::mutex> sync(m_servicesLock);

    const service_list& list = *static_cast<const service_list*>(serviceId);
    // list should be size > 0
    return list.at(0);
  }
  else {
    // throw an exception
    throw GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION,
        "Internal error: getService got null serviceId");
  }
}

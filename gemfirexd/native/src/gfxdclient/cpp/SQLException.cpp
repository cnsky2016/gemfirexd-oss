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
 * SQLException.cpp
 *
 *      Author: swale
 */

#include "SQLException.h"
#include "Utils.h"
#include "impl/InternalUtils.h"

#include "thrift/gfxd_struct_GFXDException.h"

extern "C" {
#  ifdef __GNUC__
#    include <execinfo.h>
#  endif
}

using namespace com::pivotal::gemfirexd::client;

SQLException::SQLException(const char* file, int line,
    const std::string& reason, const SQLState& state, SQLException* next) :
    m_reason(reason), m_state(state.getSQLState()),
    m_severity((int32_t)state.getSeverity()), m_next(next),
    m_file(file), m_line(line) {
  init();
}

SQLException::SQLException(const char* file, int line, const SQLState& state,
    const std::string& reason) :
    m_reason(reason), m_state(state.getSQLState()),
    m_severity((int32_t)state.getSeverity()), m_next(NULL),
    m_file(file), m_line(line) {
  init();
}

SQLException::SQLException(const char* file, int line, const char* sqlState,
    const ExceptionSeverity::type severity, const std::string& reason) :
    m_reason(reason), m_state(sqlState), m_severity(severity), m_next(NULL),
    m_file(file), m_line(line) {
  init();
}

SQLException::SQLException(const char* file, int line,
    const thrift::GFXDException& gfxdException) :
    m_reason(gfxdException.exceptionData.reason),
    m_state(gfxdException.exceptionData.sqlState),
    m_severity(gfxdException.exceptionData.severity), m_next(NULL),
    m_file(file), m_line(line) {
  initNextException(gfxdException.nextExceptions);
  init();
}

SQLException::SQLException(const char* file, int line,
    const std::exception& stde) :
    m_reason(stde.what()), m_state(SQLState::UNKNOWN_EXCEPTION.getSQLState()),
    m_severity(ExceptionSeverity::SESSION_SEVERITY), m_next(NULL),
    m_file(file), m_line(line) {
  init();
}

SQLException::SQLException(const SQLException& other) :
    m_reason(other.m_reason), m_state(other.m_state),
    m_severity(other.m_severity), m_next(NULL),
    m_file(other.m_file), m_line(other.m_line) {
#ifdef __GNUC__
  copyStack(other.m_stack, other.m_stackSize);
#endif
  initNextException(other);
}

SQLException* SQLException::clone() const {
  return new SQLException(*this);
}

void SQLException::copyStack(void* const * stack, size_t stackSize) {
  if (stack != NULL && stackSize > 0) {
    for (size_t i = 0; i < stackSize; i++) {
      m_stack[i] = stack[i];
    }
  }
  m_stackSize = stackSize;
}

void SQLException::init() {
  // TODO: Add stack handling for Windows
#ifdef __GNUC__
  // get the stack trace; only get the frames here for efficiency
  // while the names will be gotten in getStackTrace()
  m_stackSize = ::backtrace(m_stack, STACK_MAX_SIZE);
#endif
}

void SQLException::initNextException(
    const std::vector<thrift::GFXDExceptionData>& nextExceptions) {
  if (nextExceptions.size() > 0) {
    SQLException* next = NULL;
    SQLException* current;
    for (std::vector<thrift::GFXDExceptionData>::const_reverse_iterator iter =
        nextExceptions.rbegin(); iter != nextExceptions.rend(); ++iter) {
      current = createNextException(*iter);
      current->m_next = next;
      next = current;
    }
    m_next = next;
  } else {
    m_next = NULL;
  }
}

void SQLException::initNextException(const SQLException& other) {
  // re-create the next chain
  const SQLException* onext = &other;
  SQLException* next = this;
  while ((onext = onext->m_next) != NULL) {
    next->m_next = createNextException(onext->m_file, onext->m_line,
        onext->m_reason, onext->m_state.c_str(), onext->m_severity
#ifdef __GNUC__
        , onext->m_stack, onext->m_stackSize
#endif
        );
    next = next->m_next;
  }
}

std::ostream& SQLException::printStackTrace(std::ostream& out) const {
  toString(out);
  out << _GFXD_NEWLINE;
#ifdef __GNUC__
  char** stackStrings;
  const size_t skip = skipFrames();
  if (m_stack != NULL && m_stackSize > skip) {
    if ((stackStrings = ::backtrace_symbols(m_stack, m_stackSize)) != NULL) {
      impl::FreePointer freeStrings(stackStrings);
      std::string stackStr, function;
      size_t begin, end;
      char* demangledName;
      for (size_t i = skip; i < m_stackSize; i++) {
        demangledName = NULL;
        stackStr.assign(stackStrings[i]);

        // locate the mangled name after the parentheses and address
        begin = stackStr.find('(');
        if (begin != std::string::npos) {
          // search for the end
          if ((end = stackStr.find('+', begin + 2)) != std::string::npos) {
            function.assign(stackStr.substr(begin + 1, end - begin - 1));
          } else {
            function.assign(stackStr.substr(begin + 1));
          }
          // demangle the name
          demangledName = Utils::gnuDemangledName(function.c_str());
        }
        if (demangledName != NULL) {
          impl::FreePointer freeName(demangledName);
          out << "\tat " << stackStr.substr(0, begin + 1) << demangledName;
          if (end != std::string::npos) {
            out << stackStr.substr(end);
          }
        } else {
          out << "\tat " << stackStr;
        }
        out << _GFXD_NEWLINE;
      }
    }
  }
#endif
  return out;
}

void SQLException::toString(std::ostream& out) const {
  out << getName() << " SQLSTATE=" << m_state << " SEVERITY=" << m_severity
      << ": " << m_reason << _GFXD_NEWLINE_STR "\tat " << m_file << ':'
      << m_line;
}

SQLException::~SQLException() throw () {
  SQLException* next = m_next;
  SQLException* pnext;
  while (next != NULL) {
    // iteratively go at the end and start deleting/clearing
    pnext = next->m_next;
    next->m_next = NULL;
    delete next;
    next = pnext;
  }
}

SQLWarning::SQLWarning(const char* file, int line, const std::string& reason,
    const SQLState& state, SQLWarning* next) :
    SQLException(file, line, reason, state, next) {
}

SQLWarning::SQLWarning(const char* file, int line, const SQLState& state,
    const std::string& reason) :
    SQLException(file, line, state, reason) {
}

const SQLWarning* SQLWarning::getNextWarning() const throw () {
  const SQLException* next = getNextException();
  return next != NULL ? dynamic_cast<const SQLWarning*>(next) : NULL;
}

SQLWarning::SQLWarning(const SQLWarning& other) :
    SQLException(other.m_file, other.m_line, other.m_reason,
        other.m_state.c_str(), other.m_severity
#ifdef __GNUC__
        , other.m_stack, other.m_stackSize
#endif
        ) {
  // virtual call from inside here is fine since this is after
  // base class object construction has been completed
  initNextException(other);
}

SQLException* SQLWarning::clone() const {
  return new SQLWarning(*this);
}

std::ostream& operator<<(std::ostream& out, const SQLException& sqle) {
  sqle.toString(out);
  return out;
}

std::ostream& operator<<(std::ostream& out, _SqleStack s) {
  return s.m_sqle.printStackTrace(out);
}

std::ostream& operator<<(std::ostream& out, const std::exception& stde) {
  return Utils::printFullMessage(stde, out);
}

std::ostream& operator<<(std::ostream& out, _StdeStack s) {
  std::string reason;
  Utils::printFullMessage(s.m_stde, reason);
  return GET_SQLEXCEPTION(SQLState::UNKNOWN_EXCEPTION, reason).printStackTrace(
      out);
}

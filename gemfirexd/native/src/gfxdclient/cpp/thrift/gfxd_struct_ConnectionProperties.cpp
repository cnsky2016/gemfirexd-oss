/**
 * Autogenerated by Thrift Compiler (0.9.1)
 *
 * DO NOT EDIT UNLESS YOU ARE SURE THAT YOU KNOW WHAT YOU ARE DOING
 *  @generated
 */

#include <thrift/Thrift.h>
#include <thrift/TApplicationException.h>
#include <thrift/protocol/TProtocol.h>
#include <thrift/transport/TTransport.h>

#include <thrift/cxxfunctional.h>

#include "gfxd_struct_ConnectionProperties.h"

#include <algorithm>

namespace com { namespace pivotal { namespace gemfirexd { namespace thrift {

const char* ConnectionProperties::ascii_fingerprint = "6A786CBFC3F8CC36DBE9A60593D99486";
const uint8_t ConnectionProperties::binary_fingerprint[16] = {0x6A,0x78,0x6C,0xBF,0xC3,0xF8,0xCC,0x36,0xDB,0xE9,0xA6,0x05,0x93,0xD9,0x94,0x86};

uint32_t ConnectionProperties::read(::apache::thrift::protocol::TProtocol* iprot) {

  uint32_t xfer = 0;
  std::string fname;
  ::apache::thrift::protocol::TType ftype;
  int16_t fid;

  xfer += iprot->readStructBegin(fname);

  using ::apache::thrift::protocol::TProtocolException;

  bool isset_connId = false;
  bool isset_clientHostName = false;
  bool isset_clientID = false;

  while (true)
  {
    xfer += iprot->readFieldBegin(fname, ftype, fid);
    if (ftype == ::apache::thrift::protocol::T_STOP) {
      break;
    }
    switch (fid)
    {
      case 1:
        if (ftype == ::apache::thrift::protocol::T_I32) {
          xfer += iprot->readI32(this->connId);
          isset_connId = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 2:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->clientHostName);
          isset_clientHostName = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 3:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->clientID);
          isset_clientID = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 4:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readString(this->userName);
          this->__isset.userName = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      case 5:
        if (ftype == ::apache::thrift::protocol::T_STRING) {
          xfer += iprot->readBinary(this->token);
          this->__isset.token = true;
        } else {
          xfer += iprot->skip(ftype);
        }
        break;
      default:
        xfer += iprot->skip(ftype);
        break;
    }
    xfer += iprot->readFieldEnd();
  }

  xfer += iprot->readStructEnd();

  if (!isset_connId)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_clientHostName)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  if (!isset_clientID)
    throw TProtocolException(TProtocolException::INVALID_DATA);
  return xfer;
}

uint32_t ConnectionProperties::write(::apache::thrift::protocol::TProtocol* oprot) const {
  uint32_t xfer = 0;
  xfer += oprot->writeStructBegin("ConnectionProperties");

  xfer += oprot->writeFieldBegin("connId", ::apache::thrift::protocol::T_I32, 1);
  xfer += oprot->writeI32(this->connId);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("clientHostName", ::apache::thrift::protocol::T_STRING, 2);
  xfer += oprot->writeString(this->clientHostName);
  xfer += oprot->writeFieldEnd();

  xfer += oprot->writeFieldBegin("clientID", ::apache::thrift::protocol::T_STRING, 3);
  xfer += oprot->writeString(this->clientID);
  xfer += oprot->writeFieldEnd();

  if (this->__isset.userName) {
    xfer += oprot->writeFieldBegin("userName", ::apache::thrift::protocol::T_STRING, 4);
    xfer += oprot->writeString(this->userName);
    xfer += oprot->writeFieldEnd();
  }
  if (this->__isset.token) {
    xfer += oprot->writeFieldBegin("token", ::apache::thrift::protocol::T_STRING, 5);
    xfer += oprot->writeBinary(this->token);
    xfer += oprot->writeFieldEnd();
  }
  xfer += oprot->writeFieldStop();
  xfer += oprot->writeStructEnd();
  return xfer;
}

void swap(ConnectionProperties &a, ConnectionProperties &b) {
  using ::std::swap;
  swap(a.connId, b.connId);
  swap(a.clientHostName, b.clientHostName);
  swap(a.clientID, b.clientID);
  swap(a.userName, b.userName);
  swap(a.token, b.token);
  swap(a.__isset, b.__isset);
}

}}}} // namespace

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
package com.gemstone.gemfire.management.internal;

import java.io.Serializable;

import javax.management.Attribute;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.ReflectionException;

import com.gemstone.gemfire.cache.execute.FunctionAdapter;
import com.gemstone.gemfire.cache.execute.FunctionContext;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.InternalEntity;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;

/**
 * A generic function to act as a conduit between Managing Node and Managed
 * nodes.
 * 
 * The direction of request flow is from Managing Node to Managing Node.
 * 
 * The following methods are executed at Managed node on behalf of the proxy.
 * 
 * 1) All setter methods 2) All operations 3) addNotificationListener 4)
 * removeNotificationListener 5) getNotificationInfo
 * 
 * @author rishim
 * 
 */

public class ManagementFunction extends FunctionAdapter implements InternalEntity{

  /**
   * 
   */
  private static final long serialVersionUID = 1L;

  /**
   * Platform MBean server.
   */
  private MBeanServer mbeanServer = MBeanJMXAdapter.mbeanServer;

  /**
   * Notification hub instance
   */
  private NotificationHub notificationHub;

  /**
   * logger
   */
  private LogWriterI18n logger = InternalDistributedSystem.getLoggerI18n();

  /**
   * Public constructor
   * 
   * @param notifHub
   */
  public ManagementFunction(NotificationHub notifHub) {
    this.notificationHub = notifHub;
  }

  /**
   * Actual function execution. It delegates task at managed node according to
   * the request received.
   * 
   * If any exception is encountered it will set the result to UNDEFINED
   */
  public void execute(FunctionContext fc) {

    boolean executedSuccessfully = false;
    
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();


    Object[] functionArguments = (Object[]) fc.getArguments();

    ObjectName objectName = (ObjectName) functionArguments[0];

    String methodName = (String) functionArguments[1];

    String[] signature = (String[]) functionArguments[2];

    Object[] args = (Object[]) functionArguments[3];
    String memberName = (String) functionArguments[4];

    Object returnObj = null;

    try {

      final int nargs = (args == null) ? 0 : args.length;

      if (methodName.startsWith("set") && methodName.length() > 3 && nargs == 1) {

        Attribute attr = new Attribute(methodName.substring(3), args[0]);
        mbeanServer.setAttribute(objectName, attr);
        fc.getResultSender().lastResult((Serializable) null);

      } else if (methodName.equals("addNotificationListener")) {
        notificationHub.addHubNotificationListener(memberName, objectName);
        fc.getResultSender().lastResult(
            (Serializable) ManagementConstants.UNDEFINED);

      } else if (methodName.equals("removeNotificationListener")) {
        notificationHub.removeHubNotificationListener(memberName, objectName);
        fc.getResultSender().lastResult(
            (Serializable) ManagementConstants.UNDEFINED);

      } else if (methodName.equals("getNotificationInfo")) {
        fc.getResultSender().lastResult(mbeanServer.getMBeanInfo(objectName));

      } else {
        returnObj = mbeanServer.invoke(objectName, methodName, args, signature);
        fc.getResultSender().lastResult((Serializable) returnObj);
      }

      executedSuccessfully = true;

    } catch (InstanceNotFoundException e) {
        if (cache != null && !cache.isClosed()) {
          sendException(e, fc);
        }
    } catch (ReflectionException e) {
      sendException(e, fc);
    } catch (MBeanException e) {
      sendException(e, fc);
    } catch (NullPointerException e) {
      sendException(e, fc);
    } catch (Exception e) {
      sendException(e, fc);
    } finally {
      if (!executedSuccessfully) {
        if (cache == null || (cache != null && cache.isClosed())) {
          Exception e = new Exception(ManagementStrings.MEMBER_IS_SHUTTING_DOWN
              .toLocalizedString());
          sendException(e, fc);
          return; // member is closing or invalid member
        }

      }
    }

  }

  public String getId() {
    return ManagementConstants.MGMT_FUNCTION_ID;
  }
  
  private void sendException(Exception e, FunctionContext fc){
    if (logger.fineEnabled()) {
      logger.fine(ManagementStrings.MANAGEMENT_FUNCTION_COULD_NOT_EXECUTE
          .toLocalizedString());
      logger.fine(e.getMessage());
    }
    fc.getResultSender().sendException(e);
  }

}
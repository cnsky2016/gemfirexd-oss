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
package com.gemstone.gemfire.distributed.internal;

import com.gemstone.gemfire.distributed.DistributedSystem;
import java.util.*;

import util.TestException;

/**
 * A little class for testing the {@link LocalDistributionManager}
 */
public class LDM {

  public static void main(String[] args) throws Exception {
    Properties props = new Properties();
    props.setProperty("locators", "localhost[31576]");
    props.setProperty("mcastPort", "0");
    props.setProperty("logLevel", "config");
    InternalDistributedSystem system = (InternalDistributedSystem)
      DistributedSystem.connect(props);
    DM dm = system.getDistributionManager();

    DistributionMessage message = new HelloMessage();
    dm.putOutgoing(message);

    system.getLogWriter().info("Waiting 5 seconds for message");

    try {
      Thread.sleep(5 * 1000);

    } catch (InterruptedException ex) {
      throw new TestException("interrupted");
    }

    system.disconnect();
  }

  static class HelloMessage extends SerialDistributionMessage {

    public HelloMessage() { }   // for Externalizable
    @Override
    public void process(DistributionManager dm) {
      dm.getLoggerI18n().convertToLogWriter().severe("Hello World");
    }
    public int getDSFID() {
      return NO_FIXED_ID;
    }
  }

}

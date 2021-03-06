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
package com.gemstone.gemfire.internal.cache.tier.sockets;

import java.io.IOException;
import java.util.Properties;

import com.gemstone.gemfire.cache.AttributesFactory;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.NoSubscriptionServersAvailableException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.Scope;
import com.gemstone.gemfire.cache.client.NoAvailableServersException;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.client.internal.PoolImpl;
import com.gemstone.gemfire.cache.server.CacheServer;
import com.gemstone.gemfire.cache30.CacheTestCase;
import com.gemstone.gemfire.internal.AvailablePortHelper;

import dunit.Host;
import dunit.SerializableRunnable;
import dunit.VM;

public class DurableClientBug39997DUnitTest extends CacheTestCase {

  private static final long serialVersionUID = -2712855295338732543L;

  public DurableClientBug39997DUnitTest(String name) {
    super(name);
  }
  
  public void testNoServerAvailableOnStartup() {
    Host host = Host.getHost(0);
    VM vm0 = host.getVM(0);
    VM vm1 = host.getVM(1);

    final String hostName = getServerHostName(host);
    final int port = AvailablePortHelper.getRandomAvailableTCPPort();
    vm0.invoke(new SerializableRunnable("create cache") {
      public void run() {
        getSystem(getClientProperties());
        PoolImpl p = (PoolImpl)PoolManager.createFactory()
        .addServer(hostName, port)
        .setSubscriptionEnabled(true)
        .setSubscriptionRedundancy(0)
        .create("DurableClientReconnectDUnitTestPool");
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.LOCAL);
        factory.setPoolName(p.getName());
        Cache cache = getCache();
        Region region1 = cache.createRegion("region", factory.create());
        cache.readyForEvents();

        try {
          region1.registerInterest("ALL_KEYS");
          fail("Should have received an exception trying to register interest");
        } catch(NoSubscriptionServersAvailableException expected) {
          //this is expected
        }
      }
    });
  
    vm1.invoke(new SerializableRunnable() {
      public void run() {
        Cache cache = getCache();
        AttributesFactory factory = new AttributesFactory();
        factory.setScope(Scope.DISTRIBUTED_ACK);
        cache.createRegion("region", factory.create());
        CacheServer server = cache.addCacheServer();
        server.setPort(port);
        try {
          server.start();
        } catch (IOException e) {
          fail("couldn't start server", e);
        }
      }
    });

    vm0.invoke(new SerializableRunnable() {
      public void run() {
        Cache cache = getCache();
        final Region region = cache.getRegion("region");
        waitForCriterion(new WaitCriterion() {

          public String description() {
            return "Wait for register interest to succeed";
          }

          public boolean done() {
            try { 
              region.registerInterest("ALL_KEYS");
            } catch (NoSubscriptionServersAvailableException e) {
              return false;
            }
            return true;
          }
          
        }, 30000, 1000, true);
      }
    });
  }

  public Properties getClientProperties() {
    Properties props = new Properties();
    props.setProperty("mcast-port", "0");
    props.setProperty("durable-client-id", "my_id");
    return props;
  }
}

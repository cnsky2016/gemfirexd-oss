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

import com.gemstone.gemfire.cache.client.PoolFactory;
import com.gemstone.gemfire.cache.client.PoolManager;

import dunit.DistributedTestCase;
import dunit.Host;

/**
 * @author dsmith
 * @since 5.7
 *
 * Test reconnecting a durable client that is using
 * the locator to discover its servers
 */
public class DurableClientReconnectAutoDUnitTest extends
    DurableClientReconnectDUnitTest {

  public static void caseSetUp() throws Exception {
    DistributedTestCase.disconnectAllFromDS();
  }
 
  public DurableClientReconnectAutoDUnitTest(String name) {
    super(name);
  }
  
  public void testDurableReconnectSingleServerWithZeroConnPerServer() {
    //do nothing, this test doesn't make sense with the locator
  }

  public void testDurableReconnectSingleServer() throws Exception {
    //do nothing, this test doesn't make sense with the locator
  }
  
  protected PoolFactory getPoolFactory() {
    Host host = Host.getHost(0);
    PoolFactory factory = PoolManager.createFactory()
    .addLocator(getServerHostName(host), getDUnitLocatorPort());
    return factory;
  }

}

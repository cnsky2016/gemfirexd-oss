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
package com.gemstone.gemfire.internal.cache;

import java.io.File;
import java.util.Properties;

import com.gemstone.gemfire.CancelException;
import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.client.ClientNotReadyException;

/**
 * <code>RegionFactoryImpl</code> extends RegionFactory
 * adding {@link RegionShortcut} support.
 * @since 6.5
 */

public class RegionFactoryImpl<K,V> extends RegionFactory<K,V>
{
  public RegionFactoryImpl(GemFireCacheImpl cache) {
    super(cache);
  }

  public RegionFactoryImpl(GemFireCacheImpl cache, RegionShortcut pra) {
    super(cache, pra);
  }

  public RegionFactoryImpl(GemFireCacheImpl cache, RegionAttributes ra) {
    super(cache, ra);
  }

  public RegionFactoryImpl(GemFireCacheImpl cache, String regionAttributesId) {
    super(cache, regionAttributesId);
  }
  
}

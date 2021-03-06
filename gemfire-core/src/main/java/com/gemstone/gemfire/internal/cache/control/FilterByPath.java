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
package com.gemstone.gemfire.internal.cache.control;

import java.util.HashSet;
import java.util.Set;

import com.gemstone.gemfire.cache.Region;

/**
 * @author dsmith
 *
 */
public class FilterByPath implements RegionFilter {
  
  private final Set<String> included;
  private final Set<String> excluded;
  
  public FilterByPath(Set<String> included, Set<String> excluded) {
    super();
    if (included != null) {
      this.included = new HashSet<String>();
      for (String regionName : included) {
        this.included.add((!regionName.startsWith("/")) ? ("/" + regionName) : regionName);
      }
    }else{
      this.included = null;
    }
    if (excluded != null) {
      this.excluded = new HashSet<String>();
      for (String regionName : excluded) {
        this.excluded.add((!regionName.startsWith("/")) ? ("/" + regionName) : regionName);
      }
    }else{
      this.excluded = null;
    }
  }


  public boolean include(Region<?, ?> region) {
    String fullPath = region.getFullPath();
    if(included != null) {
      return included.contains(fullPath);
    }
    if(excluded != null) {
      return !excluded.contains(fullPath);
    }
    
    return true;
  }

}

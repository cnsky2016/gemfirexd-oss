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

package com.gemstone.gemfire.internal.lang;

/**
 * The Initable interface defines a contract for implementing classes who's Object instances can be initialized after
 * construction.
 * <p/>
 * @author John Blum
 * @since 6.8
 */
public interface Initable {

  /**
   * Called to perform additional initialization logic after construction of the Object instance.  This is necessary
   * in certain cases to prevent escape of the "this" reference during construction by subclasses needing to
   * instantiate other collaborators or starting of additional services, like Threads, and so on.
   */
  public void init();

}

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

package com.gemstone.gemfire.cache.query;


/**
 * Thrown when an attribute or method name could not be resolved during query
 * execution because no matching method or field could be found.
 *
 * @author      Eric Zoerner
 * @since 4.0
 */

public class NameNotFoundException extends NameResolutionException {
private static final long serialVersionUID = 4827972941932684358L;
  /**
   * Constructs instance of ObjectNameNotFoundException with error message
   * @param message the error message
   */
  public NameNotFoundException(String message) {
    super(message);
  }
  
  /**
   * Constructs instance of ObjectNameNotFoundException with error message and cause
   * @param message the error message
   * @param cause a Throwable that is a cause of this exception
   */
  public NameNotFoundException(String message, Throwable cause) {
    super(message, cause);
  }
  
}

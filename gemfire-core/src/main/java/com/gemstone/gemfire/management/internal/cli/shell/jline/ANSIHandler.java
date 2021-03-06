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
package com.gemstone.gemfire.management.internal.cli.shell.jline;

import org.springframework.shell.core.JLineLogHandler;

import jline.ANSIBuffer;

/**
 * Overrides jline.History to add History without newline characters.
 * 
 * @author Abhishek Chaudhari
 * @since 7.0
 */
public class ANSIHandler {
  private static ANSIHandler instance;

  private boolean isAnsiEnabled;
  
  public ANSIHandler(boolean isAnsiEnabled) {
    this.isAnsiEnabled = isAnsiEnabled;
  }
  
  public static ANSIHandler getInstance(boolean isAnsiSupported) {
    if (instance == null) {
      instance = new ANSIHandler(isAnsiSupported);
    }
    return instance;
  }
  
  public boolean isAnsiEnabled() {
    return isAnsiEnabled;
  }

  public String decorateString(String input, ANSIStyle...styles) {
    String decoratedInput = input;
    
    if (isAnsiEnabled()) {
      ANSIBuffer ansiBuffer = JLineLogHandler.getANSIBuffer();

      for (ANSIStyle ansiStyle : styles) {
        switch (ansiStyle) {
        case RED:
          ansiBuffer.red(input);
          break;
        case BLUE:
          ansiBuffer.blue(input);
          break;
        case GREEN:
          ansiBuffer.green(input);
          break;
        case BLACK:
          ansiBuffer.black(input);
          break;
        case YELLOW:
          ansiBuffer.yellow(input);
          break;
        case MAGENTA:
          ansiBuffer.magenta(input);
          break;
        case CYAN:
          ansiBuffer.cyan(input);
          break;
        case BOLD:
          ansiBuffer.bold(input);
          break;
        case UNDERSCORE:
          ansiBuffer.underscore(input);
          break;
        case BLINK:
          ansiBuffer.blink(input);
          break;
        case REVERSE:
          ansiBuffer.reverse(input);
          break;
        default:
          break;
        }
      }
      
      decoratedInput = ansiBuffer.toString();
    }
    
    return decoratedInput;
  }
  
  public static enum ANSIStyle {
    RED, 
    BLUE, 
    GREEN, 
    BLACK, 
    YELLOW, 
    MAGENTA, 
    CYAN, 
    BOLD, 
    UNDERSCORE, 
    BLINK, 
    REVERSE;   
  }
}

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
package quickstart;

/**
 * Tests the functionality of the Querying quickstart example.
 *
 * @author Kirk Lund
 * @since 4.1.1
 */
public class QueryingTest extends QuickstartTestCase {

  protected ProcessWrapper process;

  public QueryingTest(String name) {
    super(name);
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    if (this.process != null) this.process.destroy();
  }
  
  public void testQuerying() throws Exception {
    // start up Querying
    getLogWriter().info("[testQuerying] start up Querying");
    this.process = new ProcessWrapper(Querying.class);
    this.process.execute(createProperties());

    getLogWriter().info("[testQuerying] sending four line feeds");
    this.process.waitForOutputToMatch("Press Enter to continue to next query...");
    this.process.sendInput();
    this.process.waitForOutputToMatch("Press Enter to continue to next query...");
    this.process.sendInput();
    this.process.waitForOutputToMatch("Press Enter to continue to next query...");
    this.process.sendInput();
    this.process.waitForOutputToMatch("Press Enter to continue to next query...");
    this.process.sendInput();
    this.process.waitForOutputToMatch("Closing the cache and disconnecting.");
    getLogWriter().info("[testQuerying] joining to Querying");
    this.process.waitFor();
    printProcessOutput(this.process);
    
    // validate output from process
    assertOutputMatchesGoldenFile(this.process.getOutput(), "Querying.txt");
  }
}

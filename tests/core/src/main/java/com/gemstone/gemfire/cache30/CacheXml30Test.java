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
package com.gemstone.gemfire.cache30;

import com.gemstone.gemfire.cache.*;
import com.gemstone.gemfire.cache.client.PoolManager;
import com.gemstone.gemfire.cache.util.BridgeClient;
import com.gemstone.gemfire.cache.util.ObjectSizer;
import com.gemstone.gemfire.distributed.internal.*;
import com.gemstone.gemfire.internal.AvailablePortHelper;
import com.gemstone.gemfire.internal.cache.DiskWriteAttributesImpl;
import com.gemstone.gemfire.internal.cache.xmlcache.*;
import org.xml.sax.*;
import java.io.*;
import java.util.*;
import dunit.DistributedTestCase;

/**
 * Tests the functionality of loading a declarative caching file when
 * a <code>Cache</code> is {@link CacheFactory#create created}.  The
 * fact that it is a subclass of {@link RegionTestCase} allows us to
 * take advantage of methods like {@link #getCache}.
 *
 * <P>
 *
 * Note that this class only tests the XML syntax allowed in GemFire
 * 3.X (3.0, 3.2, 3.5).  Tests for syntax added in subsequent releases
 * is tested by subclasses of this class.
 *
 * @author David Whitlock
 * @since 3.0
 */
public class CacheXml30Test extends CacheTestCase {

  /** The file used by this test (method) to initialize the cache */
  private File xmlFile;
  
  /** set this to false if a test needs a non-loner distributed system */
  static boolean lonerDistributedSystem = true;

  ////////  Constructors

  public CacheXml30Test(String name) {
    super(name);
  }


  //////// Test Lifecycle Methods

  public void setUp() throws Exception {
    super.setUp();
    disconnectAllFromDS();
  }

  public void tearDown2() throws Exception {
    this.xmlFile = null;    
    super.tearDown2();
    disconnectAllFromDS();
  }

  ////////  Helper methods

  /**
   * The version of GemFire tested by this class.  This method should
   * be overridden by subclasses.
   *
   * @see CacheXml#VERSION_3_0
   */
  protected String getGemFireVersion() {
    return CacheXml.VERSION_3_0;
  }

  /**
   * Sets the file used by this test to initialize the cache
   */
  protected void setXmlFile(File xmlFile) {
    this.xmlFile = xmlFile;
  }

  /**
   * Finds an XML file with the given name.  Looks in $JTESTS.
   */
  protected File findFile(String fileName) {
    File dir = new File(System.getProperty("JTESTS"));
    if (!dir.exists()) {
      String s = "Could not find JTESTS";
      throw new IllegalStateException(s);
    }

    String[] subdirs = { "com", "gemstone", "gemfire", "cache30" };
    for (int i = 0; i < subdirs.length; i++) {
      dir = new File(dir, subdirs[i]);
      if (!dir.exists()) {
        String s = "Could not find " + dir;
        throw new IllegalStateException(s);
      }
    }

    File file = new File(dir, fileName);
//     if (!file.exists()) {
//       String s = "Could not find " + file;
//       throw new IllegalStateException(s);
//     }

    return file;
  }

  public Properties getDistributedSystemProperties() {
    Properties props = super.getDistributedSystemProperties();
    if (this.xmlFile != null) {
      props.setProperty(DistributionConfig.CACHE_XML_FILE_NAME,
                        this.xmlFile.toString());
    }

    // make it a loner
    if (lonerDistributedSystem) {
      props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
      props.setProperty(DistributionConfig.LOCATORS_NAME, "");
    }

    return props;
  }

  /**
   * Uses the {@link CacheXmlGenerator} to generate an XML file from
   * the given {@link CacheCreation}.  It then creates a {@link Cache}
   * from the XML and asserts that it is the same as the cache
   * described in the <code>CacheCreation</code>.
   */
  protected void testXml(CacheCreation creation) {
    testXml(creation, true);
  }
  protected void testXml(CacheCreation creation, boolean checkSame) {

    File dir = new File("XML_" + this.getGemFireVersion());
    dir.mkdirs();
    File file = new File(dir, this.getUniqueName() + ".xml");

    final boolean useSchema = false;
    final String version = getGemFireVersion();
    try {
      PrintWriter pw = new PrintWriter(new FileWriter(file), true);
      CacheXmlGenerator.generate(creation, pw, useSchema, version);
      pw.close();

    } catch (IOException ex) {
      String s = "While generating XML";
      fail(s, ex);
    }

    setXmlFile(file);

    boolean client = creation instanceof ClientCacheCreation;
    Cache cache = getCache(client);

    try {
      if (checkSame && !creation.sameAs(cache)) {
        StringWriter sw = new StringWriter();
        CacheXmlGenerator.generate(creation, new PrintWriter(sw, true),
            useSchema, version);
        CacheXmlGenerator.generate(cache, new PrintWriter(sw, true),
            useSchema, version);
        fail(sw.toString());
      }
    } catch (RuntimeException re) {
      StringWriter sw = new StringWriter();
      CacheXmlGenerator.generate(creation, new PrintWriter(sw, true),
          useSchema, version);
      CacheXmlGenerator.generate(cache, new PrintWriter(sw, true),
          useSchema, version);
      fail(sw.toString(), re);
    }
  }

  ////////  Test methods

  /**
   * Tests creating a cache with a non-existent XML file
   */
  public void testNonExistentFile() throws IOException {
//    System.out.println("testNonExistentFile - start: " + System.currentTimeMillis());
    File nonExistent = new File(this.getName() + ".xml");
    nonExistent.delete();
//    System.out.println("testNonExistentFile - deleted: " + System.currentTimeMillis());
    setXmlFile(nonExistent);
//    System.out.println("testNonExistentFile - set: " + System.currentTimeMillis());

    try {
      getCache();
      fail("Should have thrown a CacheXmlException");

    } catch (CacheXmlException ex) {
//      System.out.println("testNonExistentFile - caught: " + System.currentTimeMillis());
      // pass...
    }
  }

  /**
   * Tests creating a cache with a XML file that is a directory
   */
  public void testXmlFileIsDirectory() {
    File dir = new File(this.getName() + "dir");
    dir.mkdirs();
    dir.deleteOnExit();
    setXmlFile(dir);

    try {
      getCache();
      fail("Should have thrown a CacheXmlException");

    } catch (CacheXmlException ex) {
      // pass...
    }
  }

  /**
   * Tests creating a cache with the default lock-timeout, lock-lease,
   * and search-timeout.
   */
  public void testDefaultCache() {
    CacheCreation cache = new CacheCreation();

    testXml(cache);
  }

  /**
   * Tests creating a cache with non-default lock-timeout, lock-lease,
   * and search-timeout.
   */
  public void testNonDefaultCache() {
    CacheCreation cache = new CacheCreation();
    cache.setLockTimeout(42);
    cache.setLockLease(43);
    cache.setSearchTimeout(44);

    if (getGemFireVersion().compareTo(CacheXml.VERSION_4_0) >= 0) {
      cache.setCopyOnRead(true);
    }

    testXml(cache);
  }

  /**
   * Tests creating a cache with entries defined in the root region
   */
  public void testEntriesInRootRegion() throws CacheException {
    CacheCreation cache = new CacheCreation();
    RegionCreation root = (RegionCreation)
      cache.createRegion("root", new RegionAttributesCreation(cache));
    root.put("KEY1", "VALUE1");
    root.put("KEY2", "VALUE2");
    root.put("KEY3", "VALUE3");

    testXml(cache);
  }

  /**
   * Tests creating a cache whose keys are constrained
   */
  public void testConstrainedKeys() throws CacheException {
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    attrs.setKeyConstraint(String.class);
    cache.createRegion("root", attrs);

    testXml(cache);
  }

  /**
   * Tests creating a cache with a various {@link
   * ExpirationAttributes}.
   */
  public void testExpirationAttriubutes() throws CacheException {
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    attrs.setStatisticsEnabled(true);

    {
      ExpirationAttributes expire =
        new ExpirationAttributes(42, ExpirationAction.INVALIDATE);
      attrs.setRegionTimeToLive(expire);
    }

    {
      ExpirationAttributes expire =
        new ExpirationAttributes(43, ExpirationAction.DESTROY);
      attrs.setRegionIdleTimeout(expire);
    }

    {
      ExpirationAttributes expire =
        new ExpirationAttributes(44, ExpirationAction.LOCAL_INVALIDATE);
      attrs.setEntryTimeToLive(expire);
    }

    {
      ExpirationAttributes expire =
        new ExpirationAttributes(45, ExpirationAction.LOCAL_DESTROY);
      attrs.setEntryIdleTimeout(expire);
    }

    cache.createRegion("root", attrs);

    testXml(cache);
  }

  /**
   * Tests a cache loader an interesting combination of declarables
   */
  public void testCacheLoaderWithDeclarables() throws CacheException {
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);

    CacheLoaderWithDeclarables loader =
      new CacheLoaderWithDeclarables();
    attrs.setCacheLoader(loader);

    cache.createRegion("root", attrs);

    testXml(cache);
  }

  public static class TestBridgeClient extends BridgeClient {
    public boolean equals(Object obj)
    {
      return obj instanceof BridgeClient; // needed for sameAs comparison
    }
  }
  /**
   * Tests a bridge client and special setCacheWriter behavior on 
   * region attributes
   */
  public void testBridgeClientAsLoader() throws CacheException {
    getSystem();
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    final int[] ports = AvailablePortHelper.getRandomAvailableTCPPorts(1);

    BridgeClient bc = new TestBridgeClient();
    Properties props = new Properties();
    props.setProperty("endpoints", "server=" + 
      DistributedTestCase.getIPLiteral() + ":" + ports[0]);
    bc.init(props);
    
    attrs.setCacheLoader(bc);

    cache.createRegion("root", attrs);

    testXml(cache);
    final Region ro = cache.getRegion("root");
    assertSame(ro.getAttributes().getCacheLoader(), ro.getAttributes().getCacheWriter());
    assertTrue(ro.getAttributes().getCacheLoader() instanceof TestBridgeClient);
    // now that a BridgeLoader creates its own pool make sure it exists
    assertEquals("pools="+PoolManager.getAll(), 1, PoolManager.getAll().size());
  }

  /**
   * Tests a bridge client and special setCacheWriter over-ride behavior on 
   * region attributes
   */
  public void testBridgeClientWriterOverride() throws CacheException {
    getSystem();
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    final int[] ports = AvailablePortHelper.getRandomAvailableTCPPorts(1);

    BridgeClient bc = new TestBridgeClient();
    Properties props = new Properties();
    props.setProperty("endpoints", "server="
      + DistributedTestCase.getIPLiteral() + ":" + ports[0]);
    bc.init(props);
    
    attrs.setCacheLoader(bc);
    attrs.setCacheWriter(new MyTestCacheWriter());

    cache.createRegion("root", attrs);

    testXml(cache);
    
    final Region ro = cache.getRegion("root");
    assertTrue(ro.getAttributes().getCacheLoader() instanceof BridgeClient);
    assertTrue(ro.getAttributes().getCacheWriter() instanceof MyTestCacheWriter);
    // now that a BridgeLoader creates its own pool make sure it exists
    assertEquals("pools="+PoolManager.getAll(), 1, PoolManager.getAll().size());
  }

  
  /**
   * Tests a bridge client and special setCacheLoader over-ride behavior on 
   * region attributes
   */
  public void testBridgeClientLoaderOverride() throws CacheException {
    getSystem();
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    final int[] ports = AvailablePortHelper.getRandomAvailableTCPPorts(1);

    BridgeClient bc = new TestBridgeClient();
    Properties props = new Properties();
    props.setProperty("endpoints", "server=" 
      + DistributedTestCase.getIPLiteral() + ":" + ports[0]);
    bc.init(props);
    
    attrs.setCacheWriter(bc);
    attrs.setCacheLoader(new CacheLoaderWithDeclarables());

    cache.createRegion("root", attrs);

    testXml(cache);
    final Region ro = cache.getRegion("root");
    assertTrue(ro.getAttributes().getCacheWriter() instanceof BridgeClient);
    assertTrue(ro.getAttributes().getCacheLoader() instanceof CacheLoaderWithDeclarables);
    // now that a BridgeLoader creates its own pool make sure it exists
    assertEquals("pools="+PoolManager.getAll(), 1, PoolManager.getAll().size());
  }

  /**
   * Tests a bridge client and special setCacheWriter behavior on 
   * region attributes
   */
  public void testBridgeClientAsWriter() throws CacheException {
    getSystem();
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    final int[] ports = AvailablePortHelper.getRandomAvailableTCPPorts(1);

    BridgeClient bc = new TestBridgeClient();
    Properties props = new Properties();
    props.setProperty("endpoints", "server=" 
      + DistributedTestCase.getIPLiteral() + ":" + ports[0]);
    bc.init(props);
    
    attrs.setCacheWriter(bc);

    cache.createRegion("root", attrs);

    testXml(cache);
    final Region ro = cache.getRegion("root");
    assertSame(ro.getAttributes().getCacheLoader(), ro.getAttributes().getCacheWriter());
    assertTrue(ro.getAttributes().getCacheWriter() instanceof TestBridgeClient);
    // now that a BridgeLoader creates its own pool make sure it exists
    assertEquals("pools="+PoolManager.getAll(), 1, PoolManager.getAll().size());
  }

  /**
   * Tests a cache writer with no parameters
   */
  public void testCacheWriter() throws CacheException {
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);

    CacheWriter writer = new MyTestCacheWriter();
    attrs.setCacheWriter(writer);

    cache.createRegion("root", attrs);

    testXml(cache);
  }

  /**
   * Tests a cache listener with no parameters
   */
  public void testCacheListener() throws CacheException {
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);

    CacheListener listener = new MyTestCacheListener();
    attrs.setCacheListener(listener);

    cache.createRegion("root", attrs);

    testXml(cache);
  }

  /**
   * Tests a region with non-default region attributes
   */
  public void testNonDefaultRegionAttributes() throws CacheException {
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);

    attrs.setScope(Scope.DISTRIBUTED_NO_ACK);
    attrs.setMirrorType(MirrorType.KEYS_VALUES);
    attrs.setInitialCapacity(142);
    attrs.setLoadFactor(42.42f);
    attrs.setStatisticsEnabled(false);

    cache.createRegion("root", attrs);

      testXml(cache);
  }

  /**
   * Tests parsing a malformed XML file
   */
  public void testMalformed() {
    setXmlFile(findFile("malformed.xml"));

    try {
      getCache();
      fail("Should have thrown a CacheXmlException");

    } catch (CacheXmlException ex) {
      assertTrue(ex.getCause() instanceof SAXException);
    }
  }

  /**
   * Tests parsing an XML file with a bad integer
   */
  public void testBadInt() {
    setXmlFile(findFile("badInt.xml"));

    try {
      getCache();
      fail("Should have thrown a CacheXmlException");

    } catch (CacheXmlException ex) {
      Throwable cause = ex.getCause();
      assertNotNull("Expected a cause", cause);
      assertTrue("Didn't expect cause:" + cause + " (a " +
                 cause.getClass().getName() + ")",
                 cause instanceof NumberFormatException);
    }
  }

  /**
   * Tests parsing an XML file with a bad float
   */
  public void testBadFloat() {
    setXmlFile(findFile("badFloat.xml"));

    try {
      getCache();
      fail("Should have thrown a CacheXmlException");

    } catch (CacheXmlException ex) {
      assertTrue(ex.getCause() instanceof NumberFormatException);
    }
  }

  /**
   * Tests parsing an XML file with a bad scope.  This error should be
   * caught by the XML parser.
   */
  public void testBadScope() {
    setXmlFile(findFile("badScope.xml"));

    try {
      getCache();
      fail("Should have thrown a CacheXmlException");

    } catch (CacheXmlException ex) {
      assertTrue(ex.getCause() instanceof SAXException);
    }
  }

  /**
   * Tests parsing an XML file with a non-existent key constraint
   * class.
   */
  public void testBadKeyConstraintClass() {
    setXmlFile(findFile("badKeyConstraintClass.xml"));

    try {
      getCache();
      fail("Should have thrown a CacheXmlException");

    } catch (CacheXmlException ex) {
      assertTrue(ex.getCause() instanceof ClassNotFoundException);
    }
  }

  /**
   * Tests parsing an XML file that specifies a cache listener that is
   * not {@link Declarable}.
   */
  public void testCallbackNotDeclarable() {
    setXmlFile(findFile("callbackNotDeclarable.xml"));

    try {
      getCache();
      fail("Should have thrown a CacheXmlException");

    } catch (CacheXmlException ex) {
      Throwable cause = ex.getCause();
      assertNull(/*"Didn't expect a cause of " + cause + " (a " +
                   cause.getClass().getName() + ")" + " from " + ex, */
                 cause);
    }
  }

  /**
   * Tests parsing an XML file that specifies a cache listener whose
   * constructor throws an {@linkplain TestException exception}.
   */
  public void testCallbackWithException() {
    setXmlFile(findFile("callbackWithException.xml"));

    try {
      getCache();
      fail("Should have thrown a CacheXmlException");

    } catch (CacheXmlException ex) {
      assertTrue(ex.getCause() instanceof TestException);
    }

  }

  /**
   * Tests parsing an XML file that specifies a cache listener that is
   * not a <code>CacheLoader</code>.
   */
  public void testLoaderNotLoader() {
    setXmlFile(findFile("loaderNotLoader.xml"));

    try {
      getCache();
      fail("Should have thrown a CacheXmlException");

    } catch (CacheXmlException ex) {
      Throwable cause = ex.getCause();
      assertNull("Didn't expect a " + cause, cause);
    }
  }

  /**
   * Tests nested regions
   */
  public void testNestedRegions() throws CacheException {
    CacheCreation cache = new CacheCreation();

    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    attrs.setScope(Scope.DISTRIBUTED_NO_ACK);

    RegionCreation root =
      (RegionCreation) cache.createRegion("root", attrs);

    {
      attrs = new RegionAttributesCreation(cache);
      attrs.setScope(Scope.DISTRIBUTED_NO_ACK);
      attrs.setMirrorType(MirrorType.KEYS_VALUES);
      attrs.setInitialCapacity(142);
      attrs.setLoadFactor(42.42f);
      attrs.setStatisticsEnabled(false);

      root.createSubregion("one", attrs);
    }

    {
      attrs = new RegionAttributesCreation(cache);
      attrs.setScope(Scope.DISTRIBUTED_ACK);
      attrs.setMirrorType(MirrorType.KEYS);
      attrs.setInitialCapacity(242);

      Region region = root.createSubregion("two", attrs);

      {
        attrs = new RegionAttributesCreation(cache);
        attrs.setScope(Scope.GLOBAL);
        attrs.setLoadFactor(43.43f);

        region.createSubregion("three", attrs);
      }
    }

      testXml(cache);
  }

  /**
   * Tests whether or not XML attributes can appear in any order.  See
   * bug 30050.
   */
  public void testAttributesUnordered() {
    setXmlFile(findFile("attributesUnordered.xml"));
    getCache();
  }

  /**
   * Tests disk directories
   */
  public void testDiskDirs() throws CacheException {
    CacheCreation cache = new CacheCreation();

    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    File[] dirs = new File[] {
      new File(this.getUniqueName() + "-dir1"),
      new File(this.getUniqueName() + "-dir2")
    };
    for (int i = 0; i < dirs.length; i++) {
      dirs[i].mkdirs();
      dirs[i].deleteOnExit();
    }

    int[] diskSizes = {DiskWriteAttributesImpl.DEFAULT_DISK_DIR_SIZE ,DiskWriteAttributesImpl.DEFAULT_DISK_DIR_SIZE };
    attrs.setDiskDirsAndSize(dirs,diskSizes);
    cache.createRegion("root", attrs);

    testXml(cache);
  }

  /**
   * Tests the <code>overflowThreshold</code> and
   * <code>persistBackup</code> related attributes
   */
  public void testOverflowAndBackup() throws CacheException {
    CacheCreation cache = new CacheCreation();

    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    attrs.setMirrorType(MirrorType.KEYS_VALUES);
    attrs.setPersistBackup(true);

    cache.createRegion("root", attrs);

    testXml(cache);
  }

  /**
   * Tests <code>DiskWriteAttributes</code>
   */
  public void testDiskWriteAttributes() throws CacheException {
    CacheCreation cache = new CacheCreation();
//  Set properties for Asynch writes
    

    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);

    RegionCreation root = (RegionCreation)
      cache.createRegion("root", attrs);

    {
      attrs = new RegionAttributesCreation(cache);
      DiskWriteAttributesFactory dwaf = new DiskWriteAttributesFactory();
      dwaf.setSynchronous(true);  
      attrs.setDiskWriteAttributes(dwaf.create());
      root.createSubregion("sync", attrs);
    }

    {
      attrs = new RegionAttributesCreation(cache);
      DiskWriteAttributesFactory dwaf = new DiskWriteAttributesFactory();
      dwaf.setTimeInterval(123L);
      dwaf.setBytesThreshold(456L);
      attrs.setDiskWriteAttributes(dwaf.create());
      root.createSubregion("async", attrs);
    }

    testXml(cache);
  }

  /**
   * Tests to make sure that the example cache.xml file in the API
   * documentation conforms to the DTD.
   *
   * @since 3.2.1
   */
  public void testExampleCacheXmlFile() throws Exception {
    // Check for old example files
    String dirName = "examples_" + this.getGemFireVersion();
    File dir = findFile(dirName);
    if (dir.exists()) {
      File[] xmlFiles = dir.listFiles(new FilenameFilter() {
          public boolean accept(File dir, String name) {
            return name.endsWith(".xml");
          }
        });
      assertTrue("No XML files in " + dirName, xmlFiles.length > 0);
      for (int i = 0; i < xmlFiles.length; i++) {
        File xmlFile = xmlFiles[i];
        getLogWriter().info("Parsing " + xmlFile);

        FileInputStream fis = new FileInputStream(xmlFile);
        CacheXmlParser.parse(fis, getLogWriter().convertToLogWriterI18n());
      }

    } else {
      // Test the examples that are shipped with this version of the
      // product. 
      Class c = CacheXmlParser.class;
      java.net.URL url =
        c.getProtectionDomain().getCodeSource().getLocation();
      File gemfireJar = new File(url.getPath());
      assertTrue("gemfire.jar (" + gemfireJar + ") doesn't exist???",
                 gemfireJar.exists());
      File lib = gemfireJar.getParentFile();
      File product = lib.getParentFile();
      File docs = new File(product, "docs");
      File japi = new File(docs, "japi");
      File com = new File(japi, "com");
      File gemstone = new File(com, "gemstone");
      File gemfire = new File(gemstone, "gemfire");
      File cache = new File(gemfire, "cache");
      File doc_files = new File(cache, "doc-files");
      File example = new File(doc_files, "example-cache.xml");

      assertTrue("Could not find example cache.xml file: " + example,
                 example.exists());

      FileInputStream fis = new FileInputStream(example);
      CacheXmlParser.parse(fis, getLogWriter().convertToLogWriterI18n());

      File example2 = new File(doc_files, "example2-cache.xml");
      fis = new FileInputStream(example2);
      CacheXmlParser.parse(fis, getLogWriter().convertToLogWriterI18n());    

      File example3 = new File(doc_files, "example3-cache.xml");
      fis = new FileInputStream(example3);
      CacheXmlParser.parse(fis, getLogWriter().convertToLogWriterI18n());    
    }
  }
  
  public void testEvictionLRUEntryAttributes() throws Exception {
    final String rName = getUniqueName();
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    attrs.setEvictionAttributes(EvictionAttributes.createLRUEntryAttributes(80, EvictionAction.LOCAL_DESTROY));
    cache.createRegion(rName, attrs);
    testXml(cache);
  }

  public static class EvictionObjectSizer implements ObjectSizer, Declarable2 {
    Properties props = new Properties();
    public int sizeof(Object o) { return 1; }
    public Properties getConfig()
    {
      if (null==this.props) {
        this.props = new Properties();
      }
      this.props.setProperty("EvictionObjectSizerColor", "blue");
      return this.props;
    }

    public void init(Properties props)
    {
      this.props = props;
    }

    public boolean equals(Object obj)
    {
      if (obj == this){
        return true;
      }
      if (! (obj instanceof EvictionObjectSizer)) {
        return false;
      }
      EvictionObjectSizer other = (EvictionObjectSizer) obj;
      if (! this.props.equals(other.props)) {
        return false;
      }
      return true;
    }
  }
  public void testEvictionLRUMemoryAttributes() throws Exception {
    final String rName = getUniqueName();
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    attrs.setEvictionAttributes(EvictionAttributes.createLRUMemoryAttributes(10, new EvictionObjectSizer()));
    cache.createRegion(rName, attrs);
    testXml(cache);
  }

  public void testEvictionLRUHeapAttributes() throws Exception {
    final String rName = getUniqueName();
    CacheCreation cache = new CacheCreation();
    RegionAttributesCreation attrs = new RegionAttributesCreation(cache);
    attrs.setEvictionAttributes(EvictionAttributes
        .createLRUHeapAttributes(new EvictionObjectSizer(), EvictionAction.LOCAL_DESTROY));
    cache.createRegion(rName, attrs);
    testXml(cache);
  }


  ////////////////////////  Inner Classes  ////////////////////////

  /**
   * A cache listener that is not {@link Declarable}
   *
   * @see #testCallbackNotDeclarable()
   */
  public static class NotDeclarableCacheListener
    extends TestCacheListener {

  }


  public static class TestException extends RuntimeException {
    public TestException() {
      super("Test Exception");
    }
  }

  /**
   * A cache listener whose constructor throws an exception
   *
   * @see #testCallbackWithException()
   */
  public static class ExceptionalCacheListener
    extends TestCacheListener {

    public ExceptionalCacheListener() {
      throw new TestException();
    }
  }


  /**
   * A <code>CacheListener</code> that is
   * <code>Declarable</code>, but not <code>Declarable2</code>.
   */
  public static class MyTestCacheListener
    extends TestCacheListener implements Declarable {

    public void init(Properties props) { }

    public boolean equals(Object o) {
      return o instanceof MyTestCacheListener;
    }
  }

  /**
   * A <code>CacheWriter</code> that is
   * <code>Declarable</code>, but not <code>Declarable2</code>.
   */
  public static class MyTestCacheWriter
    extends TestCacheWriter implements Declarable {

    public void init(Properties props) { }

    public boolean equals(Object o) {
      return o instanceof MyTestCacheWriter;
    }
  }

  /**
   * A <code>TransactionListener</code> that is
   * <code>Declarable</code>, but not <code>Declarable2</code>.
   */
  public static class MyTestTransactionListener
    extends TestTransactionListener implements Declarable {

    public void init(Properties props) { }

    public boolean equals(Object o) {
      return o instanceof MyTestTransactionListener;
    }
  }


  /**
   * A <code>CacheLoader</code> that is <code>Declarable</code> and
   * has some interesting parameters.
   */
  public static class CacheLoaderWithDeclarables
    implements CacheLoader, Declarable2 {

    /** This loader's properties */
    private Properties props;

    /** Was this declarable initialized */
    private boolean initialized = false;

    /**
     * Creates a new loader and initializes its properties
     */
    public CacheLoaderWithDeclarables() {
      this.props = new Properties();
      props.put("KEY1", "VALUE1");
      props.put("KEY2", new TestDeclarable());
    }

    /**
     * Returns whether or not this <code>Declarable</code> was
     * initialized.
     */
    public boolean isInitialized() {
      return this.initialized;
    }

    public void init(Properties props) {
      this.initialized = true;
      assertEquals(this.props, props);
    }

    public Properties getConfig() {
      return this.props;
    }

    public Object load(LoaderHelper helper)
      throws CacheLoaderException {

      fail("Loader shouldn't be invoked");
      return null;
    }

    public boolean equals(Object o) {
      if (o instanceof CacheLoaderWithDeclarables) {
        CacheLoaderWithDeclarables other =
          (CacheLoaderWithDeclarables) o;
        return this.props.equals(other.props);

      } else {
        return false;
      }
    }

    public void close() { }

  }

  public static class TestDeclarable implements Declarable {
    public void init(Properties props) { }

    public boolean equals(Object o) {
      return o instanceof TestDeclarable;
    }
  }

}

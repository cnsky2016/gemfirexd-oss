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
package com.gemstone.gemfire.internal.cache.versions;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import com.gemstone.gemfire.distributed.internal.membership.InternalDistributedMember;
import com.gemstone.gnu.trove.TIntHashSet;

/**
 * A test of the region version holder, where all of the
 * test methods will now build there RVV by applying versions
 * in a random order.
 * 
 * This test also contains some other non deterministic RVV junit tests
 * @author dsmith
 *
 */
public class RegionVersionHolderRandomJUnitTest extends RegionVersionHolderSmallBitSetJUnitTest {
  
  Random random = new Random();
  
  @Override
  protected void setUp() throws Exception {
    super.setUp();
    long seed = System.nanoTime();
//    seed = 1194319178069961L;
    random.setSeed(seed);
    System.out.println("RegionVersionHolderJUnitTest using random seed " + seed);
  }
  
  
  int loopNum = 0;
  /**
   * Rerun all of the tests several times to see if anything fails.
   * @throws InterruptedException
   */
//  public void testLoop() throws InterruptedException {
//    for(int i =0; i < 50; i++) {
//      loopNum=i;
//      System.err.println("i=" + i);
//      testInitializeFrom();
//      testConsumeReceivedRevisions();
//      testParallelThreads();
//    }
//  }
  /**
   * Record versions in random order, rather than in sequential order
   * This should generate and fill some exceptions.
   */
  @Override
  protected void recordVersions(RegionVersionHolder vh, BitSet bs) {
    List<Integer> list = new ArrayList<Integer>();
    //Build a list of the versions to record
    for(int i =1; i < bs.length(); i++) {
      if(bs.get(i)) {
        list.add(i);
      }
    }
    
    //randomize the list
    Collections.shuffle(list, random);
    for(Integer version : list) {
      vh.recordVersion(version, null);
    }
  }
  
  /**
   * This tries to be a more "real life" test. A bunch of threads perform
   * updates, which should create exceptions. Verify that the final
   * exception list is correct.
   * @throws InterruptedException
   */
  public void testParallelThreads() throws InterruptedException {
    RegionVersionHolder vh1 = new RegionVersionHolder(member);
    RegionVersionHolder vh2 = new RegionVersionHolder(member);
    
    int UPDATES = 50000;
    int NUM_UPDATERS=20;
    int NUM_EXCEPTIONS=500;
//    int NUM_EXCEPTIONS=0;
    
    Random random = new Random();
    TIntHashSet exceptions = new TIntHashSet();
    for(int i =0; i < NUM_EXCEPTIONS; i++) {
      exceptions.add(i);
    }
    
    
    HolderUpdater[] updaters = new HolderUpdater[NUM_UPDATERS];
    for(int i =0; i < updaters.length; i++ ) {
      updaters[i] = new HolderUpdater(UPDATES, i, NUM_UPDATERS,  exceptions, vh1, vh2);
    }
    
    for(int i =0; i < updaters.length; i++ ) {
      updaters[i].start();
    }
    
    
    for(int i =0; i < updaters.length; i++ ) {
      updaters[i].join();
    }

//    System.out.println("testing vh1="+vh1);
    for(int i = 1; i < UPDATES; i++) {
      assertEquals("vector is incorrect on item "+  i,!exceptions.contains(i), vh1.contains(i));
    }
    
    //Mimic a GII. Initialize vh2 from vh1. vh2 has not seen all of the updates
    vh2.initializeFrom(vh1);
    
//    System.out.println("testing vh2="+vh2);
    for(int i = 1; i < UPDATES; i++) {
      assertEquals("vector is incorrect on item "+  i,!exceptions.contains(i), vh2.contains(i));
    }
    
  }
  
  
  
  private static class HolderUpdater extends Thread {
    

    private int updates;
    private int myNumber;
    private int numUpdaters;
    private TIntHashSet exceptions;
    private RegionVersionHolder vh1;
    private RegionVersionHolder vh2;

    public HolderUpdater(int updates, int myNumber, int numUpdaters, TIntHashSet exceptions,
        RegionVersionHolder vh1, RegionVersionHolder vh2) {
      this.updates = updates;
      this.myNumber = myNumber;
      this.numUpdaters = numUpdaters;
      this.exceptions = exceptions;
      this.vh1 = vh1;
      this.vh2 = vh2;
    }

    @Override
    public void run() {
      
      //This thread will record updates for this single thread. The
      //update is the thread id * the update number
      for(int i =myNumber; i < updates; i+= numUpdaters) {
        
        //Only apply the update if it is not an intended exception
        if(!exceptions.contains(i)) {
          synchronized(vh1) {
            vh1.recordVersion(i, null);
          }
          //Apply only half the updates to vh2. We will then mimic a GII
          if(i < updates /2) {
            synchronized(vh2) {
              vh2.recordVersion(i, null);
            }
          }
          Thread.yield();
        }
      }
      
    }
    
  }

}

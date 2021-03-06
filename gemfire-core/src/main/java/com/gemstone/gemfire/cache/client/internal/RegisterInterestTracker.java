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
package com.gemstone.gemfire.cache.client.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.gemstone.gemfire.InternalGemFireError;
import com.gemstone.gemfire.cache.InterestResultPolicy;
import com.gemstone.gemfire.cache.query.internal.CqQueryImpl;
import com.gemstone.gemfire.i18n.LogWriterI18n;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.tier.InterestType;
import com.gemstone.gemfire.internal.concurrent.CFactory;
import com.gemstone.gemfire.internal.concurrent.CM;
import com.gemstone.gemfire.internal.cache.tier.sockets.UnregisterAllInterest;
import com.gemstone.gemfire.internal.i18n.LocalizedStrings;

/**
 * Used to keep track of what interest a client has registered.
 * This code was extracted from the old ConnectionProxyImpl.
 * @author darrel
 * @since 5.7
 */
public class RegisterInterestTracker {

  public final static int interestListIndex = 0;
  public final static int durableInterestListIndex = 1;
  public final static int interestListIndexForUpdatesAsInvalidates = 2;
  public final static int durableInterestListIndexForUpdatesAsInvalidates = 3;
  
  private final FailoverInterestList[] fils = new FailoverInterestList[4];
  
  /** Manages CQs */
  private final CM cqs /* <CqQuery,Boolean> */= CFactory.createCM();
  
  public RegisterInterestTracker() {
    this.fils[interestListIndex] = new FailoverInterestList();
    this.fils[interestListIndexForUpdatesAsInvalidates] = new FailoverInterestList();
    this.fils[durableInterestListIndex] = new FailoverInterestList();
    this.fils[durableInterestListIndexForUpdatesAsInvalidates] = new FailoverInterestList();
  }
  
  public static int getInterestLookupIndex(boolean isDurable, boolean receiveUpdatesAsInvalidates) {
    if (isDurable) {
      if (receiveUpdatesAsInvalidates) {
        return durableInterestListIndexForUpdatesAsInvalidates;
      } else {
        return durableInterestListIndex;
      }
    } else {
      if (receiveUpdatesAsInvalidates) {
        return interestListIndexForUpdatesAsInvalidates;
      } else {
        return interestListIndex;
      }
    }
  }
  
  public List getInterestList(String regionName, int interestType)
  {
    RegionInterestEntry rie1 = readRegionInterests(regionName, interestType, false, false);
    RegionInterestEntry rie2 = readRegionInterests(regionName, interestType, false, true);
    RegionInterestEntry rie3 = readRegionInterests(regionName, interestType, true, false);
    RegionInterestEntry rie4 = readRegionInterests(regionName, interestType, true, true);
    
    ArrayList result = new ArrayList();
    
    if (rie1 != null) {
      result.addAll(rie1.getInterests().keySet());
    }
    
    if (rie2 != null) {
      result.addAll(rie2.getInterests().keySet());
    }
    
    if (rie3 != null) {
      result.addAll(rie3.getInterests().keySet());
    }
    
    if (rie4 != null) {
      result.addAll(rie4.getInterests().keySet());
    }
    
    return result;
  }

  public void addSingleInterest(LocalRegion r, Object key, int interestType,
      InterestResultPolicy pol, boolean isDurable, boolean receiveUpdatesAsInvalidates)
  {
    RegionInterestEntry rie = getRegionInterests(r, interestType, false,
        isDurable, receiveUpdatesAsInvalidates);
    rie.getInterests().put(key, pol);
  }

  public boolean removeSingleInterest(LocalRegion r, Object key,
      int interestType, boolean isDurable, boolean receiveUpdatesAsInvalidates)
  {
    final LogWriterI18n logger = r.getCache().getLoggerI18n();
    RegionInterestEntry rie = getRegionInterests(r, interestType, true,
        isDurable, receiveUpdatesAsInvalidates);
    if (rie == null) {
      return false;
    }
    if (logger.fineEnabled()) {
      logger.fine("removeSingleInterest region=" + r.getFullPath() + " key="
          + key);
    }
    Object interest = rie.getInterests().remove(key);
    if (interest == null) {
      if (logger.warningEnabled()) {
        logger.warning(LocalizedStrings.RegisterInterestTracker_REMOVESINGLEINTEREST_KEY_0_NOT_REGISTERED_IN_THE_CLIENT, key);
      }
      return false;
    }
    else {
      return true;
    }
    //return rie.getInterests().remove(key) != null;
  }

  public void addInterestList(LocalRegion r, List keys,
      InterestResultPolicy pol, boolean isDurable, boolean receiveUpdatesAsInvalidates)
  {
    RegionInterestEntry rie = getRegionInterests(r, InterestType.KEY, false,
        isDurable, receiveUpdatesAsInvalidates);
    for (int i = 0; i < keys.size(); i++) {
      rie.getInterests().put(keys.get(i), pol);
    }
  }

  public void addCq(CqQueryImpl cqi, boolean isDurable)
  {
    this.cqs.put(cqi, Boolean.valueOf(isDurable));
    /*
    RegionInterestEntry rie = getRegionInterests(r, InterestType.CQ, false, isDurable);
      rie.getInterests().put(cqi.getName(), cqi);
      */
  }
  
  public void removeCq(CqQueryImpl cqi, boolean isDurable)
  {
    this.cqs.remove(cqi);
    /*
    RegionInterestEntry rie = getRegionInterests(r, InterestType.CQ, false, isDurable);
      rie.getInterests().remove(cqi.getName());
      */
  }

  public Map getCqsMap(){
    return this.cqs;
  }
  
  /**
   * Unregisters everything registered on the given region name
   */
  public void unregisterRegion(ServerRegionProxy srp,
                               boolean keepalive) {
    removeAllInterests(srp, InterestType.KEY, false, keepalive, false);
    removeAllInterests(srp, InterestType.FILTER_CLASS, false, keepalive, false);
    removeAllInterests(srp, InterestType.OQL_QUERY, false, keepalive, false);
    removeAllInterests(srp, InterestType.REGULAR_EXPRESSION, false, keepalive, false);
    removeAllInterests(srp, InterestType.KEY, false, keepalive, true);
    removeAllInterests(srp, InterestType.FILTER_CLASS, false, keepalive, true);
    removeAllInterests(srp, InterestType.OQL_QUERY, false, keepalive, true);
    removeAllInterests(srp, InterestType.REGULAR_EXPRESSION, false, keepalive, true);
    //durable
    if (srp.getPool().isDurableClient()) {
      removeAllInterests(srp, InterestType.KEY, true, keepalive, true);
      removeAllInterests(srp, InterestType.FILTER_CLASS, true, keepalive, true);
      removeAllInterests(srp, InterestType.OQL_QUERY, true, keepalive, true);
      removeAllInterests(srp, InterestType.REGULAR_EXPRESSION, true, keepalive, true);
      removeAllInterests(srp, InterestType.KEY, true, keepalive, false);
      removeAllInterests(srp, InterestType.FILTER_CLASS, true, keepalive, false);
      removeAllInterests(srp, InterestType.OQL_QUERY, true, keepalive, false);
      removeAllInterests(srp, InterestType.REGULAR_EXPRESSION, true, keepalive, false);
    }
  }
  
  /**
   * Remove all interests of a given type on the given proxy's region.
   * @param interestType
   *          the interest type
   * @param durable
   *          a boolean stating whether to remove durable or non-durable registrations
   */
  private void removeAllInterests(ServerRegionProxy srp, int interestType,
      boolean durable, boolean keepAlive, boolean receiveUpdatesAsInvalidates)
  {
    String regName = srp.getRegionName();
    CM allInterests = getRegionToInterestsMap(interestType, durable, receiveUpdatesAsInvalidates);
    if (allInterests.remove(regName) != null) {
      if (srp.getLogger().fineEnabled()) {
        srp.getLogger().fine(
            "removeAllInterests region=" + regName + " type="
                + InterestType.getString(interestType));
      }
      try {
        // fix bug 35680 by using a UnregisterAllInterest token
        Object key = UnregisterAllInterest.singleton();
        // we have already cleaned up the tracker so send the op directly
        UnregisterInterestOp.execute(srp.getPool(), regName, key, interestType,
                                     true/*isClosing*/, keepAlive);
      }
      catch (Exception e) {
        if(srp.getPool().getCancelCriterion().cancelInProgress() == null) {
          if (srp.getLogger().warningEnabled()) {
            srp.getLogger().warning(
                LocalizedStrings.RegisterInterestTracker_PROBLEM_REMOVING_ALL_INTEREST_ON_REGION_0_INTERESTTYPE_1_2,
                new Object[] {regName, InterestType.getString(interestType), e.getLocalizedMessage()});
          }
        }
      }
    }
  }

  public boolean removeInterestList(LocalRegion r, List keys, boolean isDurable,
      boolean receiveUpdatesAsInvalidates)
  {
    final LogWriterI18n logger = r.getCache().getLoggerI18n();
    RegionInterestEntry rie = getRegionInterests(r, InterestType.KEY, true,
        isDurable, receiveUpdatesAsInvalidates);
    if (rie == null) {
      return false;
    }
    if (logger.fineEnabled()) {
      logger.fine("removeInterestList region=" + r.getFullPath() + " keys="
          + keys);
    }
    int removeCount = 0;
    for (int i = 0; i < keys.size(); i++) {
      Object key = keys.get(i);
      Object interest = rie.getInterests().remove(key);
      if (interest != null) {
        removeCount++;
      }
      else {
        if (logger.warningEnabled())
          logger.warning(
              LocalizedStrings.RegisterInterestTracker_REMOVEINTERESTLIST_KEY_0_NOT_REGISTERED_IN_THE_CLIENT,
              key);
      }
    }
    return removeCount != 0;
  }

  /**
   * Return keys of interest for a given region. The keys in this Map are the
   * full names of the regions. The values are instances of RegionInterestEntry.
   *
   * @param interestType
   *          the type to return
   * @return the map
   */
  public CM getRegionToInterestsMap(int interestType, boolean isDurable,
      boolean receiveUpdatesAsInvalidates)
  {
    FailoverInterestList fil =
      this.fils[getInterestLookupIndex(isDurable, receiveUpdatesAsInvalidates)];
    
    CM mapOfInterest = null;
    
    switch (interestType) {
    case InterestType.KEY:
      mapOfInterest = fil.keysOfInterest;
      break;
    case InterestType.REGULAR_EXPRESSION:
      mapOfInterest = fil.regexOfInterest;
      break;
    case InterestType.FILTER_CLASS:
      mapOfInterest = fil.filtersOfInterest;
      break;
    case InterestType.CQ:
      mapOfInterest = fil.cqsOfInterest;
      break;
    case InterestType.OQL_QUERY:
      mapOfInterest = fil.queriesOfInterest;
      break;
    default:
      throw new InternalGemFireError("Unknown interestType");
    }
    return mapOfInterest;
  }

  /**
   * Return the RegionInterestEntry for the given region. Create one if none
   * exists and forRemoval is false.
   *
   * @param r
   *          specified region
   * @param interestType
   *          desired interest type
   * @param forRemoval
   *          true if calls wants one for removal
   * @return the entry or null if none exists and forRemoval is true.
   */
  private RegionInterestEntry getRegionInterests(LocalRegion r,
                                                 int interestType,
                                                 boolean forRemoval,
                                                 boolean isDurable,
                                                 boolean receiveUpdatesAsInvalidates)
  {
    final String regionName = r.getFullPath();
    CM mapOfInterest = getRegionToInterestsMap(interestType, isDurable, receiveUpdatesAsInvalidates);
    RegionInterestEntry result = (RegionInterestEntry)
      mapOfInterest.get(regionName);
    if (result == null && !forRemoval) {
      RegionInterestEntry rie = new RegionInterestEntry(r);
      result = (RegionInterestEntry)mapOfInterest.putIfAbsent(regionName, rie);
      if (result == null) {
        result = rie;
      }
    }
    return result;
  }
  private RegionInterestEntry readRegionInterests(String regionName,
                                                  int interestType,
                                                  boolean isDurable,
                                                  boolean receiveUpdatesAsInvalidates)
  {
    CM mapOfInterest = getRegionToInterestsMap(interestType, isDurable, receiveUpdatesAsInvalidates);
    return (RegionInterestEntry)mapOfInterest.get(regionName);
  }

  /**
   * A Holder object for client's register interest, this is required when 
   * a client fails over to another server and does register interest based on 
   * this Data structure 
   * 
   * @author Yogesh Mahajan
   * @since 5.5
   *
   */
   static protected class FailoverInterestList {
    /**
     * Record of enumerated keys of interest.
     *
     * This list is maintained here in case an endpoint (server) bounces. In that
     * case, a message will be sent to the endpoint as soon as it has restarted.
     *
     * The keys in this Map are the full names of the regions. The values are
     * instances of {@link RegionInterestEntry}.
     */
    final CM keysOfInterest = CFactory.createCM();

    /**
     * Record of regular expression keys of interest.
     *
     * This list is maintained here in case an endpoint (server) bounces. In that
     * case, a message will be sent to the endpoint as soon as it has restarted.
     *
     * The keys in this Map are the full names of the regions. The values are
     * instances of {@link RegionInterestEntry}.
     */
     final CM regexOfInterest = CFactory.createCM();

    /**
     * Record of filtered keys of interest.
     *
     * This list is maintained here in case an endpoint (server) bounces. In that
     * case, a message will be sent to the endpoint as soon as it has restarted.
     *
     * The keys in this Map are the full names of the regions. The values are
     * instances of {@link RegionInterestEntry}.
     */
     final CM filtersOfInterest = CFactory.createCM();

    /**
     * Record of QOL keys of interest.
     *
     * This list is maintained here in case an endpoint (server) bounces. In that
     * case, a message will be sent to the endpoint as soon as it has restarted.
     *
     * The keys in this Map are the full names of the regions. The values are
     * instances of {@link RegionInterestEntry}.
     */
     final CM queriesOfInterest = CFactory.createCM();  
     
     /**
      * Record of registered CQs
      *
      */
      final CM cqsOfInterest = CFactory.createCM(); 
  }

  /**
   * Description of the interests of a particular region.
   *
   * @author jpenney
   *
   */
  static public class RegionInterestEntry
  {
    private final LocalRegion region;

    private final CM interests;

    RegionInterestEntry(LocalRegion r) {
      this.region = r;
      this.interests = CFactory.createCM();
    }

    public LocalRegion getRegion() {
      return this.region;
    }

    public CM getInterests() {
      return this.interests;
    }
  }
}

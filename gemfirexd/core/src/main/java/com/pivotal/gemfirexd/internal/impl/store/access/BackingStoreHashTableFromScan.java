/*

   Derby - Class com.pivotal.gemfirexd.internal.impl.store.access.BackingStoreHashTableFromScan

   Licensed to the Apache Software Foundation (ASF) under one or more
   contributor license agreements.  See the NOTICE file distributed with
   this work for additional information regarding copyright ownership.
   The ASF licenses this file to you under the Apache License, Version 2.0
   (the "License"); you may not use this file except in compliance with
   the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.

 */

/*
 * Changes for GemFireXD distributed data platform (some marked by "GemStone changes")
 *
 * Portions Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
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

package com.pivotal.gemfirexd.internal.impl.store.access;





import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.store.access.BackingStoreHashtable;
import com.pivotal.gemfirexd.internal.iapi.store.access.Qualifier;
import com.pivotal.gemfirexd.internal.iapi.store.access.RowSource;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.ScanManager;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;

import java.util.Properties;

/**

Extend BackingStoreHashtable with the ability to maintain the underlying 
openScan() until the hashtable has been closed.  This is necessary for 
long row access.  Access to long row delays actual objectification until
the columns are accessed, but depends on the underlying table to be still
open when the column is accessed.  

<P>
Transactions are obtained from an AccessFactory.
@see BackingStoreHashtable

**/

class BackingStoreHashTableFromScan extends BackingStoreHashtable
{

    /**************************************************************************
     * Fields of the class
     **************************************************************************
     */
    private ScanManager             open_scan;

    /**************************************************************************
     * Constructors for This class:
     **************************************************************************
     */
    public BackingStoreHashTableFromScan(
        TransactionController   tc,
		long                    conglomId,
		int                     open_mode,
        int                     lock_level,
        int                     isolation_level,
		FormatableBitSet                 scanColumnList,
		DataValueDescriptor[]   startKeyValue,
		int                     startSearchOperator,
		Qualifier               qualifier[][],
		DataValueDescriptor[]   stopKeyValue,
		int                     stopSearchOperator,
        long                    max_rowcnt,
        int[]                   key_column_numbers,
        boolean                 remove_duplicates,
        long                    estimated_rowcnt,
        long                    max_inmemory_rowcnt,
        int                     initialCapacity,
        float                   loadFactor,
        boolean                 collect_runtimestats,
		boolean					skipNullKeyColumns,
        boolean                 keepAfterCommit)
            throws StandardException
    {

        super(
            tc, 
            (RowSource) null,
            key_column_numbers,
            remove_duplicates,
            estimated_rowcnt,
            max_inmemory_rowcnt,
            initialCapacity,
            loadFactor,
			skipNullKeyColumns,
            keepAfterCommit);

        open_scan =  (ScanManager)
            tc.openScan(
                conglomId,
                false,
                open_mode,
                lock_level,
                isolation_level,
                scanColumnList,
                startKeyValue,
                startSearchOperator,
                qualifier,
                stopKeyValue,
                // GemStone changes BEGIN
                stopSearchOperator, null);
                // GemStone changes END

        open_scan.fetchSet(
            max_rowcnt, key_column_numbers, this);

        if (collect_runtimestats)
        {
            Properties prop = new Properties();
            open_scan.getScanInfo().getAllScanInfo(prop);
            this.setAuxillaryRuntimeStats(prop);
            prop = null;
        }
    }


    /**************************************************************************
     * Private/Protected methods of This class:
     **************************************************************************
     */

    /**************************************************************************
     * Public Methods of This class:
     **************************************************************************
     */

    /**
     * Close the BackingStoreHashtable.
     * <p>
     * Perform any necessary cleanup after finishing with the hashtable.  Will
     * deallocate/dereference objects as necessary.  If the table has gone
     * to disk this will drop any on disk files used to support the hash table.
     * <p>
     *
	 * @exception  StandardException  Standard exception policy.
     **/
    public void close() 
		throws StandardException
    {
        open_scan.close();

        super.close();

        return;
    }

    /**************************************************************************
     * Public Methods of XXXX class:
     **************************************************************************
     */
}

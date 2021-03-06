/*
 * Copyright 2009-2012 by The Regents of the University of California
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * you may obtain a copy of the License from
 * 
 *     http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.uci.ics.asterix.transaction.management.ioopcallbacks;

import java.util.List;

import edu.uci.ics.asterix.transaction.management.opcallbacks.IndexOperationTracker;
import edu.uci.ics.hyracks.api.exceptions.HyracksDataException;
import edu.uci.ics.hyracks.storage.am.btree.impls.BTree;
import edu.uci.ics.hyracks.storage.am.lsm.btree.impls.LSMBTreeImmutableComponent;
import edu.uci.ics.hyracks.storage.am.lsm.common.api.ILSMComponent;

public class LSMBTreeIOOperationCallback extends AbstractLSMIOOperationCallback {

    public LSMBTreeIOOperationCallback(IndexOperationTracker opTracker) {
        super(opTracker);
    }

    @Override
    public void afterOperation(List<ILSMComponent> oldComponents, ILSMComponent newComponent)
            throws HyracksDataException {
        if (oldComponents != null && newComponent != null) {
            LSMBTreeImmutableComponent btreeComponent = (LSMBTreeImmutableComponent) newComponent;
            putLSNIntoMetadata(btreeComponent.getBTree(), oldComponents);
        }
    }

    @Override
    protected long getComponentLSN(List<ILSMComponent> oldComponents) throws HyracksDataException {
        if (oldComponents == null) {
            // Implies a flush IO operation.
            return opTracker.getLastLSN();
        }
        // Get max LSN from the oldComponents. Implies a merge IO operation.
        long maxLSN = -1;
        for (ILSMComponent c : oldComponents) {
            BTree btree = ((LSMBTreeImmutableComponent) c).getBTree();
            maxLSN = Math.max(getTreeIndexLSN(btree), maxLSN);
        }
        return maxLSN;
    }
}

/*
 * Copyright 2009-2010 by The Regents of the University of California
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
package edu.uci.ics.asterix.formats.nontagged;

import edu.uci.ics.asterix.dataflow.data.nontagged.keynormalizers.AInt32AscNormalizedKeyComputerFactory;
import edu.uci.ics.asterix.dataflow.data.nontagged.keynormalizers.AInt32DescNormalizedKeyComputerFactory;
import edu.uci.ics.asterix.dataflow.data.nontagged.keynormalizers.AStringAscNormalizedKeyComputerFactory;
import edu.uci.ics.asterix.dataflow.data.nontagged.keynormalizers.AStringDescNormalizedKeyComputerFactory;
import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.hyracks.algebricks.data.INormalizedKeyComputerFactoryProvider;
import edu.uci.ics.hyracks.api.dataflow.value.INormalizedKeyComputerFactory;

public class AqlNormalizedKeyComputerFactoryProvider implements INormalizedKeyComputerFactoryProvider {

    public static final AqlNormalizedKeyComputerFactoryProvider INSTANCE = new AqlNormalizedKeyComputerFactoryProvider();

    private AqlNormalizedKeyComputerFactoryProvider() {
    }

    @Override
    public INormalizedKeyComputerFactory getNormalizedKeyComputerFactory(Object type, boolean ascending) {
        IAType aqlType = (IAType) type;
        if (ascending) {
            switch (aqlType.getTypeTag()) {
                case INT32: {
                    return AInt32AscNormalizedKeyComputerFactory.INSTANCE;
                }
                case STRING: {
                    return AStringAscNormalizedKeyComputerFactory.INSTANCE;
                }
                default: {
                    return null;
                }
            }
        } else {
            switch (aqlType.getTypeTag()) {
                case INT32: {
                    return AInt32DescNormalizedKeyComputerFactory.INSTANCE;
                }
                case STRING: {
                    return AStringDescNormalizedKeyComputerFactory.INSTANCE;
                }
                default: {
                    return null;
                }
            }
        }
    }

}

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

package edu.uci.ics.asterix.om.typecomputer.base;

import edu.uci.ics.asterix.om.types.IAType;
import edu.uci.ics.hyracks.algebricks.core.algebra.expressions.AbstractFunctionCallExpression;

public class TypeComputerUtilities {

    public static boolean setRequiredAndInputTypes(AbstractFunctionCallExpression expr, IAType requiredRecordType,
            IAType inputRecordType) {
        boolean changed = false;
        Object opaqueParameter = expr.getOpaqueParameters();
        if (opaqueParameter == null) {
            Object[] opaqueParameters = new Object[2];
            opaqueParameters[0] = requiredRecordType;
            opaqueParameters[1] = inputRecordType;
            expr.setOpaqueParameters(opaqueParameters);
            changed = true;
        }
        return changed;
    }

    public static IAType getRequiredType(AbstractFunctionCallExpression expr) {
        Object[] type = expr.getOpaqueParameters();
        if (type != null) {
            IAType returnType = (IAType) type[0];
            return returnType;
        } else
            return null;
    }

    public static IAType getInputType(AbstractFunctionCallExpression expr) {
        Object[] type = expr.getOpaqueParameters();
        if (type != null) {
            IAType returnType = (IAType) type[1];
            return returnType;
        } else
            return null;
    }
}

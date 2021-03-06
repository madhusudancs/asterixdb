package edu.uci.ics.asterix.om.util;

import edu.uci.ics.hyracks.data.std.util.ByteArrayAccessibleOutputStream;

/**
 * This class extends ByteArrayAccessibleOutputStream to allow reset to a given
 * size.
 * 
 */
public class ResettableByteArrayOutputStream extends ByteArrayAccessibleOutputStream {

    public void reset(int size) {
        count = size;
    }
}

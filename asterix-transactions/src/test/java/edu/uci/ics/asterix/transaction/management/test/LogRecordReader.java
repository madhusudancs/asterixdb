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
package edu.uci.ics.asterix.transaction.management.test;

import java.io.IOException;
import java.util.Properties;

import edu.uci.ics.asterix.transaction.management.exception.ACIDException;
import edu.uci.ics.asterix.transaction.management.service.logging.IBuffer;
import edu.uci.ics.asterix.transaction.management.service.logging.ILogCursor;
import edu.uci.ics.asterix.transaction.management.service.logging.ILogFilter;
import edu.uci.ics.asterix.transaction.management.service.logging.ILogManager;
import edu.uci.ics.asterix.transaction.management.service.logging.ILogRecordHelper;
import edu.uci.ics.asterix.transaction.management.service.logging.LogManager;
import edu.uci.ics.asterix.transaction.management.service.logging.LogManagerProperties;
import edu.uci.ics.asterix.transaction.management.service.logging.LogUtil;
import edu.uci.ics.asterix.transaction.management.service.logging.LogicalLogLocator;
import edu.uci.ics.asterix.transaction.management.service.logging.PhysicalLogLocator;
import edu.uci.ics.asterix.transaction.management.service.transaction.TransactionProvider;

public class LogRecordReader {

    ILogManager logManager;

    public LogRecordReader(TransactionProvider factory) throws ACIDException {
        logManager = factory.getLogManager();
    }

    public LogRecordReader(ILogManager logManager) {
        this.logManager = logManager;
    }

    public void readLogs(long startingLsn) throws IOException, ACIDException {
        ILogRecordHelper parser = logManager.getLogRecordHelper();
        PhysicalLogLocator lsn = new PhysicalLogLocator(startingLsn, logManager);
        ILogCursor logCursor = logManager.readLog(lsn, new ILogFilter() {
            @Override
            public boolean accept(IBuffer buffer, long startOffset, int length) {
                return true;
            }
        });
        LogicalLogLocator memLSN = LogUtil.getDummyLogicalLogLocator(logManager);
        int logCount = 0;
        while (true) {
            boolean logValidity = logCursor.next(memLSN);
            if (logValidity) {
                System.out.println(++logCount + parser.getLogRecordForDisplay(memLSN));
            } else {
                break;
            }
        }
    }

    public void readLogRecord(long lsnValue) throws IOException, ACIDException {
        LogicalLogLocator memLSN = logManager.readLog(new PhysicalLogLocator(lsnValue, logManager));
        System.out.println(logManager.getLogRecordHelper().getLogRecordForDisplay(memLSN));
    }

    /**
     * @param args
     */
    public static void main(String[] args) throws ACIDException, Exception {
        long lsnValue = 10747454;
        String id = "nc1";
        String logDir = "/home/raman/research/work/hyracks-branches/svn/trunk/hyracks/asterix_logs/";
        Properties props = new Properties();
        props.setProperty(LogManagerProperties.LOG_DIR_KEY, logDir + "/" + id);
        LogManagerProperties logProps = new LogManagerProperties(props);
        LogManager logManager = new LogManager(null, logProps);
        LogRecordReader logReader = new LogRecordReader(logManager);
        logReader.readLogs(0);
        //   logReader.readLogRecord(1703620);
    }

}

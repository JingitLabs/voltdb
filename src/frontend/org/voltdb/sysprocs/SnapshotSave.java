/* This file is part of VoltDB.
 * Copyright (C) 2008-2010 VoltDB L.L.C.
 *
 * VoltDB is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * VoltDB is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with VoltDB.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.voltdb.sysprocs;

import java.io.File;
import java.io.IOException;
import java.io.StringWriter;
import java.io.PrintWriter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.voltdb.BackendTarget;
import org.voltdb.DefaultSnapshotDataTarget;
import org.voltdb.DependencyPair;
import org.voltdb.HsqlBackend;
import org.voltdb.ParameterSet;
import org.voltdb.ProcInfo;
import org.voltdb.SiteProcedureConnection;
import org.voltdb.SnapshotDataTarget;
import org.voltdb.SnapshotSiteProcessor;
import org.voltdb.VoltDB;
import org.voltdb.VoltSystemProcedure;
import org.voltdb.VoltTable;
import org.voltdb.VoltType;
import org.voltdb.ExecutionSite.SystemProcedureExecutionContext;
import org.voltdb.SnapshotSiteProcessor.SnapshotTableTask;
import org.voltdb.VoltTable.ColumnInfo;
import org.voltdb.catalog.*;
import org.voltdb.client.ConnectionUtil;
import org.voltdb.dtxn.DtxnConstants;
import org.voltdb.logging.VoltLogger;
import org.voltdb.sysprocs.saverestore.SnapshotUtil;
import org.voltdb.utils.CatalogUtil;

@ProcInfo(singlePartition = false)
public class SnapshotSave extends VoltSystemProcedure
{
    private static final VoltLogger TRACE_LOG = new VoltLogger(SnapshotSave.class.getName());

    private static final VoltLogger HOST_LOG = new VoltLogger("HOST");

    private static final int DEP_saveTest = (int)
        SysProcFragmentId.PF_saveTest | DtxnConstants.MULTIPARTITION_DEPENDENCY;
    private static final int DEP_saveTestResults = (int)
        SysProcFragmentId.PF_saveTestResults;
    private static final int DEP_createSnapshotTargets = (int)
        SysProcFragmentId.PF_createSnapshotTargets | DtxnConstants.MULTIPARTITION_DEPENDENCY;
    private static final int DEP_createSnapshotTargetsResults = (int)
        SysProcFragmentId.PF_createSnapshotTargetsResults;

    /**
     * Ensure the first thread to run the fragment does the creation
     * of the targets and the distribution of the work.
     */
    private static final Semaphore m_snapshotCreateSetupPermit = new Semaphore(1);
    /**
     * Only proceed once permits are available after setup completes
     */
    private static Semaphore m_snapshotPermits = new Semaphore(0);
    private static final LinkedList<Deque<SnapshotTableTask>>
        m_taskListsForSites = new LinkedList<Deque<SnapshotTableTask>>();

    @Override
    public void init(int numberOfPartitions, SiteProcedureConnection site,
            Procedure catProc, BackendTarget eeType, HsqlBackend hsql, Cluster cluster)
    {
        super.init(numberOfPartitions, site, catProc, eeType, hsql, cluster);
        site.registerPlanFragment(SysProcFragmentId.PF_saveTest, this);
        site.registerPlanFragment(SysProcFragmentId.PF_saveTestResults, this);
        site.registerPlanFragment(SysProcFragmentId.PF_createSnapshotTargets, this);
        site.registerPlanFragment(SysProcFragmentId.PF_createSnapshotTargetsResults, this);
    }

    @Override
    public DependencyPair
    executePlanFragment(HashMap<Integer, List<VoltTable>> dependencies,
                        long fragmentId,
                        ParameterSet params,
                        SystemProcedureExecutionContext context)
    {
        String hostname = ConnectionUtil.getHostnameOrAddress();
        if (fragmentId == SysProcFragmentId.PF_saveTest)
        {
            return saveTest(params, context, hostname);
        }
        else if (fragmentId == SysProcFragmentId.PF_saveTestResults)
        {
            return saveTestResults(dependencies);
        }
        else if (fragmentId == SysProcFragmentId.PF_createSnapshotTargets)
        {
            assert(params.toArray()[0] != null);
            assert(params.toArray()[1] != null);
            assert(params.toArray()[2] != null);
            assert(params.toArray()[3] != null);
            final String file_path = (String) params.toArray()[0];
            final String file_nonce = (String) params.toArray()[1];
            final long startTime = (Long)params.toArray()[2];
            byte block = (Byte)params.toArray()[3];
            return createSnapshotTargets(file_path, file_nonce, block, startTime, context, hostname);
        }
        else if (fragmentId == SysProcFragmentId.PF_createSnapshotTargetsResults)
        {
            return createSnapshotTargetsResults(dependencies);
        }
        assert (false);
        return null;
    }

    private DependencyPair createSnapshotTargetsResults(
            HashMap<Integer, List<VoltTable>> dependencies) {
        {
            TRACE_LOG.trace("Aggregating create snapshot target results");
            assert (dependencies.size() > 0);
            List<VoltTable> dep = dependencies.get(DEP_createSnapshotTargets);
            VoltTable result = null;
            for (VoltTable table : dep)
            {
                /**
                 * XXX Ning: There are two different tables here. We have to
                 * detect which table we are looking at in order to create the
                 * result table with the proper schema. Maybe we should make the
                 * result table consistent?
                 */
                if (result == null) {
                    if (table.getColumnType(2).equals(VoltType.INTEGER))
                        result = constructPartitionResultsTable();
                    else
                        result = constructNodeResultsTable();
                }

                while (table.advanceRow())
                {
                    // this will add the active row of table
                    result.add(table);
                }
            }
            return new
                DependencyPair( DEP_createSnapshotTargetsResults, result);
        }
    }

    private DependencyPair saveTest(ParameterSet params,
            SystemProcedureExecutionContext context, String hostname) {
        {
            assert(params.toArray()[0] != null);
            assert(params.toArray()[1] != null);
            String file_path = (String) params.toArray()[0];
            String file_nonce = (String) params.toArray()[1];
            VoltTable result = constructNodeResultsTable();
            // Choose the lowest site ID on this host to do the file scan
            // All other sites should just return empty results tables.
            int host_id = context.getExecutionSite().getCorrespondingHostId();
            Integer lowest_site_id =
                VoltDB.instance().getCatalogContext().siteTracker.
                getLowestLiveExecSiteIdForHost(host_id);
            if (context.getExecutionSite().getSiteId() == lowest_site_id)
            {
                TRACE_LOG.trace("Checking feasibility of save with path and nonce: "
                                + file_path + ", " + file_nonce);

                if (SnapshotSiteProcessor.ExecutionSitesCurrentlySnapshotting.get() != -1) {
                    result.addRow(
                                  Integer.parseInt(context.getSite().getHost().getTypeName()),
                                  hostname,
                                  "",
                                  "FAILURE",
                    "SNAPSHOT IN PROGRESS");
                    return new DependencyPair( DEP_saveTest, result);
                }

                for (Table table : getTablesToSave(context.getDatabase()))
                {
                    File saveFilePath =
                        constructFileForTable(table, file_path, file_nonce,
                                              context.getSite().getHost().getTypeName());
                    TRACE_LOG.trace("Host ID " + context.getSite().getHost().getTypeName() +
                                    " table: " + table.getTypeName() +
                                    " to path: " + saveFilePath);
                    String file_valid = "SUCCESS";
                    String err_msg = "";
                    if (saveFilePath.exists())
                    {
                        file_valid = "FAILURE";
                        err_msg = "SAVE FILE ALREADY EXISTS: " + saveFilePath;
                    }
                    else if (!saveFilePath.getParentFile().canWrite())
                    {
                        file_valid = "FAILURE";
                        err_msg = "FILE LOCATION UNWRITABLE: " + saveFilePath;
                    }
                    else
                    {
                        try
                        {
                            saveFilePath.createNewFile();
                        }
                        catch (IOException ex)
                        {
                            file_valid = "FAILURE";
                            err_msg = "FILE CREATION OF " + saveFilePath +
                            "RESULTED IN IOException: " + ex.getMessage();
                        }
                    }
                    result.addRow(Integer.parseInt(context.getSite().getHost().getTypeName()),
                                  hostname,
                                  table.getTypeName(),
                                  file_valid,
                                  err_msg);
                }
            }
            return new DependencyPair(DEP_saveTest, result);
        }
    }

    private DependencyPair saveTestResults(
            HashMap<Integer, List<VoltTable>> dependencies) {
        {
            TRACE_LOG.trace("Aggregating save feasiblity results");
            assert (dependencies.size() > 0);
            List<VoltTable> dep = dependencies.get(DEP_saveTest);
            VoltTable result = constructNodeResultsTable();
            for (VoltTable table : dep)
            {
                while (table.advanceRow())
                {
                    // this will add the active row of table
                    result.add(table);
                }
            }
            return new DependencyPair( DEP_saveTestResults, result);
        }
    }

    private DependencyPair createSnapshotTargets(String file_path, String file_nonce, byte block,
            long startTime, SystemProcedureExecutionContext context, String hostname)
    {
        TRACE_LOG.trace("Creating snapshot target and handing to EEs");
        final VoltTable result = constructNodeResultsTable();
        final int numLocalSites = VoltDB.instance().getLocalSites().values().size();
        if ( m_snapshotCreateSetupPermit.tryAcquire()) {
            /*
             * Used to close targets on failure
             */
            final ArrayList<SnapshotDataTarget> targets = new ArrayList<SnapshotDataTarget>();
            try {
                final ArrayDeque<SnapshotTableTask> partitionedSnapshotTasks =
                    new ArrayDeque<SnapshotTableTask>();
                final ArrayList<SnapshotTableTask> replicatedSnapshotTasks =
                    new ArrayList<SnapshotTableTask>();
                assert(SnapshotSiteProcessor.ExecutionSitesCurrentlySnapshotting.get() == -1);

                final List<Table> tables = getTablesToSave(context.getDatabase());

                SnapshotUtil.recordSnapshotTableList(
                        startTime,
                        file_path,
                        file_nonce,
                        tables);
                final AtomicInteger numTables = new AtomicInteger(tables.size());
                final SnapshotRegistry.Snapshot snapshotRecord =
                    SnapshotRegistry.startSnapshot(
                            startTime,
                            context.getExecutionSite().getCorrespondingHostId(),
                            file_path,
                            file_nonce,
                            tables.toArray(new Table[0]));
                for (final Table table : getTablesToSave(context.getDatabase()))
                {
                    String canSnapshot = "SUCCESS";
                    String err_msg = "";
                    final File saveFilePath =
                        constructFileForTable(table, file_path, file_nonce,
                                              context.getSite().getHost().getTypeName());
                    SnapshotDataTarget sdt = null;
                    try {
                        sdt =
                            constructSnapshotDataTargetForTable(
                                    context,
                                    saveFilePath,
                                    table,
                                    context.getSite().getHost(),
                                    context.getCluster().getPartitions().size(),
                                    startTime);
                        targets.add(sdt);
                        final SnapshotDataTarget sdtFinal = sdt;
                        final Runnable onClose = new Runnable() {
                            @Override
                            public void run() {
                                final long now = System.currentTimeMillis();
                                snapshotRecord.updateTable(table.getTypeName(),
                                        new SnapshotRegistry.Snapshot.TableUpdater() {
                                    @Override
                                    public SnapshotRegistry.Snapshot.Table update(
                                            SnapshotRegistry.Snapshot.Table registryTable) {
                                        return snapshotRecord.new Table(
                                                registryTable,
                                                sdtFinal.getBytesWritten(),
                                                now,
                                                sdtFinal.getLastWriteException());
                                    }
                                });
                                int tablesLeft = numTables.decrementAndGet();
                                if (tablesLeft == 0) {
                                    final SnapshotRegistry.Snapshot completed =
                                        SnapshotRegistry.finishSnapshot(snapshotRecord);
                                    final double duration =
                                        (completed.timeFinished - completed.timeStarted) / 1000.0;
                                    HOST_LOG.info(
                                            "Snapshot " + snapshotRecord.nonce + " finished at " +
                                             completed.timeFinished + " and took " + duration
                                             + " seconds ");
                                }
                            }
                        };

                        sdt.setOnCloseHandler(onClose);

                        final SnapshotTableTask task =
                            new SnapshotTableTask(
                                    table.getRelativeIndex(),
                                    sdt,
                                    table.getIsreplicated(),
                                    table.getTypeName());

                        if (table.getIsreplicated()) {
                            replicatedSnapshotTasks.add(task);
                        } else {
                            partitionedSnapshotTasks.offer(task);
                        }
                    } catch (IOException ex) {
                        /*
                         * Creation of this specific target failed. Close it if it was created.
                         * Continue attempting the snapshot anyways so that at least some of the data
                         * can be retrieved.
                         */
                        try {
                            if (sdt != null) {
                                targets.remove(sdt);
                                sdt.close();
                            }
                        } catch (Exception e) {
                            HOST_LOG.error(e);
                        }

                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        ex.printStackTrace(pw);
                        pw.flush();
                        canSnapshot = "FAILURE";
                        err_msg = "SNAPSHOT INITIATION OF " + saveFilePath +
                        "RESULTED IN IOException: \n" + sw.toString();
                    }

                    result.addRow(Integer.parseInt(context.getSite().getHost().getTypeName()),
                            hostname,
                            table.getTypeName(),
                            canSnapshot,
                            err_msg);
                }

                synchronized (m_taskListsForSites) {
                    if (!partitionedSnapshotTasks.isEmpty() || !replicatedSnapshotTasks.isEmpty()) {
                        SnapshotSiteProcessor.ExecutionSitesCurrentlySnapshotting.set(
                                VoltDB.instance().getLocalSites().values().size());
                        for (int ii = 0; ii < numLocalSites; ii++) {
                            m_taskListsForSites.add(new ArrayDeque<SnapshotTableTask>());
                        }
                    } else {
                        SnapshotRegistry.discardSnapshot(snapshotRecord);
                    }

                    /**
                     * Distribute the writing of replicated tables to exactly one partition.
                     */
                    for (int ii = 0; ii < numLocalSites && !partitionedSnapshotTasks.isEmpty(); ii++) {
                        m_taskListsForSites.get(ii).addAll(partitionedSnapshotTasks);
                    }

                    int siteIndex = 0;
                    for (SnapshotTableTask t : replicatedSnapshotTasks) {
                        m_taskListsForSites.get(siteIndex++ % numLocalSites).offer(t);
                    }
                }
            } catch (Exception ex) {
                /*
                 * Close all the targets to release the threads. Don't let sites get any tasks.
                 */
                m_taskListsForSites.clear();
                for (SnapshotDataTarget sdt : targets) {
                    try {
                        sdt.close();
                    } catch (Exception e) {
                        HOST_LOG.error(ex);
                    }
                }

                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                ex.printStackTrace(pw);
                pw.flush();
                result.addRow(
                        Integer.parseInt(context.getSite().getHost().getTypeName()),
                        hostname,
                        "",
                        "FAILURE",
                        "SNAPSHOT INITIATION OF " + file_path + file_nonce +
                        "RESULTED IN Exception: \n" + sw.toString());
                HOST_LOG.error(ex);
            } finally {
                m_snapshotPermits.release(numLocalSites);
            }
        }

        try {
            m_snapshotPermits.acquire();
        } catch (Exception e) {
            result.addRow(Integer.parseInt(context.getSite().getHost().getTypeName()),
                    hostname,
                    "",
                    "FAILURE",
                    e.toString());
            return new DependencyPair( DEP_createSnapshotTargets, result);
        } finally {
            /*
             * The last thread to acquire a snapshot permit has to be the one
             * to release the setup permit to ensure that a thread
             * doesn't come late and think it is supposed to do the setup work
             */
            synchronized (m_snapshotPermits) {
                if (m_snapshotPermits.availablePermits() == 0 &&
                        m_snapshotCreateSetupPermit.availablePermits() == 0) {
                    m_snapshotCreateSetupPermit.release();
                }
            }
        }

        synchronized (m_taskListsForSites) {
            final Deque<SnapshotTableTask> m_taskList = m_taskListsForSites.poll();
            if (m_taskList == null) {
                return new DependencyPair( DEP_createSnapshotTargets, result);
            } else {
                if (m_taskListsForSites.isEmpty()) {
                    assert(m_snapshotCreateSetupPermit.availablePermits() == 1);
                    assert(m_snapshotPermits.availablePermits() == 0);
                }
                assert(SnapshotSiteProcessor.ExecutionSitesCurrentlySnapshotting.get() > 0);
                context.getExecutionSite().initiateSnapshots(m_taskList);
            }
        }

        if (block != 0) {
            HashSet<Exception> failures = null;
            String status = "SUCCESS";
            String err = "";
            try {
                failures = context.getExecutionSite().completeSnapshotWork();
            } catch (InterruptedException e) {
                status = "FAILURE";
                err = e.toString();
            }
            final VoltTable blockingResult = constructPartitionResultsTable();

            if (failures.isEmpty()) {
                blockingResult.addRow(
                        Integer.parseInt(context.getSite().getHost().getTypeName()),
                        hostname,
                        Integer.parseInt(context.getSite().getTypeName()),
                        status,
                        err);
            } else {
                status = "FAILURE";
                for (Exception e : failures) {
                    err = e.toString();
                }
                blockingResult.addRow(
                        Integer.parseInt(context.getSite().getHost().getTypeName()),
                        hostname,
                        Integer.parseInt(context.getSite().getTypeName()),
                        status,
                        err);
            }
            return new DependencyPair( DEP_createSnapshotTargets, blockingResult);
        }

        return new DependencyPair( DEP_createSnapshotTargets, result);
    }

    public VoltTable[] run(SystemProcedureExecutionContext ctx,
            String path, String nonce, long block) throws VoltAbortException
    {
        final long startTime = System.currentTimeMillis();
        HOST_LOG.info("Saving database to path: " + path + ", ID: " + nonce + " at " + System.currentTimeMillis());

        if (path == null || path.equals("")) {
            ColumnInfo[] result_columns = new ColumnInfo[1];
            int ii = 0;
            result_columns[ii++] = new ColumnInfo("ERR_MSG", VoltType.STRING);
            VoltTable results[] = new VoltTable[] { new VoltTable(result_columns) };
            results[0].addRow("Provided path was null or the empty string");
            return results;
        }

        if (nonce == null || nonce.equals("")) {
            ColumnInfo[] result_columns = new ColumnInfo[1];
            int ii = 0;
            result_columns[ii++] = new ColumnInfo("ERR_MSG", VoltType.STRING);
            VoltTable results[] = new VoltTable[] { new VoltTable(result_columns) };
            results[0].addRow("Provided nonce was null or the empty string");
            return results;
        }

        if (nonce.contains("-") || nonce.contains(",")) {
            ColumnInfo[] result_columns = new ColumnInfo[1];
            int ii = 0;
            result_columns[ii++] = new ColumnInfo("ERR_MSG", VoltType.STRING);
            VoltTable results[] = new VoltTable[] { new VoltTable(result_columns) };
            results[0].addRow("Provided nonce " + nonce + " contains a prohitibited character (- or ,)");
            return results;
        }

        // See if we think the save will succeed
        VoltTable[] results;
        results = performSaveFeasibilityWork(path, nonce);

        // Test feasibility results for fail
        while (results[0].advanceRow())
        {
            if (results[0].getString("RESULT").equals("FAILURE"))
            {
                // Something lost, bomb out and just return the whole
                // table of results to the client for analysis
                return results;
            }
        }

        results = performSnapshotCreationWork( path, nonce, startTime, (byte)block);

        final long finishTime = System.currentTimeMillis();
        final long duration = finishTime - startTime;
        HOST_LOG.info("Snapshot initiation took " + duration + " milliseconds");
        return results;
    }

    private final List<Table>
    getTablesToSave(Database database)
    {
        CatalogMap<Table> all_tables = database.getTables();
        ArrayList<Table> my_tables = new ArrayList<Table>();
        for (Table table : all_tables)
        {
            // Make a list of all non-materialized, non-export only tables
            if ((table.getMaterializer() != null) ||
                    (CatalogUtil.isTableExportOnly(database, table)))
            {
                continue;
            }
            my_tables.add(table);
        }
        return my_tables;
    }

    private final SnapshotDataTarget constructSnapshotDataTargetForTable(
                                             SystemProcedureExecutionContext context,
                                             File f,
                                             Table table,
                                             Host h,
                                             int numPartitions,
                                             long createTime) throws IOException
    {
        return new DefaultSnapshotDataTarget(
                f,
                Integer.parseInt(h.getTypeName()),
                context.getCluster().getTypeName(),
                context.getDatabase().getTypeName(),
                table.getTypeName(),
                numPartitions,
                table.getIsreplicated(),
                getPartitionsOnHost(context, h),
                CatalogUtil.getVoltTable(table),
                createTime);
    }

    private final VoltTable constructNodeResultsTable()
    {
        return new VoltTable(nodeResultsColumns);
    }

    public static final ColumnInfo nodeResultsColumns[] = new ColumnInfo[] {
        new ColumnInfo(CNAME_HOST_ID, CTYPE_ID),
        new ColumnInfo("HOSTNAME", VoltType.STRING),
        new ColumnInfo("TABLE", VoltType.STRING),
        new ColumnInfo("RESULT", VoltType.STRING),
        new ColumnInfo("ERR_MSG", VoltType.STRING)
    };

    public static final ColumnInfo partitionResultsColumns[] = new ColumnInfo[] {
        new ColumnInfo(CNAME_HOST_ID, CTYPE_ID),
        new ColumnInfo("HOSTNAME", VoltType.STRING),
        new ColumnInfo(CNAME_SITE_ID, CTYPE_ID),
        new ColumnInfo("RESULT", VoltType.STRING),
        new ColumnInfo("ERR_MSG", VoltType.STRING)
    };

    private final VoltTable constructPartitionResultsTable()
    {
        return new VoltTable(partitionResultsColumns);
    }

    private final VoltTable[] performSaveFeasibilityWork(String filePath,
                                                         String fileNonce)
    {
        SynthesizedPlanFragment[] pfs = new SynthesizedPlanFragment[2];

        // This fragment causes each execution site to confirm the likely
        // success of writing tables to disk
        pfs[0] = new SynthesizedPlanFragment();
        pfs[0].fragmentId = SysProcFragmentId.PF_saveTest;
        pfs[0].outputDepId = DEP_saveTest;
        pfs[0].inputDepIds = new int[] {};
        pfs[0].multipartition = true;
        ParameterSet params = new ParameterSet();
        params.setParameters(filePath, fileNonce);
        pfs[0].parameters = params;

        // This fragment aggregates the save-to-disk sanity check results
        pfs[1] = new SynthesizedPlanFragment();
        pfs[1].fragmentId = SysProcFragmentId.PF_saveTestResults;
        pfs[1].outputDepId = DEP_saveTestResults;
        pfs[1].inputDepIds = new int[] { DEP_saveTest };
        pfs[1].multipartition = false;
        pfs[1].parameters = new ParameterSet();

        VoltTable[] results;
        results = executeSysProcPlanFragments(pfs, DEP_saveTestResults);
        return results;
    }

    private final VoltTable[] performSnapshotCreationWork(String filePath,
            String fileNonce,
            long startTime,
            byte block)
    {
        SynthesizedPlanFragment[] pfs = new SynthesizedPlanFragment[2];

        // This fragment causes each execution site to confirm the likely
        // success of writing tables to disk
        pfs[0] = new SynthesizedPlanFragment();
        pfs[0].fragmentId = SysProcFragmentId.PF_createSnapshotTargets;
        pfs[0].outputDepId = DEP_createSnapshotTargets;
        pfs[0].inputDepIds = new int[] {};
        pfs[0].multipartition = true;
        ParameterSet params = new ParameterSet();
        params.setParameters(filePath, fileNonce, startTime, block);
        pfs[0].parameters = params;

        // This fragment aggregates the save-to-disk sanity check results
        pfs[1] = new SynthesizedPlanFragment();
        pfs[1].fragmentId = SysProcFragmentId.PF_createSnapshotTargetsResults;
        pfs[1].outputDepId = DEP_createSnapshotTargetsResults;
        pfs[1].inputDepIds = new int[] { DEP_createSnapshotTargets };
        pfs[1].multipartition = false;
        pfs[1].parameters = new ParameterSet();

        VoltTable[] results;
        results = executeSysProcPlanFragments(pfs, DEP_createSnapshotTargetsResults);
        return results;
    }

    private int[] getPartitionsOnHost(
            SystemProcedureExecutionContext c, Host h) {
        final ArrayList<Partition> results = new ArrayList<Partition>();
        for (final Site s : VoltDB.instance().getCatalogContext().siteTracker.getUpSites()) {
            if (s.getHost().getTypeName().equals(h.getTypeName())) {
                if (s.getPartition() != null) {
                    results.add(s.getPartition());
                }
            }
        }
        final int retval[] = new int[results.size()];
        int ii = 0;
        for (final Partition p : results) {
            retval[ii++] = Integer.parseInt(p.getTypeName());
        }
        return retval;
    }

    private final File constructFileForTable(Table table,
                                             String filePath,
                                             String fileNonce,
                                             String hostId)
    {
        return new File(filePath, SnapshotUtil.constructFilenameForTable(table, fileNonce, hostId));
    }
}

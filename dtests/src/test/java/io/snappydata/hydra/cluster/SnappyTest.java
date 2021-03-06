/*
 * Copyright (c) 2016 SnappyData, Inc. All rights reserved.
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
package io.snappydata.hydra.cluster;


import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.SystemFailure;
import hydra.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang.StringUtils;
import org.apache.spark.SparkContext;
import org.apache.spark.sql.SnappyContext;
import sql.SQLBB;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.dmlStatements.DMLStmtIF;
import sql.sqlutil.DMLStmtsFactory;
import util.*;

import java.io.*;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

//import util.*;

public class SnappyTest implements Serializable {

    private static transient SnappyContext snc = SnappyContext.apply(SnappyContext
            .globalSparkContext());
    protected static SnappyTest snappyTest;
    protected static HostDescription hd = TestConfig.getInstance().getMasterDescription()
            .getVmDescription().getHostDescription();
    private static char sep = hd.getFileSep();
    private static String gemfireHome = hd.getGemFireHome() + sep;
    private static String productDir = gemfireHome + ".." + sep + "snappy" + sep;
    private static String productConfDirPath = productDir + "conf" + sep;
    private static String productLibsDir = productDir + "lib" + sep;
    private static String productSbinDir = productDir + "sbin" + sep;
    private static String productBinDir = productDir + "bin" + sep;
    protected static String SnappyShellPath = productBinDir + "snappy-sql";
    private static String dtests = gemfireHome + ".." + sep + ".." + sep + ".." + sep + "dtests" + sep;
    private static String dtestsLibsDir = dtests + "build-artifacts" + sep + "scala-2.11" + sep + "libs" + sep;
    private static String dtestsResourceLocation = dtests + "src" + sep + "resources" + sep;
    private static String dtestsScriptLocation = dtestsResourceLocation + "scripts" + sep;
    private static String dtestsDataLocation = dtestsResourceLocation + "data" + sep;
    private static String quickstartScriptLocation = productDir + "quickstart" + sep + "scripts" + sep;
    private static String quickstartDataLocation = productDir + "quickstart" + sep + "data" + sep;
    private static String logFile = null;

    private static Set<Integer> pids = new LinkedHashSet<Integer>();
    private static Set<File> dirList = new LinkedHashSet<File>();
    public static String userAppJar = null;
    private static String simulateStreamScriptName = TestConfig.tab().stringAt(SnappyPrms.simulateStreamScriptName, "simulateFileStream");
    private static String simulateStreamScriptDestinationFolder = TestConfig.tab().stringAt(SnappyPrms.simulateStreamScriptDestinationFolder, dtests);
    public static boolean isLongRunningTest = TestConfig.tab().booleanAt(SnappyPrms.isLongRunningTest, false);  //default to false
    public static boolean useRowStore = TestConfig.tab().booleanAt(SnappyPrms.useRowStore, false);  //default to false
    public static boolean isRestarted = false;
    public static boolean useSplitMode = TestConfig.tab().booleanAt(SnappyPrms.useSplitMode, false);  //default to false
    public static boolean isStopMode = TestConfig.tab().booleanAt(SnappyPrms.isStopMode, false);  //default to false
    private static String primaryLocator = null;
    public static String leadHost = null;
    public static Long waitTimeBeforeStreamingJobStatus = TestConfig.tab().longAt(SnappyPrms.streamingJobExecutionTimeInMillis, 6000);
    private static Boolean logDirExists = false;
    private static Boolean doneCopying = false;
    private static Boolean doneRestore = false;
    private static Boolean diskDirExists = false;
    private static Boolean runGemXDQuery = false;
    protected static int[] dmlTables = SQLPrms.getTables();
    public static final Random random = new Random(SQLPrms.getRandSeed());
    protected static DMLStmtsFactory dmlFactory = new DMLStmtsFactory();

    protected static boolean cycleVms = TestConfig.tab().booleanAt(SnappyPrms.cycleVms, false);
    public static final String LASTCYCLEDTIME = "lastCycledTime"; //used in SnappyBB
    public static final String LASTCYCLEDTIMEFORLEAD = "lastCycledTimeForLead"; //used in SnappyBB
    public static long lastCycledTime = 0;
    public static long lastCycledTimeForLead = 0;
    public static int waitTimeBeforeNextCycleVM = TestConfig.tab().intAt(SnappyPrms.waitTimeBeforeNextCycleVM, 20); //secs
    public static final int THOUSAND = 1000;
    public static String cycleVMTarget = TestConfig.tab().stringAt(SnappyPrms.cycleVMTarget, "snappyStore");
    public static String cycleLeadVMTarget = TestConfig.tab().stringAt(SnappyPrms.cycleVMTarget, "lead");
    public static final String LEAD_PORT = "8090";
    public static final String MASTER_PORT = "7077";
    private static int jobSubmissionCount = 0;
    protected static String jarPath = gemfireHome + ".." + sep + ".." + sep + ".." + sep;

    private Connection connection = null;
    private static HydraThreadLocal localconnection = new HydraThreadLocal();

    public enum SnappyNode {
        LOCATOR, SERVER, LEAD, WORKER
    }

    SnappyNode snappyNode;

    public SnappyTest() {
    }

    public SnappyTest(SnappyNode snappyNode) {
        this.snappyNode = snappyNode;
    }

    public static void HydraTask_stopSnappy() {
        SparkContext sc = SnappyContext.globalSparkContext();
        if (sc != null) sc.stop();
        Log.getLogWriter().info("SnappyContext stopped successfully");
    }

    public static synchronized void HydraTask_initializeSnappyTest() {
        if (snappyTest == null) {
            snappyTest = new SnappyTest();
            snappyTest.getClientHostDescription();
            int tid = RemoteTestModule.getCurrentThread().getThreadId();
            if (tid == 0) {
                snappyTest.generateConfig("locators");
                snappyTest.generateConfig("servers");
                snappyTest.generateConfig("leads");
                if (useSplitMode) {
                    snappyTest.generateConfig("slaves");
                    snappyTest.generateConfig("spark-env.sh");
                }
                if (isLongRunningTest) {
                    snappyTest.generateConfig("locatorConnInfo");
                    snappyTest.generateConfig("leadHost");
                }
            }
        }
    }

    protected String getStoreTestsJar() {
        String storeTestsJar = hd.getTestDir() + hd.getFileSep() + ".." + hd.getFileSep() + ".." + hd.getFileSep() + "libs" + hd.getFileSep() + "snappydata-store-hydra-tests-" +
                ProductVersionHelper.getInfo().getProperty(ProductVersionHelper.SNAPPYRELEASEVERSION) + "-all.jar";
        return storeTestsJar;
    }

    protected String getSnappyTestsJar() {
        String snappyTestsJar = getUserAppJarLocation("snappydata-store-scala-tests*.jar", hd.getGemFireHome() + hd.getFileSep() + ".." + hd.getFileSep() + ".." + hd.getFileSep() + ".." + hd.getFileSep() + "dtests" + hd.getFileSep() +
                "build-artifacts" + hd.getFileSep() + "scala-2.11" + hd.getFileSep() + "libs");
        return snappyTestsJar;
    }

    protected void getClientHostDescription() {
        hd = TestConfig.getInstance()
                .getClientDescription(RemoteTestModule.getMyClientName())
                .getVmDescription().getHostDescription();
    }

    protected static String getUserAppJarLocation(final String jarName, String jarPath) {
        String userAppJarPath = null;
        File baseDir = new File(jarPath);
        try {
            IOFileFilter filter = new WildcardFileFilter(jarName);
            List<File> files = (List<File>) FileUtils.listFiles(baseDir, filter, TrueFileFilter.INSTANCE);
            Log.getLogWriter().info("Jar file found: " + Arrays.asList(files));
            for (File file1 : files) {
                if (!file1.getAbsolutePath().contains("/work/") || !file1.getAbsolutePath().contains("/scala-2.10/"))
                    userAppJarPath = file1.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.getLogWriter().info("Unable to find " + jarName + " jar at " + jarPath + " location.");
        }
        return userAppJarPath;
    }

    protected String getDataLocation(String paramName) {
        if (paramName.equals(" ")) return paramName;
        String scriptPath = null;
        if (new File(paramName).exists()) {
            return paramName;
        } else {
            scriptPath = quickstartDataLocation + paramName;
            if (new File(scriptPath).exists()) return scriptPath;
            else scriptPath = dtestsDataLocation + paramName;
            if (new File(scriptPath).exists()) return scriptPath;
            else {
                String s = "Data doesn't exists at any expected location.";
                throw new TestException(s);
            }
        }
    }

    public String getScriptLocation(String scriptName) {
        String scriptPath = null;
        if (new File(scriptName).exists()) return scriptName;
        scriptPath = productSbinDir + scriptName;
        if (!new File(scriptPath).exists()) {
            scriptPath = productBinDir + scriptName;
            if (new File(scriptPath).exists()) return scriptPath;
            else
                scriptPath = getUserAppJarLocation(scriptName, dtestsScriptLocation);
            if (new File(scriptPath).exists()) return scriptPath;
            else
                scriptPath = quickstartScriptLocation + scriptName;
            if (new File(scriptPath).exists()) return scriptPath;
            else {
                String s = "Unable to find the script at any expected location.";
                throw new TestException(s);
            }
        }
        return scriptPath;
    }

    /**
     * Generates the configuration data required to start the snappy locator.
     */
    public static synchronized void HydraTask_generateSnappyLocatorConfig() {
        SnappyTest locator = new SnappyTest(SnappyNode.LOCATOR);
        locator.generateNodeConfig("locatorLogDir");
    }

    /**
     * Generates the configuration data required to start the snappy Server.
     */
    public static synchronized void HydraTask_generateSnappyServerConfig() {
        SnappyTest server = new SnappyTest(SnappyNode.SERVER);
        server.generateNodeConfig("serverLogDir");
    }

    /**
     * Generates the configuration data required to start the snappy Server.
     */
    public static synchronized void HydraTask_generateSnappyLeadConfig() {
        SnappyTest lead = new SnappyTest(SnappyNode.LEAD);
        lead.generateNodeConfig("leadLogDir");
    }

    /**
     * Generates the configuration data required to start the snappy Server.
     */
    public static synchronized void HydraTask_generateSparkWorkerConfig() {
        SnappyTest worker = new SnappyTest(SnappyNode.WORKER);
        worker.generateNodeConfig("workerLogDir");
    }

    protected void generateNodeConfig(String logDir) {
        if (logDirExists) return;
        String addr = HostHelper.getHostAddress();
        int port = PortHelper.getRandomPort();
        String endpoint = addr + ":" + port;
        String clientPort = " -client-port=";
        String locators = " -locators=";
        String locPortString = " -peer-discovery-port=";
        String locatorsList = null;
        String dirPath = snappyTest.getLogDir();
        String nodeLogDir = null;
        String hostnameForPrimaryLocator = null;
        switch (snappyNode) {
            case LOCATOR:
                int locPort;
                do locPort = PortHelper.getRandomPort();
                while (locPort < 0 || locPort > 65535);
                nodeLogDir = HostHelper.getLocalHost() + " -dir=" + dirPath + clientPort + port + locPortString + locPort + SnappyPrms.getTimeStatistics() + SnappyPrms.getLogLevel();
                SnappyBB.getBB().getSharedMap().put("locatorHost" + "_" + RemoteTestModule.getMyVmid(), HostHelper.getLocalHost());
                SnappyBB.getBB().getSharedMap().put("locatorPort" + "_" + RemoteTestModule.getMyVmid(), Integer.toString(port));
                SnappyBB.getBB().getSharedMap().put("locatorMcastPort" + "_" + RemoteTestModule.getMyVmid(), Integer.toString(locPort));
                SnappyBB.getBB().getSharedMap().put("locators" + "_" + RemoteTestModule.getMyVmid(), HostHelper.getLocalHost() + ":" + Integer.toString(locPort));
                SnappyBB.getBB().getSharedMap().put(Integer.toString(locPort), HostHelper.getLocalHost());
                Log.getLogWriter().info("Generated locator endpoint: " + endpoint);
                SnappyNetworkServerBB.getBB().getSharedMap().put("locator" + "_" + RemoteTestModule.getMyVmid(), endpoint);
                int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.primaryLocatorStarted);
                if (num == 1) {
                    primaryLocator = endpoint;
                    hostnameForPrimaryLocator = HostHelper.getLocalHost();
                    SnappyBB.getBB().getSharedMap().put("primaryLocatorHost", hostnameForPrimaryLocator);
                    SnappyBB.getBB().getSharedMap().put("primaryLocatorPort", Integer.toString(port));
                }
                break;
            case SERVER:
                locatorsList = getLocatorsList("locators");
                nodeLogDir = HostHelper.getLocalHost() + locators + locatorsList + " -dir=" + dirPath + clientPort + port +
                        " -heap-size=" + SnappyPrms.getServerMemory() + " -conserve-sockets=" + SnappyPrms.getConserveSockets() +
                        " -J-Dgemfirexd.table-default-partitioned=" + SnappyPrms.getTableDefaultDataPolicy() + SnappyPrms.getTimeStatistics() +
                        SnappyPrms.getLogLevel() + SnappyPrms.getCriticalHeapPercentage() + SnappyPrms.getEvictionHeapPercentage() +
                        " -J-Dgemfire.CacheServerLauncher.SHUTDOWN_WAIT_TIME_MS=50000" + SnappyPrms.getFlightRecorderOptions(dirPath) +
                        " -classpath=" + getSnappyTestsJar() + ":" + getStoreTestsJar();
                Log.getLogWriter().info("Generated peer server endpoint: " + endpoint);
                SnappyBB.getBB().getSharedCounters().increment(SnappyBB.numServers);
                SnappyNetworkServerBB.getBB().getSharedMap().put("server" + "_" + RemoteTestModule.getMyVmid(), endpoint);
                break;
            case LEAD:
                locatorsList = getLocatorsList("locators");
                String leadHost;
                int leadPort = PortHelper.getRandomPort();
                /*do leadPort = PortHelper.getRandomPort();
                while (leadPort < 8091 || leadPort > 8099);*/
                nodeLogDir = HostHelper.getLocalHost() + locators + locatorsList + " -spark.executor.cores=" + SnappyPrms.getExecutorCores() +
                        " -spark.driver.maxResultSize=" + SnappyPrms.getDriverMaxResultSize() + " -dir=" + dirPath + clientPort + port +
                        " -heap-size=" + SnappyPrms.getLeadMemory() + " -spark.sql.autoBroadcastJoinThreshold=" + SnappyPrms.getSparkSqlBroadcastJoinThreshold() +
                        " -spark.jobserver.port=" + leadPort +
                        " -spark.scheduler.mode=" + SnappyPrms.getSparkSchedulerMode() + " -spark.sql.inMemoryColumnarStorage.compressed=" + SnappyPrms.getCompressedInMemoryColumnarStorage() +
                        " -spark.sql.inMemoryColumnarStorage.batchSize=" + SnappyPrms.getInMemoryColumnarStorageBatchSize() + " -conserve-sockets=" + SnappyPrms.getConserveSockets() +
                        " -table-default-partitioned=" + SnappyPrms.getTableDefaultDataPolicy() + SnappyPrms.getTimeStatistics() + SnappyPrms.getLogLevel() +
                        " -spark.sql.aqp.numBootStrapTrials=" + SnappyPrms.getNumBootStrapTrials() + SnappyPrms.getClosedFormEstimates() + SnappyPrms.getZeppelinInterpreter() +
                        " -classpath=" + getSnappyTestsJar() + ":" + getStoreTestsJar() + " -J-Dgemfire.CacheServerLauncher.SHUTDOWN_WAIT_TIME_MS=50000" +
                        SnappyPrms.getFlightRecorderOptions(dirPath) +
                        " -spark.driver.extraClassPath=" + getSnappyTestsJar() + ":" + getStoreTestsJar() + " -spark.executor.extraClassPath=" +
                        getSnappyTestsJar() + ":" + getStoreTestsJar();
                try {
                    leadHost = HostHelper.getIPAddress().getLocalHost().getHostName();
                } catch (UnknownHostException e) {
                    String s = "Lead host not found...";
                    throw new HydraRuntimeException(s, e);
                }
                SnappyBB.getBB().getSharedMap().put("leadHost_" + RemoteTestModule.getMyClientName() + "_" + RemoteTestModule.getMyVmid(), leadHost);
                SnappyBB.getBB().getSharedMap().put("leadPort_" + RemoteTestModule
                        .getMyClientName() + "_" + RemoteTestModule.getMyVmid(), Integer.toString(leadPort));
                break;
            case WORKER:
                nodeLogDir = HostHelper.getLocalHost();
                String sparkLogDir = "SPARK_LOG_DIR=" + hd.getUserDir();
                SnappyBB.getBB().getSharedMap().put("sparkLogDir" + "_" + snappyTest.getMyTid(), sparkLogDir);
                break;
        }
        SnappyBB.getBB().getSharedMap().put(logDir + "_" + RemoteTestModule.getMyVmid() + "_" + snappyTest.getMyTid(), nodeLogDir);
        SnappyBB.getBB().getSharedMap().put("logDir_" + RemoteTestModule.getMyClientName() + "_" + RemoteTestModule.getMyVmid(), dirPath);
        Log.getLogWriter().info("nodeLogDir is : " + nodeLogDir);
        logDirExists = true;
    }

    protected static Set<String> getFileContents(String userKey, Set<String> fileContents) {
        Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
        for (String key : keys) {
            if (key.startsWith(userKey)) {
                Log.getLogWriter().info("Key Found..." + key);
                String value = (String) SnappyBB.getBB().getSharedMap().get(key);
                fileContents.add(value);
            }
        }
        return fileContents;
    }

    protected static ArrayList<String> getWorkerFileContents(String userKey, ArrayList<String> fileContents) {
        Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
        for (String key : keys) {
            if (key.startsWith(userKey)) {
                Log.getLogWriter().info("Key Found..." + key);
                String value = (String) SnappyBB.getBB().getSharedMap().get(key);
                fileContents.add(value);
            }
        }
        Log.getLogWriter().info("ArrayList contains : " + fileContents.toString());
        return fileContents;
    }

    protected static Set<File> getDirList(String userKey) {
        Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
        for (String key : keys) {
            if (key.startsWith(userKey)) {
                Log.getLogWriter().info("Key Found..." + key);
                File value = (File) SnappyBB.getBB().getSharedMap().get(key);
                dirList.add(value);
            }
        }
        return dirList;
    }

    protected static String getLocatorsList(String userKey) {
        Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
        String locatorsList = null;
        Set<String> locatorHostPortList = new LinkedHashSet<>();
        for (String key : keys) {
            if (key.startsWith(userKey)) {
                Log.getLogWriter().info("Key Found..." + key);
                String value = (String) SnappyBB.getBB().getSharedMap().get(key);
                locatorHostPortList.add(value);
            }
        }
        if (locatorHostPortList.size() == 0) {
            return "";
        }
        locatorsList = StringUtils.join(locatorHostPortList, ",");
        return locatorsList;
    }

    /**
     * Write the configuration data required to start the snappy locator/s in locators file under conf directory at snappy build location.
     */
    public static void HydraTask_writeLocatorConfigData() {
        snappyTest.writeLocatorConfigData("locators", "locatorLogDir");
        if (isLongRunningTest) writeLocatorConnectionInfo();
    }

    /**
     * Write the configuration data required to start the snappy server/s in servers file under conf directory at snappy build location.
     */
    public static void HydraTask_writeServerConfigData() {
        snappyTest.writeConfigData("servers", "serverLogDir");
    }

    /**
     * Write the configuration data required to start the snappy lead/s in leads file under conf directory at snappy build location.
     */
    public static void HydraTask_writeLeadConfigData() {
        snappyTest.writeConfigData("leads", "leadLogDir");
        if (isLongRunningTest) writeLeadHostInfo();
    }

    /**
     * Write the configuration data required to start the spark worker/s in slaves file and the log directory locations in spark-env.sh file under conf directory at snappy build location.
     */
    public static void HydraTask_writeWorkerConfigData() {
        snappyTest.writeWorkerConfigData("slaves", "workerLogDir");
        snappyTest.writeConfigData("spark-env.sh", "sparkLogDir");
    }

    protected void writeConfigData(String fileName, String logDir) {
        String filePath = productConfDirPath + fileName;
        File file = new File(filePath);
        if (fileName.equalsIgnoreCase("spark-env.sh")) file.setExecutable(true);
        Set<String> fileContent = new LinkedHashSet<String>();
        fileContent = snappyTest.getFileContents(logDir, fileContent);
        if (fileContent.size() == 0) {
            String s = "No data found for writing to " + fileName + " file under conf directory";
            throw new TestException(s);
        }
        for (String s : fileContent) {
            snappyTest.writeToFile(s, file);
        }
    }

    protected void writeLocatorConfigData(String fileName, String logDir) {
        String filePath = productConfDirPath + fileName;
        File file = new File(filePath);
        String peerDiscoveryPort = null;
        Set<String> fileContent = new LinkedHashSet<String>();
        fileContent = snappyTest.getFileContents(logDir, fileContent);
        if (fileContent.size() == 0) {
            String s = "No data found for writing to " + fileName + " file under conf directory";
            throw new TestException(s);
        }
        String locatorsList = getLocatorsList("locators");
        for (String s : fileContent) {
            String[] splited = s.split(" ");
            for (String str : splited) {
                String peerDiscoveryPortString = "-peer-discovery-port=";
                if (str.contains(peerDiscoveryPortString)) {
                    peerDiscoveryPort = str.substring(str.indexOf("=") + 1);
                }
            }
            String host = (String) SnappyBB.getBB().getSharedMap().get(peerDiscoveryPort);
            String replaceString = host + ":" + peerDiscoveryPort + ",";
            String newLocatorsList;
            if (locatorsList.contains(replaceString)) {
                newLocatorsList = locatorsList.replace(replaceString, "");
            } else {
                replaceString = host + ":" + peerDiscoveryPort;
                newLocatorsList = locatorsList.replace(replaceString, "");
            }
            String nodeLogDir = s.concat(" -locators=" + newLocatorsList);
            snappyTest.writeToFile(nodeLogDir, file);
        }
    }

    protected void writeNodeConfigData(String fileName, String nodeLogDir) {
        String filePath = productConfDirPath + fileName;
        File file = new File(filePath);
        snappyTest.writeToFile(nodeLogDir, file);
    }

    protected void writeWorkerConfigData(String fileName, String logDir) {
        String filePath = productConfDirPath + fileName;
        File file = new File(filePath);
        ArrayList<String> fileContent = new ArrayList<>();
        fileContent = snappyTest.getWorkerFileContents(logDir, fileContent);
        if (fileContent.size() == 0) {
            String s = "No data found for writing to " + fileName + " file under conf directory";
            throw new TestException(s);
        }
        for (String s : fileContent) {
            snappyTest.writeToFile(s, file);
        }
    }

    /**
     * Returns all network locator endpoints from the {@link
     * SnappyNetworkServerBB} map, a possibly empty list.  This includes all
     * network servers that have ever started, regardless of their distributed
     * system or current active status.
     */
    public static List getNetworkLocatorEndpoints() {
        return getEndpoints("locator");
    }

    /**
     * Returns all network server endpoints from the {@link
     * SnappyNetworkServerBB} map, a possibly empty list.  This includes all
     * network servers that have ever started, regardless of their distributed
     * system or current active status.
     */
    public static List getNetworkServerEndpoints() {
        return getEndpoints("server");
    }

    protected HashMap getclientHostPort() {
        HashMap<String, Integer> hostPort = new HashMap<String, Integer>();
        String endpoint = null;
        List<String> endpoints = getNetworkLocatorEndpoints();
        if (endpoints.size() == 0) {
            if (isLongRunningTest) {
                endpoints = getLocatorEndpointFromFile();
            }
        }
        endpoint = endpoints.get(0);
        String host = endpoint.substring(0, endpoint.indexOf(":"));
        Log.getLogWriter().info("Client Host is:" + host);
        String port = endpoint.substring(endpoint.indexOf(":") + 1);
        int clientPort = Integer.parseInt(port);
        Log.getLogWriter().info("Client Port is :" + clientPort);
        hostPort.put(host, clientPort);
        return hostPort;
    }

    /**
     * Returns all endpoints of the given type.
     */
    private static synchronized List<String> getEndpoints(String type) {
        List<String> endpoints = new ArrayList();
        Set<String> keys = SnappyNetworkServerBB.getBB().getSharedMap().getMap().keySet();
        for (String key : keys) {
            if (key.startsWith(type.toString())) {
                String endpoint = (String) SnappyNetworkServerBB.getBB().getSharedMap().getMap().get(key);
                endpoints.add(endpoint);
            }
        }
        Log.getLogWriter().info("Returning endpoint list: " + endpoints);
        return endpoints;
    }


    /**
     * Returns PIDs for all the processes started in the test, e.g. locator, server, lead .
     */
    private static synchronized List<String> getPidList() {
        List<String> pidList = new ArrayList();
        Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
        for (String key : keys) {
            if (key.startsWith("pid")) {
                String pid = (String) SnappyBB.getBB().getSharedMap().getMap().get(key);
                pidList.add(pid);
            }
        }
        Log.getLogWriter().info("Returning pid list: " + pidList);
        return pidList;
    }

    /**
     * Returns primary lead port .
     */
    private static synchronized String getPrimaryLeadPort(String clientName) {
        List<String> portList = new ArrayList();
        String port = null;
        Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
        for (String key : keys) {
            if (key.startsWith("leadPort_") && key.contains(clientName)) {
                port = (String) SnappyBB.getBB().getSharedMap().getMap().get(key);
            }
        }
        Log.getLogWriter().info("Returning primary lead port: " + port);
        return port;
    }


    protected void initHydraThreadLocals() {
        this.connection = getConnection();
    }

    protected Connection getConnection() {
        Connection connection = (Connection) localconnection.get();
        return connection;
    }

    protected void setConnection(Connection connection) {
        localconnection.set(connection);
    }

    protected void updateHydraThreadLocals() {
        setConnection(this.connection);
    }

    public static void HydraTask_getClientConnection_Snappy() throws SQLException {
        SnappyTest st = new SnappyTest();
        st.connectThinClient();
        st.updateHydraThreadLocals();
    }

    private void connectThinClient() throws SQLException {
        connection = getLocatorConnection();
    }

    public static Connection getClientConnection() {
        SnappyTest st = new SnappyTest();
        st.initHydraThreadLocals();
        return st.getConnection();
    }

    public static void HydraTask_getClientConnection() throws SQLException {
        getLocatorConnection();
    }

    /**
     * Mandatory to use this method in case of HA test.
     * As per current implementation, for starting the server snappy-servers.sh script is used, which starts
     * the servers based on the data in servers conf file.
     * In HA test, the framework deletes the old servers file and creates the new one with the config data specific
     * to server which is getting recycled.
     * So, we need to backup the original servers conf file. This will be required at the end of the test for stopping all servers
     * which have been started in the test.
     **/
    public static synchronized void backUpServerConfigData() {
        snappyTest.copyConfigData("servers");
    }

    /**
     * Mandatory to use this method in case of HA test.
     * As per current implementation, for starting the server snappy-servers.sh script is used, which starts
     * the servers based on the data in servers conf file.
     * In HA test, the framework deletes the old servers file and creates the new one with the config data specific
     * to server which is getting recycled.
     * So, we need to restore the original servers conf file. This will be required at the end of the test for stopping all servers
     * which have been started in the test.
     **/
    public static synchronized void restoreServerConfigData() {
        snappyTest.restoreConfigData("servers");
    }

    /**
     * Mandatory to use this method in case of HA test.
     * As per current implementation, for starting the lead members, snappy-leads.sh script is used, which starts
     * the lead members based on the data in leads conf file.
     * In HA test, the framework deletes the old leads file and creates the new one with the config data specific
     * to lead member which is getting recycled.
     * So, we need to restore the original leads conf file. This will be required at the end of the test for stopping all leads
     * which have been started in the test.
     **/
    public static synchronized void restoreLeadConfigData() {
        snappyTest.restoreConfigData("leads");
    }

    /**
     * Mandatory to use this method in case of HA test.
     * As per current implementation, for starting the lead members, snappy-leads.sh script is used, which starts
     * the lead members based on the data in leads conf file.
     * In HA test, the framework deletes the old leads file and creates the new one with the config data specific
     * to lead member which is getting recycled.
     * So, we need to backup the original leads conf file. This will be required at the end of the test for stopping all leads
     * which have been started in the test.
     **/
    public static synchronized void backUpLeadConfigData() {
        snappyTest.copyConfigData("leads");
    }

    protected void copyConfigData(String fileName) {
        if (doneCopying) return;
        String filePath = productConfDirPath + fileName;
        File srcFile = new File(filePath);
        try {
            File destDir = new File(".");
            FileUtils.copyFileToDirectory(srcFile, destDir);
            Log.getLogWriter().info("Done copying " + fileName + " file from " + srcFile + " to " + destDir.getAbsolutePath());
        } catch (IOException e) {
            throw new TestException("Error occurred while copying data from file: " + srcFile + "\n " + e.getMessage());
        }
        doneCopying = true;
    }

    protected void restoreConfigData(String fileName) {
        if (doneRestore) return;
        String filePath = productConfDirPath + fileName;
        File srcDir = new File(".");
        File srcFile = null, destDir = null;
        try {
            String srcFilePath = srcDir.getCanonicalPath() + File.separator + fileName;
            srcFile = new File(srcFilePath);
            destDir = new File(filePath);
            if (destDir.exists()) destDir.delete();
            destDir = new File(productConfDirPath);
            FileUtils.copyFileToDirectory(srcFile, destDir);
            Log.getLogWriter().info("Done restoring " + fileName + " file from " + srcFile.getAbsolutePath() + " to " + destDir);
        } catch (IOException e) {
            throw new TestException("Error occurred while copying data from file: " + srcFile + "\n " + e.getMessage());
        }
        doneRestore = true;
    }

    public static synchronized void HydraTask_copyDiskFiles() {
        if (diskDirExists) return;
        else {
            String dirName = snappyTest.generateLogDirName();
            File destDir = new File(dirName);
            String diskDirName = dirName.substring(0, dirName.lastIndexOf("_")) + "_disk";
            File dir = new File(diskDirName);
            for (File srcFile : dir.listFiles()) {
                try {
                    if (srcFile.isDirectory()) {
                        FileUtils.copyDirectoryToDirectory(srcFile, destDir);
                        Log.getLogWriter().info("Done copying diskDirFile directory from: " + srcFile + " to " + destDir);
                    } else {
                        FileUtils.copyFileToDirectory(srcFile, destDir);
                        Log.getLogWriter().info("Done copying diskDirFile from: " + srcFile + " to " + destDir);
                    }
                } catch (IOException e) {
                    throw new TestException("Error occurred while copying data from file: " + srcFile + "\n " + e.getMessage());
                }
            }
            diskDirExists = true;
        }
    }

    public static synchronized void HydraTask_copyDiskFiles_gemToSnappyCluster() {
        Set<File> myDirList = getDirList("dirName_");
        if (diskDirExists) return;
        else {
            String dirName = snappyTest.generateLogDirName();
            File destDir = new File(dirName);
            String[] splitedName = RemoteTestModule.getMyClientName().split("snappy");
            String newName = splitedName[1];
            File currentDir = new File(".");
            for (File srcFile1 : currentDir.listFiles()) {
                if (!doneCopying) {
                    if (srcFile1.getAbsolutePath().contains(newName) && srcFile1.getAbsolutePath().contains("_disk")) {
                        if (myDirList.contains(srcFile1)) {
                            Log.getLogWriter().info("List contains entry for the file... " + myDirList.toString());
                        } else {
                            SnappyBB.getBB().getSharedMap().put("dirName_" + RemoteTestModule.getMyPid() + "_" + snappyTest.getMyTid(), srcFile1);
                            File dir = new File(srcFile1.getAbsolutePath());
                            Log.getLogWriter().info("Match found for file: " + srcFile1.getAbsolutePath());
                            for (File srcFile : dir.listFiles()) {
                                try {
                                    if (srcFile.isDirectory()) {
                                        FileUtils.copyDirectoryToDirectory(srcFile, destDir);
                                        Log.getLogWriter().info("Done copying diskDirFile directory from ::" + srcFile + "to " + destDir);
                                    } else {
                                        FileUtils.copyFileToDirectory(srcFile, destDir);
                                        Log.getLogWriter().info("Done copying diskDirFile from ::" + srcFile + "to " + destDir);
                                    }
                                    doneCopying = true;
                                } catch (IOException e) {
                                    throw new TestException("Error occurred while copying data from file: " + srcFile + "\n " + e.getMessage());
                                }
                            }
                        }
                    }
                }
            }
            diskDirExists = true;
        }
    }

    public static void HydraTask_doDMLOp() {
        snappyTest.doDMLOp();
    }

    protected void doDMLOp() {
        runGemXDQuery = true;
        try {
            Connection conn = getLocatorConnection();
            doDMLOp(conn);
            closeConnection(conn);
        } catch (SQLException e) {
            throw new TestException("Not able to get connection " + TestHelper.getStackTrace(e));
        }
    }

    protected void doDMLOp(Connection conn) {
        //No derby connection required for snappyTest. So providign the bull connection to existing methods
        Connection dConn = null;
//        protected void doDMLOp(Connection dConn, Connection gConn) {
        //perform the opeartions
        //randomly select a table to perform dml
        //randomly select an operation to perform based on the dmlStmt (insert, update, delete, select)
        Log.getLogWriter().info("doDMLOp-performing dmlOp, myTid is " + getMyTid());
        int table = dmlTables[random.nextInt(dmlTables.length)]; //get random table to perform dml
        DMLStmtIF dmlStmt = dmlFactory.createDMLStmt(table); //dmlStmt of a table
        int numOfOp = random.nextInt(5) + 1;
        int size = 1;

        String operation = TestConfig.tab().stringAt(SQLPrms.dmlOperations);
        Log.getLogWriter().info("doDMLOp-operation=" + operation + "  numOfOp=" + numOfOp);
        if (operation.equals("insert")) {
            for (int i = 0; i < numOfOp; i++) {
                dmlStmt.insert(dConn, conn, size);
                commit(conn);
                SnappyBB.getBB().getSharedCounters().increment(SnappyBB.insertCounter);
            }
        } else if (operation.equals("put")) {
            for (int i = 0; i < numOfOp; i++) {
                dmlStmt.put(dConn, conn, size);
                commit(conn);
                SnappyBB.getBB().getSharedCounters().increment(SnappyBB.insertCounter);
            }
        } else if (operation.equals("update")) {
            for (int i = 0; i < numOfOp; i++) {
                dmlStmt.update(dConn, conn, size);
                commit(conn);
                SnappyBB.getBB().getSharedCounters().increment(SnappyBB.updateCounter);
            }
        } else if (operation.equals("delete")) {
            dmlStmt.delete(dConn, conn);
            SnappyBB.getBB().getSharedCounters().increment(SnappyBB.deleteCounter);
        } else if (operation.equals("query")) {
            dmlStmt.query(dConn, conn);
            SnappyBB.getBB().getSharedCounters().increment(SnappyBB.queryCounter);

        } else {
            throw new TestException("Unknown entry operation: " + operation);
        }
        commit(conn);
    }

    /**
     * Writes the locator host:port information to the locatorConnInfo file under conf directory.
     */
    protected static void writeLocatorConnectionInfo() {
        List<String> endpoints = validateLocatorEndpointData();
        snappyTest.writeNodeConfigData("locatorConnInfo", endpoints.get(0));
    }

    /**
     * Writes the lead host information to the leadHost file under conf directory.
     */
    protected static void writeLeadHostInfo() {
        leadHost = (String) SnappyBB.getBB().getSharedMap().get("leadHost");
        snappyTest.writeNodeConfigData("leadHost", leadHost);
    }

    protected static String getLeadHostFromFile() {
        File logFile = getLogFile("leadHost");
        try {
            BufferedReader br = readDataFromFile(logFile);
            String str = null;
            while ((str = br.readLine()) != null) {
                leadHost = str;
            }
            br.close();
        } catch (IOException e) {
            String s = "Problem while reading the file : " + logFile.getAbsolutePath();
            throw new TestException(s, e);
        }
        return leadHost;
    }

    protected static BufferedReader readDataFromFile(File filename) {
        BufferedReader br = null;
        try {
            FileInputStream fis = new FileInputStream(filename);
            br = new BufferedReader(new InputStreamReader(fis));
        } catch (FileNotFoundException e) {
            String s = "Unable to find file: " + filename.getAbsolutePath();
            throw new TestException(s);
        }
        return br;
    }

    protected static File getLogFile(String filename) {
        String dest = productConfDirPath + filename;
        File logFile = new File(dest);
        return logFile;
    }

    protected static List<String> getLocatorEndpointFromFile() {
        List<String> endpoints = new ArrayList<String>();
        File logFile = getLogFile("locatorConnInfo");
        try {
            BufferedReader br = readDataFromFile(logFile);
            String str = null;
            while ((str = br.readLine()) != null) {
                endpoints.add(str);
            }
            br.close();
        } catch (IOException e) {
            String s = "Problem while reading the file : " + logFile.getAbsolutePath();
            throw new TestException(s, e);
        }
        return endpoints;
    }

    protected static List<String> validateLocatorEndpointData() {
        List<String> endpoints = getNetworkLocatorEndpoints();
        if (endpoints.size() == 0) {
            if (isLongRunningTest) {
                endpoints = getLocatorEndpointFromFile();
            }
        }
        if (endpoints.size() == 0) {
            String s = "No network locator endpoints found";
            throw new TestException(s);
        }
        return endpoints;
    }

    /**
     * Gets Client connection.
     */
    public static Connection getLocatorConnection() throws SQLException {
        List<String> endpoints = validateLocatorEndpointData();
        Connection conn = null;
        if (!runGemXDQuery) {
            String url = "jdbc:snappydata://" + endpoints.get(0);
            Log.getLogWriter().info("url is " + url);
            conn = getConnection(url, "io.snappydata.jdbc.ClientDriver");
        } else {
            String url = "jdbc:gemfirexd://" + endpoints.get(0);
            Log.getLogWriter().info("url is " + url);
            conn = getConnection(url, "io.snappydata.jdbc.ClientDriver");
        }
        return conn;
    }

    private static Connection getConnection(String protocol, String driver) throws SQLException {
        Log.getLogWriter().info("Creating connection using " + driver + " with " + protocol);
        loadDriver(driver);
        Connection conn = DriverManager.getConnection(protocol);
        return conn;
    }

    public static void closeConnection(Connection conn) {
        try {
            conn.close();
        } catch (SQLException e) {
            SQLHelper.printSQLException(e);
            throw new TestException("Not able to release the connection " + TestHelper.getStackTrace(e));
        }
    }

    public void commit(Connection conn) {
        if (conn == null) return;
        try {
            Log.getLogWriter().info("committing the ops.. ");
            conn.commit();
        } catch (SQLException se) {
            SQLHelper.handleSQLException(se);
        }
    }

    /**
     * The JDBC driver is loaded by loading its class.  If you are using JDBC 4.0
     * (Java SE 6) or newer, JDBC drivers may be automatically loaded, making
     * this code optional.
     * <p/>
     * In an embedded environment, any static Derby system properties
     * must be set before loading the driver to take effect.
     */
    public static void loadDriver(String driver) {
        try {
            Class.forName(driver).newInstance();
        } catch (ClassNotFoundException e) {
            String s = "Problem loading JDBC driver: " + driver;
            throw new TestException(s, e);
        } catch (InstantiationException e) {
            String s = "Problem loading JDBC driver: " + driver;
            throw new TestException(s, e);
        } catch (IllegalAccessException e) {
            String s = "Problem loading JDBC driver: " + driver;
            throw new TestException(s, e);
        }
    }

    public static void runQuery() throws SQLException {
        Connection conn = getLocatorConnection();
        String query1 = "SELECT count(*) FROM airline";
        ResultSet rs = conn.createStatement().executeQuery(query1);
        while (rs.next()) {
            Log.getLogWriter().info("Qyery executed successfully and query result is ::" + rs.getLong(1));
        }
        closeConnection(conn);
    }

    public static void HydraTask_writeCountQueryResultsToSnappyBB() {
        snappyTest.writeCountQueryResultsToBB();
    }

    public static void HydraTask_writeUpdatedCountQueryResultsToSnappyBB() {
        snappyTest.writeUpdatedCountQueryResultsToBB();
    }

    public static void HydraTask_verifyUpdateOpOnSnappyCluster() {
        snappyTest.updateQuery();
    }

    public static void HydraTask_verifyDeleteOpOnSnappyCluster() {
        snappyTest.deleteQuery();
    }

    protected void deleteQuery() {
        runGemXDQuery = true;
        try {
            Connection conn = getLocatorConnection();
            String query1 = "select count(*) from trade.txhistory";
            long rowCountBeforeDelete = runSelectQuery(conn, query1);
            String query2 = "delete from trade.txhistory where type = 'buy'";
            int rowCount = conn.createStatement().executeUpdate(query2);
            commit(conn);
            Log.getLogWriter().info("Deleted " + rowCount + " rows in trade.txhistory table in snappy.");
            String query3 = "select count(*) from trade.txhistory";
            String query4 = "select count(*) from trade.txhistory where type = 'buy'";
            long rowCountAfterDelete = 0, rowCountForquery4;
            rowCountAfterDelete = runSelectQuery(conn, query3);
            Log.getLogWriter().info("RowCountBeforeDelete: " + rowCountBeforeDelete);
            Log.getLogWriter().info("RowCountAfterDelete: " + rowCountAfterDelete);
            long expectedRowCountAfterDelete = rowCountBeforeDelete - rowCount;
            Log.getLogWriter().info("ExpectedRowCountAfterDelete: " + expectedRowCountAfterDelete);
            if (!(rowCountAfterDelete == expectedRowCountAfterDelete)) {
                String misMatch = "Test Validation failed due to mismatch in countQuery results for table trade.txhistory. countQueryResults after performing delete ops should be : " + expectedRowCountAfterDelete + ", but it is : " + rowCountAfterDelete;
                throw new TestException(misMatch);
            }
            rowCountForquery4 = runSelectQuery(conn, query4);
            Log.getLogWriter().info("Row count for query: select count(*) from trade.txhistory where type = 'buy' is: " + rowCountForquery4);
            if (!(rowCountForquery4 == 0)) {
                String misMatch = "Test Validation failed due to wrong row count value for table trade.txhistory. Expected row count value is : 0, but found : " + rowCountForquery4;
                throw new TestException(misMatch);
            }
            closeConnection(conn);
        } catch (SQLException e) {
            throw new TestException("Not able to get connection " + TestHelper.getStackTrace(e));
        }
    }

    protected void updateQuery() {
        runGemXDQuery = true;
        try {
            Connection conn = getLocatorConnection();
            String query1 = "select count(*) from trade.customers";
            long rowCountBeforeUpdate = runSelectQuery(conn, query1);
            String query2 = "update trade.customers set addr = 'Pune'";
            Log.getLogWriter().info("update query is: " + query2);
            int rowCount = conn.createStatement().executeUpdate(query2);
            commit(conn);
            Log.getLogWriter().info("Updated " + rowCount + " rows in trade.customers table in snappy.");
            String query4 = "select count(*) from trade.customers";
            String query5 = "select count(*) from trade.customers where addr != 'Pune'";
            String query6 = "select count(*) from trade.customers where addr = 'Pune'";
            long rowCountAfterUpdate = 0, rowCountForquery5 = 0, rowCountForquery6 = 0;
            rowCountAfterUpdate = runSelectQuery(conn, query4);
            if (!(rowCountBeforeUpdate == rowCountAfterUpdate)) {
                String misMatch = "Test Validation failed due to mismatch in countQuery results for table trade.customers. countQueryResults after performing update ops should be : " + rowCountBeforeUpdate + " , but it is : " + rowCountAfterUpdate;
                throw new TestException(misMatch);
            }
            rowCountForquery6 = runSelectQuery(conn, query6);
            Log.getLogWriter().info("RowCountBeforeUpdate:" + rowCountBeforeUpdate);
            Log.getLogWriter().info("RowCountAfterUpdate:" + rowCountAfterUpdate);
            if (!(rowCountForquery6 == rowCount)) {
                String misMatch = "Test Validation failed due to mismatch in row count value for table trade.customers. Row count after performing update ops should be : " + rowCount + " , but it is : " + rowCountForquery6;
                throw new TestException(misMatch);
            }
            rowCountForquery5 = runSelectQuery(conn, query5);
            Log.getLogWriter().info("Row count for query: select count(*) from trade.customers where addr != 'Pune' is: " + rowCountForquery5);
            if (!(rowCountForquery5 == 0)) {
                String misMatch = "Test Validation failed due to wrong row count value for table trade.customers. Expected row count value is : 0, but found : " + rowCountForquery5;
                throw new TestException(misMatch);
            }
            closeConnection(conn);
        } catch (SQLException e) {
            throw new TestException("Not able to get connection " + TestHelper.getStackTrace(e));
        }
    }

    protected static Long runSelectQuery(Connection conn, String query) {
        long rowCount = 0;
        try {
            ResultSet rs = conn.createStatement().executeQuery(query);
            while (rs.next()) {
                rowCount = rs.getLong(1);
                Log.getLogWriter().info(query + " query executed successfully and query result is : " + rs.getLong(1));
            }
        } catch (SQLException e) {
            throw new TestException("Not able to get connection " + TestHelper.getStackTrace(e));
        }
        return rowCount;
    }

    protected static void writeCountQueryResultsToBB() {
        runGemXDQuery = true;
        try {
            Connection conn = getLocatorConnection();
            String selectQuery = "select count(*) from ";
            ArrayList<String[]> tables = (ArrayList<String[]>) SQLBB.getBB().getSharedMap().get("tableNames");
            for (String[] table : tables) {
                String schemaTableName = table[0] + "." + table[1];
                String query = selectQuery + schemaTableName.toLowerCase();
                getCountQueryResult(conn, query, schemaTableName);
            }
            closeConnection(conn);
        } catch (SQLException e) {
            throw new TestException("Not able to get connection " + TestHelper.getStackTrace(e));
        }
    }

    protected static void writeUpdatedCountQueryResultsToBB() {
        runGemXDQuery = true;
        try {
            Connection conn = getLocatorConnection();
            String selectQuery = "select count(*) from ";
            ArrayList<String[]> tables = (ArrayList<String[]>) SQLBB.getBB().getSharedMap().get("tableNames");
            for (String[] table : tables) {
                String schemaTableName = table[0] + "." + table[1];
                String query = selectQuery + schemaTableName.toLowerCase();
                getCountQueryResult(conn, query, schemaTableName + "AfterOps");
            }
            closeConnection(conn);
        } catch (SQLException e) {
            throw new TestException("Not able to get connection " + TestHelper.getStackTrace(e));
        }
    }

    protected static void getCountQueryResult(Connection conn, String query, String tableName) {
        try {
            ResultSet rs = conn.createStatement().executeQuery(query);
            while (rs.next()) {
                Log.getLogWriter().info("Query:: " + query + "\nResult in Snappy:: " + rs.getLong(1));
                SnappyBB.getBB().getSharedMap().put(tableName, rs.getLong(1));
            }
        } catch (SQLException se) {
            SQLHelper.handleSQLException(se);
        }
    }

    public static void HydraTask_verifyCountQueryResults() {
        ArrayList<String[]> tables = (ArrayList<String[]>) SQLBB.getBB().getSharedMap().get("tableNames");
        for (String[] table1 : tables) {
            String schemaTableName = table1[0] + "." + table1[1];
            String tableName = schemaTableName;
            Long countQueryResultInSnappy = (Long) SnappyBB.getBB().getSharedMap().get(tableName);
            Log.getLogWriter().info("countQueryResult for table " + tableName + " in Snappy: " + countQueryResultInSnappy);
            Long countQueryResultInGemXD = (Long) SQLBB.getBB().getSharedMap().get(tableName);
            Log.getLogWriter().info("countQueryResult for table " + tableName + " in GemFireXD: " + countQueryResultInGemXD);
            if (!(countQueryResultInSnappy.equals(countQueryResultInGemXD))) {
                String misMatch = "Test Validation failed as countQuery result for table  " + tableName + " in GemFireXD: " + countQueryResultInGemXD + " did not match not match with countQuery result for table " + tableName + " in Snappy: " + countQueryResultInSnappy;
                throw new TestException(misMatch);
            }
        }
    }

    public static void HydraTask_verifyInsertOpOnSnappyCluster() {
        snappyTest.insertQuery();
    }

    public static String getCurrentTimeStamp() {
        SimpleDateFormat sdfDate = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
        Date now = new Date();
        String strDate = sdfDate.format(now);
        return strDate;
    }

    protected void insertQuery() {
        runGemXDQuery = true;
        try {
            Connection conn = getLocatorConnection();
            String query1 = "select count(*) from trade.txhistory";
            long rowCountBeforeInsert = runSelectQuery(conn, query1);
            for (int i = 0; i < 100; i++) {
                int cid = random.nextInt(20000);
                int tid = random.nextInt(40);
                int oid = random.nextInt(20000);
                int sid = random.nextInt(20000);
                int qty = random.nextInt(20000);
                int price = random.nextInt(Integer.MAX_VALUE) * 0;
                String ordertime = getCurrentTimeStamp();
                String type = "buy";
                String query2 = "insert into trade.txhistory (cid, oid, sid, qty, price, ordertime, type, tid )values (" + cid + ", " + oid + ", " + sid + ", " + qty + ", " + price + ", '" + ordertime + "', '" + type + "', " + tid + ")";
                int rowCount = conn.createStatement().executeUpdate(query2);
                commit(conn);
                Log.getLogWriter().info("Inserted " + rowCount + " rows into trade.txhistory table in snappy with values : " + "(" + cid + ", " + oid + ", " + sid + ", " + qty + ", " + price + ", '" + ordertime + "', '" + type + "', " + tid + ")");
            }
            String query3 = "select count(*) from trade.txhistory";
            long rowCountAfterInsert = 0;
            rowCountAfterInsert = runSelectQuery(conn, query3);
            Log.getLogWriter().info("RowCountBeforeInsert: " + rowCountBeforeInsert);
            Log.getLogWriter().info("RowCountAfterInsert: " + rowCountAfterInsert);
            long expectedRowCountAfterInsert = rowCountBeforeInsert + 100;
            Log.getLogWriter().info("ExpectedRowCountAfterInsert: " + expectedRowCountAfterInsert);
            if (!(rowCountAfterInsert == expectedRowCountAfterInsert)) {
                String misMatch = "Test Validation failed due to mismatch in countQuery results for table trade.txhistory. countQueryResults after performing insert ops should be : " + expectedRowCountAfterInsert + ", but it is : " + rowCountAfterInsert;
                throw new TestException(misMatch);
            }
            closeConnection(conn);
        } catch (SQLException e) {
            throw new TestException("Not able to get connection " + TestHelper.getStackTrace(e));
        }
    }

    protected void writeToFile(String logDir, File file) {
        try {
            FileWriter fw = new FileWriter(file.getAbsoluteFile(), true);
            BufferedWriter bw = new BufferedWriter(fw);
            bw.write(logDir);
            bw.newLine();
            bw.close();
        } catch (IOException e) {
            throw new TestException("Error occurred while writing to a file: " + file + e.getMessage());
        }
    }

    /**
     * Executes user SQL scripts.
     */
    public static synchronized void HydraTask_executeSQLScripts() {
        Vector scriptNames, dataLocationList = null, persistenceModeList = null, colocateWithOptionList = null,
                partitionByOptionList = null, numPartitionsList = null, redundancyOptionList = null,
                recoverDelayOptionList = null, maxPartitionSizeList = null, evictionByOptionList = null;
        File log = null, logFile = null;
        scriptNames = SnappyPrms.getSQLScriptNames();
        if (scriptNames == null) {
            String s = "No Script names provided for executing in the Hydra TASK";
            throw new TestException(s);
        }
        try {
            dataLocationList = SnappyPrms.getDataLocationList();
            persistenceModeList = SnappyPrms.getPersistenceModeList();
            colocateWithOptionList = SnappyPrms.getColocateWithOptionList();
            partitionByOptionList = SnappyPrms.getPartitionByOptionList();
            numPartitionsList = SnappyPrms.getNumPartitionsList();
            redundancyOptionList = SnappyPrms.getRedundancyOptionList();
            recoverDelayOptionList = SnappyPrms.getRecoverDelayOptionList();
            maxPartitionSizeList = SnappyPrms.getMaxPartitionSizeList();
            evictionByOptionList = SnappyPrms.getEvictionByOptionList();
            if (dataLocationList.size() != scriptNames.size()) {
                Log.getLogWriter().info("Adding \" \" parameter in the dataLocationList for the scripts for which no dataLocation is specified.");
                while (dataLocationList.size() != scriptNames.size())
                    dataLocationList.add(" ");
            }
            if (persistenceModeList.size() != scriptNames.size()) {
                Log.getLogWriter().info("Adding \"async\" parameter in the persistenceModeList for the scripts for which no persistence mode is specified.");
                while (persistenceModeList.size() != scriptNames.size())
                    persistenceModeList.add("async");
            }
            if (colocateWithOptionList.size() != scriptNames.size()) {
                Log.getLogWriter().info("Adding \"none\" parameter in the colocateWithOptionList for the scripts for which no COLOCATE_WITH Option is specified.");
                while (colocateWithOptionList.size() != scriptNames.size())
                    colocateWithOptionList.add("none");
            }
            if (partitionByOptionList.size() != scriptNames.size()) {
                Log.getLogWriter().info("Adding \" \" parameter in the partitionByOptionList for the scripts for which no PARTITION_BY option is specified.");
                while (partitionByOptionList.size() != scriptNames.size())
                    partitionByOptionList.add(" ");
            }
            if (numPartitionsList.size() != scriptNames.size()) {
                Log.getLogWriter().info("Adding \"113\" parameter in the partitionByOptionsList for the scripts for which no BUCKETS option is specified.");
                while (numPartitionsList.size() != scriptNames.size())
                    numPartitionsList.add("113");
            }
            if (redundancyOptionList.size() != scriptNames.size()) {
                Log.getLogWriter().info("Adding \" \" parameter in the redundancyOptionList for the scripts for which no REDUNDANCY option is specified.");
                while (redundancyOptionList.size() != scriptNames.size())
                    redundancyOptionList.add(" ");
            }
            if (recoverDelayOptionList.size() != scriptNames.size()) {
                Log.getLogWriter().info("Adding \" \" parameter in the recoverDelayOptionList for the scripts for which no RECOVER_DELAY option is specified.");
                while (recoverDelayOptionList.size() != scriptNames.size())
                    recoverDelayOptionList.add(" ");
            }
            if (maxPartitionSizeList.size() != scriptNames.size()) {
                Log.getLogWriter().info("Adding \" \" parameter in the maxPartitionSizeList for the scripts for which no MAX_PART_SIZE option is specified.");
                while (maxPartitionSizeList.size() != scriptNames.size())
                    maxPartitionSizeList.add(" ");
            }
            if (evictionByOptionList.size() != scriptNames.size()) {
                Log.getLogWriter().info("Adding \"LRUHEAPPERCENT\" parameter in the evictionByOptionList for the scripts for which no EVICTION_BY option is specified.");
                while (evictionByOptionList.size() != scriptNames.size())
                    evictionByOptionList.add("LRUHEAPPERCENT");
            }
            for (int i = 0; i < scriptNames.size(); i++) {
                String userScript = (String) scriptNames.elementAt(i);
                String location = (String) dataLocationList.elementAt(i);
                String persistenceMode = (String) persistenceModeList.elementAt(i);
                String colocateWith = (String) colocateWithOptionList.elementAt(i);
                String partitionBy = (String) partitionByOptionList.elementAt(i);
                String numPartitions = (String) numPartitionsList.elementAt(i);
                String redundancy = (String) redundancyOptionList.elementAt(i);
                String recoverDelay = (String) recoverDelayOptionList.elementAt(i);
                String maxPartitionSize = (String) maxPartitionSizeList.elementAt(i);
                String evictionByOption = (String) evictionByOptionList.elementAt(i);
                String dataLocation = snappyTest.getDataLocation(location);
                String filePath = snappyTest.getScriptLocation(userScript);
                log = new File(".");
                String dest = log.getCanonicalPath() + File.separator + "sqlScriptsResult.log";
                logFile = new File(dest);
                String primaryLocatorHost = (String) SnappyBB.getBB().getSharedMap().get("primaryLocatorHost");
                String primaryLocatorPort = (String) SnappyBB.getBB().getSharedMap().get("primaryLocatorPort");
                ProcessBuilder pb = new ProcessBuilder(SnappyShellPath, "run", "-file=" + filePath, "-param:dataLocation=" + dataLocation,
                        "-param:persistenceMode=" + persistenceMode, "-param:colocateWith=" + colocateWith,
                        "-param:partitionBy=" + partitionBy, "-param:numPartitions=" + numPartitions,
                        "-param:redundancy=" + redundancy, "-param:recoverDelay=" + recoverDelay,
                        "-param:maxPartitionSize=" + maxPartitionSize, "-param:evictionByOption=" + evictionByOption,
                        "-client-port=" + primaryLocatorPort, "-client-bind-address=" + primaryLocatorHost);
                snappyTest.executeProcess(pb, logFile);
            }
        } catch (IOException e) {
            throw new TestException("IOException occurred while retriving destination logFile path " + log + "\nError Message:" + e.getMessage());
        }
    }

    public void executeProcess(ProcessBuilder pb, File logFile) {
        Process p = null;
        try {
            pb.redirectErrorStream(true);
            pb.redirectError(ProcessBuilder.Redirect.PIPE);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            p = pb.start();
            assert pb.redirectInput() == ProcessBuilder.Redirect.PIPE;
            assert pb.redirectOutput().file() == logFile;
            assert p.getInputStream().read() == -1;
            int rc = p.waitFor();
            if (rc == 0) {
                Log.getLogWriter().info("Executed successfully");
            } else {
                Log.getLogWriter().info("Failed with exit code: " + rc);
            }
        } catch (IOException e) {
            throw new TestException("Exception occurred while starting the process:" + pb + "\nError Message:" + e.getMessage());
        } catch (InterruptedException e) {
            throw new TestException("Exception occurred while waiting for the process execution:" + p + "\nError Message:" + e.getMessage());
        }
    }

    protected void recordSnappyProcessIDinNukeRun(String pName) {
        Process pr = null;
        try {
            String command;
            if (pName.equals("Master"))
                command = "ps ax | grep -w " + pName + " | grep -v grep | awk '{print $1}'";
            else command = "jps | grep " + pName + " | awk '{print $1}'";
            hd = TestConfig.getInstance().getMasterDescription()
                    .getVmDescription().getHostDescription();
            ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
            File log = new File(".");
            pb.redirectErrorStream(true);
            String dest = log.getCanonicalPath() + File.separator + "PIDs.log";
            File logFile = new File(dest);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
            pr = pb.start();
            pr.waitFor();
            FileInputStream fis = new FileInputStream(logFile);
            BufferedReader br = new BufferedReader(new InputStreamReader(fis));
            String str = null;
            while ((str = br.readLine()) != null) {
                int pid = Integer.parseInt(str);
                try {
                    if (pids.contains(pid)) {
                        Log.getLogWriter().info("Pid is already recorded with Master" + pid);
                    } else {
                        pids.add(pid);
                        RemoteTestModule.Master.recordPID(hd, pid);
                        SnappyBB.getBB().getSharedMap().put("pid" + "_" + pName + "_" + str, str);
                    }
                } catch (RemoteException e) {
                    String s = "Unable to access master to record PID: " + pid;
                    throw new HydraRuntimeException(s, e);
                }
                Log.getLogWriter().info("pid value successfully recorded with Master");
            }
            br.close();
        } catch (IOException e) {
            String s = "Problem while starting the process : " + pr;
            throw new TestException(s, e);
        } catch (InterruptedException e) {
            String s = "Exception occurred while waiting for the process execution : " + pr;
            throw new TestException(s, e);
        }
    }


    /**
     * Task(ENDTASK) for cleaning up snappy processes, because they are not stopped by Hydra in case of Test failure.
     */
    public static void HydraTask_cleanUpSnappyProcessesOnFailure() {
        Process pr = null;
        ProcessBuilder pb = null;
        File logFile = null, log = null, nukeRunOutput = null;
        try {
            List<String> pidList = new ArrayList();
            HostDescription hd = TestConfig.getInstance().getMasterDescription()
                    .getVmDescription().getHostDescription();
            pidList = snappyTest.getPidList();
            log = new File(".");
            String nukerun = log.getCanonicalPath() + File.separator + "snappyNukeRun.sh";
            logFile = new File(nukerun);
            String nukeRunOutputString = log.getCanonicalPath() + File.separator + "nukeRunOutput.log";
            nukeRunOutput = new File(nukeRunOutputString);
            FileWriter fw = new FileWriter(logFile.getAbsoluteFile(), true);
            BufferedWriter bw = new BufferedWriter(fw);
            for (String pidString : pidList) {
                int pid = Integer.parseInt(pidString);
                bw.write("/bin/kill -KILL " + pid);
                bw.newLine();
                try {
                    RemoteTestModule.Master.removePID(hd, pid);
                } catch (RemoteException e) {
                    String s = "Failed to remove PID from nukerun script: " + pid;
                    throw new HydraRuntimeException(s, e);
                }
            }
            bw.close();
            fw.close();
            logFile.setExecutable(true);
            pb = new ProcessBuilder(nukerun);
            pb.redirectErrorStream(true);
            pb.redirectOutput(ProcessBuilder.Redirect.appendTo(nukeRunOutput));
            pr = pb.start();
            pr.waitFor();
        } catch (IOException e) {
            throw new TestException("IOException occurred while retriving logFile path " + log + "\nError Message:" + e.getMessage());
        } catch (InterruptedException e) {
            String s = "Exception occurred while waiting for the process execution : " + pr;
            throw new TestException(s, e);
        }
    }

    /**
     * Executes snappy Streaming Jobs.
     */
    public static void HydraTask_executeSnappyStreamingJob() {
        snappyTest.executeSnappyStreamingJob(SnappyPrms.getSnappyStreamingJobClassNames(),
                "snappyStreamingJobTaskResult_" + System.currentTimeMillis() + ".log");
    }

    /**
     * Executes Snappy Jobs.
     */
    public static void HydraTask_executeSnappyJob() {
        int currentThread = snappyTest.getMyTid();
        String logFile = "snappyJobResult_thread_" + currentThread + "_" + System.currentTimeMillis() + ".log";
        SnappyBB.getBB().getSharedMap().put("logFilesForJobs_" + currentThread + "_" + System.currentTimeMillis(), logFile);
        snappyTest.executeSnappyJob(SnappyPrms.getSnappyJobClassNames(), logFile, SnappyPrms.getUserAppJar(), jarPath, SnappyPrms.getUserAppName());
    }

    /**
     * Executes snappy Streaming Jobs. Task is specifically written for benchmarking.
     */
    public static void HydraTask_executeSnappyStreamingJob_benchmarking() {
        snappyTest.executeSnappyStreamingJob(SnappyPrms.getSnappyStreamingJobClassNames(),
                "snappyStreamingJobTaskResult_" + System.currentTimeMillis() + ".log");
    }

    /**
     * Executes Spark Jobs in Task.
     */
    public static void HydraTask_executeSparkJob() {
        int currentThread = snappyTest.getMyTid();
        String logFile = "sparkJobTaskResult_thread_" + currentThread + "_" + System.currentTimeMillis() + ".log";
        snappyTest.executeSparkJob(SnappyPrms.getSparkJobClassNames(), logFile);
    }

    /**
     * Executes snappy Streaming Jobs in Task.
     */
    public static void HydraTask_executeSnappyStreamingJobWithFileStream() {
        Runnable fileStreaming = new Runnable() {
            public void run() {
                snappyTest.executeSnappyStreamingJob(SnappyPrms.getSnappyStreamingJobClassNames(), "snappyStreamingJobResult_" + System.currentTimeMillis() + ".log");
            }
        };

        Runnable simulateFileStream = new Runnable() {
            public void run() {
                snappyTest.simulateStream();
            }
        };

        ExecutorService es = Executors.newFixedThreadPool(2);
        es.submit(fileStreaming);
        es.submit(simulateFileStream);
        try {
            Log.getLogWriter().info("Sleeping for " + waitTimeBeforeStreamingJobStatus + "millis before executor service shut down");
            Thread.sleep(waitTimeBeforeStreamingJobStatus);
            es.shutdown();
            es.awaitTermination(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new TestException("Exception occurred while waiting for the snappy streaming job process execution." + "\nError Message:" + e.getMessage());
        }
    }

    protected void executeSnappyStreamingJob(Vector jobClassNames, String logFileName) {
        String snappyJobScript = getScriptLocation("snappy-job.sh");
        String curlCommand1 = null, curlCommand2 = null, curlCommand3 = null, contextName = null, APP_PROPS = null;
        ProcessBuilder pb = null;
        File log = null;
        File logFile = null;
        userAppJar = SnappyPrms.getUserAppJar();
        snappyTest.verifyDataForJobExecution(jobClassNames, userAppJar);
        leadHost = getLeadHost();
        String leadPort = (String) SnappyBB.getBB().getSharedMap().get("primaryLeadPort");
        try {
            for (int i = 0; i < jobClassNames.size(); i++) {
                String userJob = (String) jobClassNames.elementAt(i);
                if (SnappyPrms.getCommaSepAPPProps() == null) {
                    APP_PROPS = "shufflePartitions=" + SnappyPrms.getShufflePartitions();
                } else {
                    APP_PROPS = SnappyPrms.getCommaSepAPPProps() + ",shufflePartitions=" + SnappyPrms.getShufflePartitions();
                }
                contextName = "snappyStreamingContext" + System.currentTimeMillis();
                String contextFactory = "org.apache.spark.sql.streaming.SnappyStreamingContextFactory";
                curlCommand1 = "curl --data-binary @" + snappyTest.getUserAppJarLocation(userAppJar, jarPath) + " " + leadHost + ":" + leadPort + "/jars/myapp";
                curlCommand2 = "curl -d  \"\"" + " '" + leadHost + ":" + leadPort + "/" + "contexts/" + contextName + "?context-factory=" + contextFactory + "'";
                curlCommand3 = "curl -d " + APP_PROPS + " '" + leadHost + ":" + leadPort + "/jobs?appName=myapp&classPath=" + userJob + "&context=" + contextName + "'";
                pb = new ProcessBuilder("/bin/bash", "-c", curlCommand1);
                log = new File(".");
                String dest = log.getCanonicalPath() + File.separator + logFileName;
                logFile = new File(dest);
                snappyTest.executeProcess(pb, logFile);
                pb = new ProcessBuilder("/bin/bash", "-c", curlCommand2);
                snappyTest.executeProcess(pb, logFile);
                pb = new ProcessBuilder("/bin/bash", "-c", curlCommand3);
                snappyTest.executeProcess(pb, logFile);
            }
            snappyTest.getSnappyJobsStatus(snappyJobScript, logFile, leadPort);
        } catch (IOException e) {
            throw new TestException("IOException occurred while retriving destination logFile path " + log + "\nError Message:" + e.getMessage());
        }
    }

    protected void executeSnappyStreamingJobUsingJobScript(Vector jobClassNames, String logFileName) {
        String snappyJobScript = getScriptLocation("snappy-job.sh");
        ProcessBuilder pb = null;
        File log = null;
        File logFile = null;
        userAppJar = SnappyPrms.getUserAppJar();
        snappyTest.verifyDataForJobExecution(jobClassNames, userAppJar);
        leadHost = getLeadHost();
        String leadPort = (String) SnappyBB.getBB().getSharedMap().get("primaryLeadPort");
        try {
            for (int i = 0; i < jobClassNames.size(); i++) {
                String userJob = (String) jobClassNames.elementAt(i);
                pb = new ProcessBuilder(snappyJobScript, "submit", "--lead", leadHost + ":" + leadPort, "--app-name", "myapp", "--class", userJob, "--app-jar", snappyTest.getUserAppJarLocation(userAppJar, jarPath), "--stream");
                java.util.Map<String, String> env = pb.environment();
                if (SnappyPrms.getCommaSepAPPProps() == null) {
                    env.put("APP_PROPS", "shufflePartitions=" + SnappyPrms.getShufflePartitions());
                } else {
                    env.put("APP_PROPS", SnappyPrms.getCommaSepAPPProps() + ",shufflePartitions=" + SnappyPrms.getShufflePartitions());
                }
                log = new File(".");
                String dest = log.getCanonicalPath() + File.separator + logFileName;
                logFile = new File(dest);
                snappyTest.executeProcess(pb, logFile);
            }
            snappyTest.getSnappyJobsStatus(snappyJobScript, logFile, leadPort);
        } catch (IOException e) {
            throw new TestException("IOException occurred while retriving destination logFile path " + log + "\nError Message:" + e.getMessage());
        }
    }

    public String getLeadHost() {
        if (isLongRunningTest) {
            leadHost = getLeadHostFromFile();
        } else {
            leadHost = (String) SnappyBB.getBB().getSharedMap().get("primaryLeadHost");
            if (leadHost == null) {
                retrievePrimaryLeadHost();
                leadHost = (String) SnappyBB.getBB().getSharedMap().get("primaryLeadHost");
                Log.getLogWriter().info("primaryLead Host is: " + leadHost);
            }
        }
        return leadHost;
    }

    protected void verifyDataForJobExecution(Vector jobClassNames, String userAppJar) {
        if (userAppJar == null) {
            String s = "Missing userAppJar parameter.";
            throw new TestException(s);
        }
        if (jobClassNames == null) {
            String s = "Missing JobClassNames parameter for required TASK/CLOSETASK.";
            throw new TestException(s);
        }
    }

    public void executeSnappyJob(Vector jobClassNames, String logFileName, String userAppJar, String jarPath, String appName) {
        String snappyJobScript = getScriptLocation("snappy-job.sh");
        File log = null, logFile = null;
//        userAppJar = SnappyPrms.getUserAppJar();
        if (appName == null) appName = SnappyPrms.getUserAppName();
        snappyTest.verifyDataForJobExecution(jobClassNames, userAppJar);
        leadHost = getLeadHost();
        String leadPort = (String) SnappyBB.getBB().getSharedMap().get("primaryLeadPort");
        Log.getLogWriter().info("primaryLead Port is : " + leadPort);
        try {
            for (int i = 0; i < jobClassNames.size(); i++) {
                String userJob = (String) jobClassNames.elementAt(i);
                String APP_PROPS = null;
                if (SnappyPrms.getCommaSepAPPProps() == null) {
                    APP_PROPS = "logFileName=" + logFileName + ",shufflePartitions=" + SnappyPrms.getShufflePartitions();
                } else {
                    APP_PROPS = SnappyPrms.getCommaSepAPPProps() + ",logFileName=" + logFileName + ",shufflePartitions=" + SnappyPrms.getShufflePartitions();
                }
                String curlCommand1 = "curl --data-binary @" + snappyTest.getUserAppJarLocation(userAppJar, jarPath) + " " + leadHost + ":" + leadPort + "/jars/" + appName;
                String curlCommand2 = "curl -d " + APP_PROPS + " '" + leadHost + ":" + leadPort + "/jobs?appName=" + appName + "&classPath=" + userJob + "'";
                ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", curlCommand1);
                log = new File(".");
                String dest = log.getCanonicalPath() + File.separator + logFileName;
                logFile = new File(dest);
                snappyTest.executeProcess(pb, logFile);
                pb = new ProcessBuilder("/bin/bash", "-c", curlCommand2);
                snappyTest.executeProcess(pb, logFile);
            }
            boolean retry = snappyTest.getSnappyJobsStatus(snappyJobScript, logFile, leadPort);
            if (retry && jobSubmissionCount <= SnappyPrms.getRetryCountForJob()) {
                jobSubmissionCount++;
                Thread.sleep(6000);
                Log.getLogWriter().info("Job failed due to primary lead node failover. Resubmitting the job to new primary lead node.....");
                retrievePrimaryLeadHost();
                HydraTask_executeSnappyJob();
            }
        } catch (IOException e) {
            throw new TestException("IOException occurred while retriving destination logFile path " + log + "\nError Message:" + e.getMessage());
        } catch (InterruptedException e) {
            throw new TestException("Exception occurred while waiting for the snappy streaming job process re-execution." + "\nError Message:" + e.getMessage());
        }
    }

    protected void executeSparkJob(Vector jobClassNames, String logFileName) {
        String snappyJobScript = getScriptLocation("spark-submit");
        ProcessBuilder pb = null;
        File log = null, logFile = null;
        userAppJar = SnappyPrms.getUserAppJar();
        snappyTest.verifyDataForJobExecution(jobClassNames, userAppJar);
        try {
            for (int i = 0; i < jobClassNames.size(); i++) {
                String userJob = (String) jobClassNames.elementAt(i);
                String masterHost = getSparkMasterHost();
                String locatorsList = getLocatorsList("locators");
                String command = snappyJobScript + " --class " + userJob +
                        " --master spark://" + masterHost + ":" + MASTER_PORT + " --conf snappydata.store.locators=" + locatorsList + " " +
                        " --conf spark.extraListeners=io.snappydata.hydra.SnappyCustomSparkListener" +
                        " " + snappyTest.getUserAppJarLocation(userAppJar, jarPath) + " " + SnappyPrms.getUserAppArgs();
                Log.getLogWriter().info("spark-submit command is : " + command);
                log = new File(".");
                String dest = log.getCanonicalPath() + File.separator + logFileName;
                logFile = new File(dest);
                pb = new ProcessBuilder("/bin/bash", "-c", command);
                snappyTest.executeProcess(pb, logFile);
                String searchString = "Spark ApplicationEnd: ";
                String expression = "cat " + logFile + " | grep -e Exception -e '" + searchString + "' | grep -v java.net.BindException" + " | wc -l)\"";
                String searchCommand = "while [ \"$(" + expression + " -le  0 ] ; do sleep 1 ; done";
                pb = new ProcessBuilder("/bin/bash", "-c", searchCommand);
                Log.getLogWriter().info("spark job " + userJob + " starts at: " + System.currentTimeMillis());
                executeProcess(pb, logFile);
                Log.getLogWriter().info("spark job " + userJob + " finishes at:  " + System.currentTimeMillis());
            }
        } catch (IOException e) {
            throw new TestException("IOException occurred while retriving destination logFile path " + log + "\nError Message:" + e.getMessage());
        }
    }

    protected static String getAbsoluteJarLocation(String jarPath, final String jarName) {
        String absoluteJarPath = null;
        File baseDir = new File(jarPath);
        try {
            IOFileFilter filter = new WildcardFileFilter(jarName);
            List<File> files = (List<File>) FileUtils.listFiles(baseDir, filter, TrueFileFilter.INSTANCE);
            Log.getLogWriter().info("Jar file found: " + Arrays.asList(files));
            for (File file1 : files) {
                if (!file1.getAbsolutePath().contains("/work/"))
                    absoluteJarPath = file1.getAbsolutePath();
            }
        } catch (Exception e) {
            Log.getLogWriter().info("Unable to find " + jarName + " jar at " + jarPath + " location.");
        }
        return absoluteJarPath;
    }

    /**
     * Returns the output file containing collective output for all threads executing Snappy job in CLOSETASK.
     */
    public static void HydraTask_getSnappyJobOutputCollectivelyForCloseTask() {
        snappyTest.getSnappyJobOutputCollectively("logFilesForJobs_", "snappyJobCollectiveOutputForCloseTask.log");
    }

    /**
     * Returns the output file containing collective output for all threads executing Snappy job in TASK.
     */
    public static void HydraTask_getSnappyJobOutputCollectivelyForTask() {
        snappyTest.getSnappyJobOutputCollectively("logFilesForJobs_", "snappyJobCollectiveOutputForTask.log");
    }

    protected void getSnappyJobOutputCollectively(String logFilekey, String fileName) {
        Set<String> snappyJobLogFiles = new LinkedHashSet<String>();
        File fin = null;
        try {
            Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
            for (String key : keys) {
                if (key.startsWith(logFilekey)) {


                    String logFilename = (String) SnappyBB.getBB().getSharedMap().getMap().get(key);
                    Log.getLogWriter().info("Key Found...." + logFilename);
                    snappyJobLogFiles.add(logFilename);
                }
            }
            File dir = new File(".");
            String dest = dir.getCanonicalPath() + File.separator + fileName;
            File file = new File(dest);
            if (!file.exists()) return;
            int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.doneExecution);
            if (num == 1) {
                FileWriter fstream = new FileWriter(dest, true);
                BufferedWriter bw = new BufferedWriter(fstream);
                Iterator<String> itr = snappyJobLogFiles.iterator();
                while (itr.hasNext()) {
                    String userScript = itr.next();
                    String threadID = userScript.substring(userScript.lastIndexOf("_"), userScript.indexOf("."));
                    String threadInfo = "Thread" + threadID + " output:";
                    bw.write(threadInfo);
                    bw.newLine();
                    String fileInput = snappyTest.getLogDir() + File.separator + userScript;
                    fin = new File(fileInput);
                    FileInputStream fis = new FileInputStream(fin);
                    BufferedReader in = new BufferedReader(new InputStreamReader(fis));
                    String line = null;
                    while ((line = in.readLine()) != null) {
                        bw.write(line);
                        bw.newLine();
                    }
                    in.close();
                }
                bw.close();
            }
        } catch (FileNotFoundException e) {
            String s = "Unable to find file: " + fin;
            throw new TestException(s);
        } catch (IOException e) {
            String s = "Problem while writing to the file : " + fin;
            throw new TestException(s, e);
        }
    }

    protected void simulateStream() {
        File logFile = null;
        File log = new File(".");
        try {
            String streamScriptName = snappyTest.getScriptLocation(simulateStreamScriptName);
            ProcessBuilder pb = new ProcessBuilder(streamScriptName, simulateStreamScriptDestinationFolder, productDir);
            String dest = log.getCanonicalPath() + File.separator + "simulateFileStreamResult.log";
            logFile = new File(dest);
            snappyTest.executeProcess(pb, logFile);
        } catch (IOException e) {
            String s = "problem occurred while retriving destination logFile path " + log;
            throw new TestException(s, e);
        }
    }

    public boolean getSnappyJobsStatus(String snappyJobScript, File logFile, String leadPort) {
        boolean found = false;
        try {
            String line = null;
            Set<String> jobIds = new LinkedHashSet<String>();
            FileReader freader = new FileReader(logFile);
            BufferedReader inputFile = new BufferedReader(freader);
            while ((line = inputFile.readLine()) != null) {
                if (line.contains("jobId")) {
                    String jobID = line.split(":")[1].trim();
                    jobID = jobID.substring(1, jobID.length() - 2);
                    jobIds.add(jobID);
                }
            }
            inputFile.close();
            for (String str : jobIds) {
                File log = new File(".");
                String dest = log.getCanonicalPath() + File.separator + "jobStatus_" + RemoteTestModule.getCurrentThread().getThreadId() + "_" + System.currentTimeMillis() + ".log";
                File commandOutput = new File(dest);
                String expression = snappyJobScript + " status --lead " + leadHost + ":" + leadPort + " --job-id " + str + " > " + commandOutput + " 2>&1 ; grep -e '\"status\": \"FINISHED\"' -e 'curl:' -e '\"status\": \"ERROR\"' " + commandOutput + " | wc -l)\"";
                String command = "while [ \"$(" + expression + " -le  0 ] ; do rm " + commandOutput + " ;  touch " + commandOutput + "  ; done";
                ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
                Log.getLogWriter().info("job " + str + " starts at: " + System.currentTimeMillis());
                executeProcess(pb, commandOutput);
                Log.getLogWriter().info("job " + str + " finishes at:  " + System.currentTimeMillis());
                FileInputStream fis = new FileInputStream(commandOutput);
                BufferedReader br = new BufferedReader(new InputStreamReader(fis));
                line = null;
                String searchString = "Connection reset by peer";
                while ((line = br.readLine()) != null && !found) {
                    if (line.toLowerCase().contains(searchString.toLowerCase())) {
                        found = true;
                        Log.getLogWriter().info("Connection reset by peer...");
                    }
                }
                br.close();
            }
        } catch (FileNotFoundException e) {
            String s = "Unable to find file: " + logFile;
            throw new TestException(s);
        } catch (IOException e) {
            String s = "Problem while reading the file : " + logFile;
            throw new TestException(s, e);
        }
        return found;
    }

    public synchronized void retrievePrimaryLeadHost() {
        Object[] tmpArr = null;
        String leadPort = null;
        tmpArr = getPrimaryLeadVM(cycleLeadVMTarget);
        List<ClientVmInfo> vmList;
        vmList = (List<ClientVmInfo>) (tmpArr[0]);
        Set<String> myDirList = new LinkedHashSet<String>();
        myDirList = getFileContents("logDir_", myDirList);
        for (int i = 0; i < vmList.size(); i++) {
            ClientVmInfo targetVm = vmList.get(i);
            String clientName = targetVm.getClientName();
            for (String vmDir : myDirList) {
                if (vmDir.contains(clientName)) {
                    String[] splitedNodeConfig = vmDir.split("_");
                    leadHost = splitedNodeConfig[splitedNodeConfig.length - 2];
                    Log.getLogWriter().info("New Primary leadHost is: " + leadHost);
                    SnappyBB.getBB().getSharedMap().put("primaryLeadHost", leadHost);
                    leadPort = getPrimaryLeadPort(clientName);
                    SnappyBB.getBB().getSharedMap().put("primaryLeadPort", leadPort);
                    if (isLongRunningTest) writeLeadHostInfo();
                }
            }
        }
    }

    /*
    * Returns the log file name.  Autogenerates the directory name at runtime
    * using the same path as the master.  The directory is created if needed.
    *
    * @throws HydraRuntimeException if the directory cannot be created.
    */
    private synchronized String getLogDir() {
        if (this.logFile == null) {
            Vector<String> names = TestConfig.tab().vecAt(ClientPrms.gemfireNames);
            String dirname = generateLogDirName();
//            this.localHost = HostHelper.getLocalHost();
            File dir = new File(dirname);
            String fullname = dir.getAbsolutePath();
            try {
                FileUtil.mkdir(dir);
                try {
                    for (String name : names) {
                        String[] splitedName = name.split("gemfire");
                        String newName = splitedName[0] + splitedName[1];
//                        if (newName.equals(RemoteTestModule.getMyClientName())) {
                        RemoteTestModule.Master.recordDir(hd,
                                name, fullname);
//                        }
                    }
                } catch (RemoteException e) {
                    String s = "Unable to access master to record directory: " + dir;
                    throw new HydraRuntimeException(s, e);
                }
            } catch (VirtualMachineError e) {
                SystemFailure.initiateFailure(e);
                throw e;
            } catch (Error e) {
                String s = "Unable to create directory: " + dir;
                throw new HydraRuntimeException(s);
            }
            this.logFile = dirname;
            log().info("logFile name is " + this.logFile);
        }
        return this.logFile;
    }

    private String generateLogDirName() {
        String dirname = hd.getUserDir() + File.separator
                + "vm_" + RemoteTestModule.getMyVmid()
                + "_" + RemoteTestModule.getMyClientName()
                + "_" + HostHelper.getLocalHost()
                + "_" + RemoteTestModule.getMyPid();
        return dirname;
    }

    protected synchronized void generateConfig(String fileName) {
        File file = null;
        try {
            String path = productConfDirPath + sep + fileName;
            log().info("File Path is ::" + path);
            file = new File(path);

            // if file doesnt exists, then create it
            if (!file.exists()) {
                file.createNewFile();
            } else if (file.exists()) {
                if (isStopMode) return;
                file.setWritable(true);
                //file.delete();
                Files.delete(Paths.get(path));
                Log.getLogWriter().info(fileName + " file deleted");
                file.createNewFile();
            }
        } catch (IOException e) {
            String s = "Problem while creating the file : " + file;
            throw new TestException(s, e);
        }
    }

    /**
     * Deletes the snappy config generated spcific to test run after successful test execution.
     */
    public static void HydraTask_deleteSnappyConfig() throws IOException {
        String locatorConf = productConfDirPath + sep + "locators";
        String serverConf = productConfDirPath + sep + "servers";
        String leadConf = productConfDirPath + sep + "leads";
        Files.delete(Paths.get(locatorConf));
        Log.getLogWriter().info("locators file deleted");
        Files.delete(Paths.get(serverConf));
        Log.getLogWriter().info("servers file deleted");
        Files.delete(Paths.get(leadConf));
        Log.getLogWriter().info("leads file deleted");
        if (useSplitMode) {
            String slaveConf = productConfDirPath + sep + "slaves";
            String sparkEnvConf = productConfDirPath + sep + "spark-env.sh";
            Files.delete(Paths.get(slaveConf));
            Log.getLogWriter().info("slaves file deleted");
            Files.delete(Paths.get(sparkEnvConf));
            Log.getLogWriter().info("spark-env.sh file deleted");
        }
        // Removing twitter data directories if exists.
        String twitterdata = dtests + "twitterdata";
        String copiedtwitterdata = dtests + "copiedtwitterdata";
        File file = new File(twitterdata);
        if (file.exists()) {
            file.delete();
            Log.getLogWriter().info("Done removing twitter data directory.");
        }
        file = new File(copiedtwitterdata);
        if (file.exists()) {
            file.delete();
            Log.getLogWriter().info("Done removing copiedtwitterdata data directory.");
        }
    }

    public int getMyTid() {
        int myTid = RemoteTestModule.getCurrentThread().getThreadId();
        return myTid;
    }


    /**
     * Start snappy cluster using snappy-start-all.sh script.
     */
    public static synchronized void HydraTask_startSnappyCluster() {
        File log = null;
        ProcessBuilder pb = null;
        try {
            int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.snappyClusterStarted);
            if (num == 1) {
                pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-start-all.sh"), "start");
                log = new File(".");
                String dest = log.getCanonicalPath() + File.separator + "snappySystem.log";
                File logFile = new File(dest);
                snappyTest.executeProcess(pb, logFile);
                snappyTest.recordSnappyProcessIDinNukeRun("LocatorLauncher");
                snappyTest.recordSnappyProcessIDinNukeRun("ServerLauncher");
                snappyTest.recordSnappyProcessIDinNukeRun("LeaderLauncher");
            }
        } catch (IOException e) {
            String s = "problem occurred while retriving destination logFile path " + log;
            throw new TestException(s, e);
        }
    }

    /**
     * Create and start snappy locator using snappy-locators.sh script.
     */
    public static synchronized void HydraTask_createAndStartSnappyLocator() {
        File log = null;
        ProcessBuilder pb = null;
        try {
            int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.locatorsStarted);
            if (num == 1) {
                if (useRowStore) {
                    Log.getLogWriter().info("Starting locator/s using rowstore option...");
                    pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-locators.sh"), "start", "rowstore");
                } else {
                    pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-locators.sh"), "start");
                }
                log = new File(".");
                String dest = log.getCanonicalPath() + File.separator + "snappyLocatorSystem.log";
                File logFile = new File(dest);
                snappyTest.executeProcess(pb, logFile);
                if (useRowStore)
                    snappyTest.recordSnappyProcessIDinNukeRun("GfxdDistributionLocator");
                else snappyTest.recordSnappyProcessIDinNukeRun("LocatorLauncher");
            }
        } catch (IOException e) {
            String s = "problem occurred while retriving destination logFile path " + log;
            throw new TestException(s, e);
        }
    }


    /**
     * Create and start snappy server.
     */
    public static synchronized void HydraTask_createAndStartSnappyServers() {
        int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.serversStarted);
        if (num == 1) {
            snappyTest.startSnappyServer();
        }
    }


    /**
     * Creates and start snappy lead.
     */
    public static synchronized void HydraTask_createAndStartSnappyLeader() {
        int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.leadsStarted);
        if (num == 1) {
            snappyTest.startSnappyLead();
        }
    }


    /**
     * Starts Spark Cluster with the specified number of workers.
     */
    public static synchronized void HydraTask_startSparkCluster() {
        File log = null;
        try {
            int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.sparkClusterStarted);
            if (num == 1) {
                // modifyJobServerConfig();
                ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("start-all.sh"));
                log = new File(".");
                String dest = log.getCanonicalPath() + File.separator + "sparkSystem.log";
                File logFile = new File(dest);
                snappyTest.executeProcess(pb, logFile);
                snappyTest.recordSnappyProcessIDinNukeRun("Worker");
                snappyTest.recordSnappyProcessIDinNukeRun("Master");
            }
        } catch (IOException e) {
            String s = "problem occurred while retriving destination logFile path " + log;
            throw new TestException(s, e);
        }
    }

    /**
     * Stops Spark Cluster.
     */
    public static synchronized void HydraTask_stopSparkCluster() {
        File log = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("stop-all.sh"));
            log = new File(".");
            String dest = log.getCanonicalPath() + File.separator + "sparkSystem.log";
            File logFile = new File(dest);
            snappyTest.executeProcess(pb, logFile);
            SnappyBB.getBB().getSharedCounters().zero(SnappyBB.sparkClusterStarted);
        } catch (IOException e) {
            String s = "problem occurred while retriving destination logFile path " + log;
            throw new TestException(s, e);
        }
    }

    /**
     * Stops snappy lead.
     */
    public static synchronized void HydraTask_stopSnappyLeader() {
        File log = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-leads.sh"), "stop");
            log = new File(".");
            String dest = log.getCanonicalPath() + File.separator + "snappyLeaderSystem.log";
            File logFile = new File(dest);
            snappyTest.executeProcess(pb, logFile);
            SnappyBB.getBB().getSharedCounters().zero(SnappyBB.leadsStarted);
        } catch (IOException e) {
            String s = "problem occurred while retriving logFile path " + log;
            throw new TestException(s, e);
        } catch (Exception e) {
            String s = "problem occurred while retriving logFile path " + log;
            throw new TestException(s, e);
        }
    }

    /**
     * Stops snappy server/servers.
     */
    public static synchronized void HydraTask_stopSnappyServers() {
        File log = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-servers.sh"), "stop");
            log = new File(".");
            String dest = log.getCanonicalPath() + File.separator + "snappyServerSystem.log";
            File logFile = new File(dest);
            snappyTest.executeProcess(pb, logFile);
            SnappyBB.getBB().getSharedCounters().zero(SnappyBB.serversStarted);
        } catch (IOException e) {
            String s = "problem occurred while retriving logFile path " + log;
            throw new TestException(s, e);
        } catch (Exception e) {
            String s = "problem occurred while retriving logFile path " + log;
            throw new TestException(s, e);
        }
    }

    /**
     * Stops a snappy locator.
     */
    public static synchronized void HydraTask_stopSnappyLocator() {
        File log = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-locators.sh"), "stop");
            log = new File(".");
            String dest = log.getCanonicalPath() + File.separator + "snappyLocatorSystem.log";
            File logFile = new File(dest);
            snappyTest.executeProcess(pb, logFile);
            SnappyBB.getBB().getSharedCounters().zero(SnappyBB.locatorsStarted);
        } catch (IOException e) {
            String s = "problem occurred while retriving logFile path " + log;
            throw new TestException(s, e);
        } catch (Exception e) {
            String s = "problem occurred while retriving logFile path " + log;
            throw new TestException(s, e);
        }
    }

    public static synchronized void HydraTask_stopSnappyCluster() {
        File log = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-stop-all.sh"));
            log = new File(".");
            String dest = log.getCanonicalPath() + File.separator + "snappySystem.log";
            File logFile = new File(dest);
            snappyTest.executeProcess(pb, logFile);
        } catch (IOException e) {
            String s = "problem occurred while retriving destination logFile path " + log;
            throw new TestException(s, e);
        }
    }

    /**
     * Concurrently stops a List of snappy store VMs, then restarts them.  Waits for the
     * restart to complete before returning.
     */
    public static void HydraTask_cycleStoreVms() {

        if (cycleVms) {
            int numToKill = TestConfig.tab().intAt(SnappyPrms.numVMsToStop, 1);
            int stopStartVms = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.stopStartVms);
            Long lastCycledTimeForStoreFromBB = (Long) SnappyBB.getBB().getSharedMap().get(LASTCYCLEDTIME);
            snappyTest.cycleVM(numToKill, stopStartVms, "storeVmCycled", lastCycledTimeForStoreFromBB, lastCycledTime, false);
        }
    }

    /**
     * Stops snappy primary lead member, then restarts it.  Waits for the
     * restart to complete before returning.
     */
    public static synchronized void HydraTask_cycleLeadVM() {
        if (cycleVms) {
            int numToKill = TestConfig.tab().intAt(SnappyPrms.numLeadsToStop, 1);
            int stopStartVms = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.stopStartLeadVms);
            Long lastCycledTimeForLeadFromBB = (Long) SnappyBB.getBB().getSharedMap().get(LASTCYCLEDTIMEFORLEAD);
            snappyTest.cycleVM(numToKill, stopStartVms, "leadVmCycled", lastCycledTimeForLeadFromBB, lastCycledTimeForLead, true);
        }
    }

    protected void
    cycleVM(int numToKill, int stopStartVMs, String cycledVM, Long lastCycledTimeFromBB, long lastCycledTime, boolean isLead) {
        if (!cycleVms) {
            Log.getLogWriter().warning("cycleVms sets to false, no node will be brought down in the test run");
            return;
        }
        List<ClientVmInfo> vms = null;
        if (stopStartVMs == 1) {
            Object vmCycled = SnappyBB.getBB().getSharedMap().get(cycledVM);
            if (vmCycled == null) {
                while (true) {
                    try {
                        if (isLead) vms = stopStartVMs(numToKill, true);
                        else vms = stopStartVMs(numToKill, false);
                        break;
                    } catch (TestException te) {
                    }
                }
            } //first time
            else {
                //relaxing a little for HA tests
                //using the BB to track when to kill the next set of vms
                if (lastCycledTimeFromBB == null) {
                    int sleepMS = 20000;
                    Log.getLogWriter().info("allow  " + sleepMS / 1000 + " seconds before killing others");
                    MasterController.sleepForMs(sleepMS); //no vms has been cycled before
                } else if (lastCycledTimeFromBB > lastCycledTime) {
                    lastCycledTime = lastCycledTimeFromBB;
                    log().info("update last cycled lead vm is set to " + lastCycledTime);
                }

                if (lastCycledTime != 0) {
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastCycledTime < waitTimeBeforeNextCycleVM * THOUSAND) {
                        if (isLead)
                            SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartLeadVms);
                        else SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartVms);
                        return;
                    } else {
                        if (isLead) log().info("cycle lead vm starts at: " + currentTime);
                        else log().info("cycle store vm starts at: " + currentTime);
                    }
                }
                if (isLead) vms = stopStartVMs(numToKill, true);
                else vms = stopStartVMs(numToKill, false);
            }
            if (vms == null || vms.size() == 0) {
                if (isLead) {
                    Log.getLogWriter().info("No lead vm being chosen to be stopped");
                    SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartLeadVms);
                } else {
                    Log.getLogWriter().info("No store vm being chosen to be stopped");
                    SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartVms);
                }
                return;
            }
//            Log.getLogWriter().info("Total number of PR is " + numOfPRs);
//            if (numOfPRs > 0)
//                PRObserver.waitForRebalRecov(vms, 1, numOfPRs, null, null, false);
            long currentTime = System.currentTimeMillis();
            if (isLead) {
                log().info("cycle lead vm finishes at: " + currentTime);
                SnappyBB.getBB().getSharedMap().put(LASTCYCLEDTIMEFORLEAD, currentTime);
                SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartLeadVms);
            } else {
                log().info("cycle store vm finishes at: " + currentTime);
                SnappyBB.getBB().getSharedMap().put(LASTCYCLEDTIME, currentTime);
                SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartVms);
            }
            SnappyBB.getBB().getSharedMap().put(cycledVM, "true");
        }
    }

    protected List<ClientVmInfo> stopStartVMs(int numToKill, boolean isLead) {
        if (isLead) {
            log().info("stopStartVMs : cycle lead vm starts at: " + System.currentTimeMillis());
            return stopStartVMs(numToKill, cycleLeadVMTarget, true);
        } else {
            log().info("stopStartVMs : cycle store vm starts at: " + System.currentTimeMillis());
            return stopStartVMs(numToKill, cycleVMTarget, false);
        }
    }

    protected List<ClientVmInfo> stopStartLeadVM(int numToKill) {
        log().info("cycle lead vm starts at: " + System.currentTimeMillis());
        return stopStartVMs(numToKill, cycleLeadVMTarget, true);
    }

    @SuppressWarnings("unchecked")
    protected List<ClientVmInfo> stopStartVMs(int numToKill, String target, boolean isLead) {
        Object[] tmpArr = null;
        if (isLead) tmpArr = snappyTest.getPrimaryLeadVMWithHA(target);
        else tmpArr = StopStartVMs.getOtherVMs(numToKill, target);
        // get the VMs to stop; vmList and stopModeList are parallel lists

        Object vm1 = SnappyBB.getBB().getSharedMap().get("storeVMTarget1");
        Object vm2 = SnappyBB.getBB().getSharedMap().get("storeVMTarget2");
        List<ClientVmInfo> vmList;
        List<String> stopModeList;

        if (vm1 == null && vm2 == null) {
            vmList = (List<ClientVmInfo>) (tmpArr[0]);
            stopModeList = (List<String>) (tmpArr[1]);
            for (ClientVmInfo client : vmList) {
                PRObserver.initialize(client.getVmid());
            } //clear bb info for the vms to be stopped/started
        } else {
            vmList = (List<ClientVmInfo>) (tmpArr[0]);
            stopModeList = (List<String>) (tmpArr[1]);
            for (int i = 0; i < vmList.size(); i++) {
                if (vmList.get(i).getVmid().intValue() == ((ClientVmInfo) vm1).getVmid().intValue()
                        || vmList.get(i).getVmid().intValue() == ((ClientVmInfo) vm2).getVmid().intValue()) {
                    Log.getLogWriter().info("remove the vm " + vmList.get(i).getVmid() + " from the stop list");
                    vmList.remove(i);
                } else PRObserver.initialize(vmList.get(i).getVmid());
            }//clear bb info for the vms to be stopped/started
        }
        if (vmList.size() != 0) {
            if (isLead) stopStartVMs(vmList, stopModeList, true);
            else stopStartVMs(vmList, stopModeList, false);
        }
        return vmList;
    }

    protected void stopStartVMs(List<ClientVmInfo> vmList, List<String> stopModeList, boolean isLead) {
        Set<String> myDirList = new LinkedHashSet<String>();
        myDirList = getFileContents("logDir_", myDirList);
        if (vmList.size() != stopModeList.size()) {
            throw new TestException("Expected targetVmList " + vmList + " and stopModeList " +
                    stopModeList + " to be parallel lists of the same size, but they have different sizes");
        }
        Log.getLogWriter().info("In stopStartVMs, vms to stop: " + vmList +
                ", corresponding stop modes: " + stopModeList);
        for (int i = 0; i < vmList.size(); i++) {
            ClientVmInfo targetVm = (ClientVmInfo) (vmList.get(i));
            String stopMode = stopModeList.get(i);
            String clientName = targetVm.getClientName();
            for (String vmDir : myDirList) {
                if (vmDir.contains(clientName)) {
                    if (isLead) recycleVM(vmDir, stopMode, clientName, true);
                    else recycleVM(vmDir, stopMode, clientName, false);
                }
            }
        }
    }

    protected void recycleVM(String vmDir, String stopMode, String clientName, boolean isLead) {
        if (stopMode.equalsIgnoreCase("NiceKill") || stopMode.equalsIgnoreCase("NICE_KILL")) {
            if (isLead) killVM(vmDir, clientName, true);
            else killVM(vmDir, clientName, false);
        }
        if (isLead) startVM(vmDir, clientName, true);
        else startVM(vmDir, clientName, false);
    }

    protected void killVM(String vmDir, String clientName, boolean isLead) {
        File log = null, logFile = null;
        ProcessBuilder pb = null;
        try {
            if (isLead) {
                pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-lead.sh"), "stop", "-dir=" + vmDir);
                log = new File(".");
                String dest = log.getCanonicalPath() + File.separator + "snappyLeaderSystem.log";
                logFile = new File(dest);
            } else {
                pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-server.sh"), "stop", "-dir=" + vmDir);
                log = new File(".");
                String dest = log.getCanonicalPath() + File.separator + "snappyServerSystem.log";
                logFile = new File(dest);
            }
            snappyTest.executeProcess(pb, logFile);
        } catch (IOException e) {
            String s = "problem occurred while retriving logFile path " + log;
            throw new TestException(s, e);
        }
        Log.getLogWriter().info(clientName + " stopped successfully...");
    }

    protected void startVM(String vmDir, String clientName, boolean isLead) {
        if (isLead) {
            regenerateConfigData(vmDir, "leads", clientName, true);
            startSnappyLead();
        } else {
            regenerateConfigData(vmDir, "servers", clientName, false);
            startSnappyServer();
        }
        Log.getLogWriter().info(clientName + " restarted successfully...");
    }

    protected void regenerateConfigData(String vmDir, String confFileName, String clientName, boolean isLead) {
        generateConfig(confFileName);
        Set<String> fileContent = new LinkedHashSet<String>();
        if (isLead) {
            if (isLongRunningTest) writeLeadHostInfo();
            fileContent = snappyTest.getFileContents("leadLogDir", fileContent);
        } else {
            fileContent = snappyTest.getFileContents("serverLogDir", fileContent);
        }
        for (String nodeConfig : fileContent) {
            if (nodeConfig.contains(vmDir)) {
                writeNodeConfigData(confFileName, nodeConfig);
            }
        }
    }

    public static Object[] getPrimaryLeadVMWithHA(String clientMatchStr) {
        ArrayList vmList = new ArrayList();
        ArrayList stopModeList = new ArrayList();
        int myVmID = RemoteTestModule.getMyVmid();
        // get VMs that contain the clientMatchStr
        List vmInfoList = StopStartVMs.getAllVMs();
        vmInfoList = StopStartVMs.getMatchVMs(vmInfoList, clientMatchStr);
        // now all vms in vmInfoList match the clientMatchStr
        do {
            Object[] tmpArr = getClientVmInfo(vmInfoList, clientMatchStr);
            ClientVmInfo info = (ClientVmInfo) tmpArr[0];
            int randInt = (int) tmpArr[1];
            if (info.getVmid().intValue() != myVmID) { // info is not the current VM
                getLeadVM(info, vmList, stopModeList);
            }
            vmInfoList.remove(randInt);
        } while (vmList.size() < vmInfoList.size());
        return new Object[]{vmList, stopModeList, vmInfoList};
    }

    public static Object[] getPrimaryLeadVM(String clientMatchStr) {
        ArrayList vmList = new ArrayList();
        ArrayList stopModeList = new ArrayList();
        // get VMs that contain the clientMatchStr
        List vmInfoList = StopStartVMs.getAllVMs();
        vmInfoList = StopStartVMs.getMatchVMs(vmInfoList, clientMatchStr);
        // now all vms in vmInfoList match the clientMatchStr
        do {
            Object[] tmpArr = getClientVmInfo(vmInfoList, clientMatchStr);
            ClientVmInfo info = (ClientVmInfo) tmpArr[0];
            int randInt = (int) tmpArr[1];
            getLeadVM(info, vmList, stopModeList);
            vmInfoList.remove(randInt);
        } while (vmList.size() < vmInfoList.size());
        return new Object[]{vmList, stopModeList, vmInfoList};
    }

    protected static Object[] getClientVmInfo(List vmInfoList, String clientMatchStr) {
        if (vmInfoList.size() == 0) {
            throw new TestException("Unable to find lead node " +
                    " vms to stop with client match string " + clientMatchStr +
                    "; either a test problem or add StopStartVMs.StopStart_initTask to the test");
        }
        // add a VmId to the list of vms to stop
        int randInt = TestConfig.tab().getRandGen().nextInt(0, vmInfoList.size() - 1);
        ClientVmInfo info = (ClientVmInfo) (vmInfoList.get(randInt));
        return new Object[]{info, randInt};
    }

    protected static void getLeadVM(ClientVmInfo info, ArrayList vmList, ArrayList stopModeList) {
        Set<String> myDirList = new LinkedHashSet<String>();
        myDirList = getFileContents("logDir_", myDirList);
        String vmDir = null;
        String clientName = info.getClientName();
        for (String dir : myDirList) {
            if (dir.contains(clientName)) {
                vmDir = dir;
                break;
            }
        }
        Set<String> fileContent = new LinkedHashSet<String>();
        fileContent = snappyTest.getFileContents("leadLogDir", fileContent);
        boolean found = false;
        for (String nodeConfig : fileContent) {
            if (nodeConfig.contains(vmDir)) {
                //check for active lead member dir
                String searchString1 = "Primary lead lock acquired";
                String searchString2 = "Resuming startup sequence from STANDBY";
                File dirFile = new File(vmDir);
                for (File srcFile : dirFile.listFiles()) {
                    if (srcFile.getAbsolutePath().contains("snappyleader.log")) {
                        try {
                            FileInputStream fis = new FileInputStream(srcFile);
                            BufferedReader br = new BufferedReader(new InputStreamReader(fis));
                            String str = null;
                            while ((str = br.readLine()) != null && !found) {
                                if (str.toLowerCase().contains(searchString1.toLowerCase()) || str.toLowerCase().contains(searchString2.toLowerCase())) {
                                    found = true;
                                }
                            }
                            br.close();
                        } catch (FileNotFoundException e) {
                            String s = "Unable to find file: " + srcFile.getAbsolutePath();
                            throw new TestException(s);
                        } catch (IOException e) {
                            String s = "Problem while reading the file : " + srcFile.getAbsolutePath();
                            throw new TestException(s, e);
                        }
                    }
                }
            }
        }
        if (found) {
            vmList.add(info);
            // choose a stopMode
            String choice = TestConfig.tab().stringAt(StopStartPrms.stopModes, "NICE_KILL");
            stopModeList.add(choice);
        }
    }

    protected static String getSparkMasterHost() {
        String masterHost = (String) SnappyBB.getBB().getSharedMap().get("masterHost");
        if (masterHost == null) {
            try {
                File log = new File(".");
                String dest = log.getCanonicalPath();
                String masterFileName = "spark-*.Master-1-*.out";
                String masterFilePath = snappyTest.getUserAppJarLocation(masterFileName, dest);
                masterHost = masterFilePath.substring(masterFilePath.lastIndexOf("Master-1-") + 9, masterFilePath.lastIndexOf(".out"));
                SnappyBB.getBB().getSharedMap().put("masterHost", masterHost);
                Log.getLogWriter().info("Master host is : " + SnappyBB.getBB().getSharedMap().get("masterHost"));
            } catch (Exception e) {
                String s = "Spark Master host not found";
                throw new HydraRuntimeException(s, e);
            }
        }
        return masterHost;
    }

    protected void startSnappyServer() {
        File log = null;
        ProcessBuilder pb = null;
        try {
            if (useRowStore) {
                Log.getLogWriter().info("Starting server/s using rowstore option...");
                pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-servers.sh"), "start", "rowstore");
            } else {
                pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-servers.sh"), "start");
            }
            log = new File(".");
            String dest = log.getCanonicalPath() + File.separator + "snappyServerSystem.log";
            File logFile = new File(dest);
            snappyTest.executeProcess(pb, logFile);
            if (useRowStore)
                snappyTest.recordSnappyProcessIDinNukeRun("GfxdServerLauncher");
            else snappyTest.recordSnappyProcessIDinNukeRun("ServerLauncher");
        } catch (IOException e) {
            String s = "problem occurred while retriving logFile path " + log;
            throw new TestException(s, e);
        }
    }

    protected void startSnappyLead() {
        File log = null;
        try {
            ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-leads.sh"), "start");
            log = new File(".");
            String dest = log.getCanonicalPath() + File.separator + "snappyLeaderSystem.log";
            File logFile = new File(dest);
            snappyTest.executeProcess(pb, logFile);
            snappyTest.recordSnappyProcessIDinNukeRun("LeaderLauncher");
        } catch (IOException e) {
            String s = "problem occurred while retriving logFile path " + log;
            throw new TestException(s, e);
        }
    }

    protected LogWriter log() {
        return Log.getLogWriter();
    }

}

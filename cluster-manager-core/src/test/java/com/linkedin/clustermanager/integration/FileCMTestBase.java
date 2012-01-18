	package com.linkedin.clustermanager.integration;

import java.util.Date;
import java.util.List;

import org.apache.log4j.Logger;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;

import com.linkedin.clustermanager.ClusterManagementService;
import com.linkedin.clustermanager.ClusterManager;
import com.linkedin.clustermanager.ClusterManagerFactory;
import com.linkedin.clustermanager.InstanceType;
import com.linkedin.clustermanager.TestHelper;
import com.linkedin.clustermanager.ZNRecord;
import com.linkedin.clustermanager.agent.file.FileClusterManagementTool;
import com.linkedin.clustermanager.controller.GenericClusterController;
import com.linkedin.clustermanager.mock.storage.DummyProcess;
import com.linkedin.clustermanager.model.IdealState;
import com.linkedin.clustermanager.model.InstanceConfig;
import com.linkedin.clustermanager.model.InstanceConfig.InstanceConfigProperty;
import com.linkedin.clustermanager.store.PropertyJsonComparator;
import com.linkedin.clustermanager.store.PropertyJsonSerializer;
import com.linkedin.clustermanager.store.file.FilePropertyStore;
import com.linkedin.clustermanager.tools.IdealStateCalculatorForStorageNode;

/**
 * Test base for dynamic file-based cluster manager
 *
 * @author zzhang
 *
 */

public class FileCMTestBase
{
  private static Logger logger = Logger.getLogger(FileCMTestBase.class);
  protected final String CLUSTER_NAME = "CLUSTER_" + getShortClassName();
  private static final String TEST_DB = "TestDB";
  protected static final String STATE_MODEL = "MasterSlave";
  protected static final int NODE_NR = 5;
  protected static final int START_PORT = 12918;
  private final String ROOT_PATH = "/tmp/" + getShortClassName();

//  private static final PropertyJsonSerializer<ZNRecord> serializer = new PropertyJsonSerializer<ZNRecord>(
//      ZNRecord.class);
//  private static final PropertyJsonComparator<ZNRecord> comparator = new PropertyJsonComparator<ZNRecord>(
//      ZNRecord.class);
//
  protected final FilePropertyStore<ZNRecord> _fileStore
    = new FilePropertyStore<ZNRecord>(new PropertyJsonSerializer<ZNRecord>(ZNRecord.class),
                                      ROOT_PATH,
                                      new PropertyJsonComparator<ZNRecord>(ZNRecord.class));
//  protected final FileBasedDataAccessor _accessor = new FileBasedDataAccessor(
//      _store, CLUSTER_NAME);

  protected ClusterManager _manager;
  protected ClusterManagementService _mgmtTool;

  @BeforeClass()
  public void beforeClass() throws Exception
  {
//    _manager = ClusterManagerFactory.getFileBasedManagerForController(
//        CLUSTER_NAME, "controller_0", _accessor);
//    try
//    {
//      _manager = ClusterManagerFactory.getDynamicFileClusterManager(CLUSTER_NAME,
//                                                                    "controller_0",
//                                                                    InstanceType.CONTROLLER,
//                                                                    _fileStore);
//    }
//    catch (Exception e)
//    {
//      // TODO Auto-generated catch block
//      e.printStackTrace();
//    }

    _mgmtTool = new FileClusterManagementTool(_fileStore);
        // _manager.getClusterManagmentTool();

    // setup cluster
    _mgmtTool.addCluster(CLUSTER_NAME, true);
    _mgmtTool.addResourceGroup(CLUSTER_NAME, TEST_DB, 10, STATE_MODEL);
    for (int i = 0; i < NODE_NR; i++)
    {
      addNodeToCluster(CLUSTER_NAME, "localhost", START_PORT + i);
    }
    rebalanceStorageCluster(CLUSTER_NAME, TEST_DB, 2);

    // start dummy storage nodes
    for (int i = 0; i < NODE_NR; i++)
    {
//      DummyProcess process = new DummyProcess(null, CLUSTER_NAME, "localhost_"
//          + (START_PORT + i), null, 0, _accessor);
      DummyProcess process = new DummyProcess(null,
                                              CLUSTER_NAME,
                                              "localhost_" + (START_PORT + i),
                                              "dynamic-file",
                                              null,
                                              0,
                                              _fileStore);
      try
      {
        process.start();
      }
      catch (Exception e)
      {
        logger
            .error("fail to start dummy participant using dynmaic file-based cluster-manager", e);
      }

      _manager =
          ClusterManagerFactory.getDynamicFileClusterManager(CLUSTER_NAME,
                                                             "controller_0",
                                                             InstanceType.CONTROLLER,
                                                             _fileStore);

    }

    // start cluster manager controller
    GenericClusterController controller = new GenericClusterController();
    try
    {
      // manager.addConfigChangeListener(controller);
      _manager.addLiveInstanceChangeListener(controller);
      _manager.addIdealStateChangeListener(controller);
      // manager.addExternalViewChangeListener(controller);
      _manager.connect();
    } catch (Exception e)
    {
      logger.error("fail to start controller using dynamic file-based cluster-manager ", e);
    }

    verifyCluster();
  }

  @AfterClass()
  public void afterClass() throws Exception
  {
    logger.info("START afterClass FileCMTestBase shutting down file-based cluster managers at "
        + new Date(System.currentTimeMillis()));

    // Thread.sleep(3000);
    // _store.stop();
    _manager.disconnect();
    _manager.disconnect();  // test if disconnect() can be called twice

    logger.info("END afterClass FileCMTestBase at " + new Date(System.currentTimeMillis()));

  }

  private String getShortClassName()
  {
    String className = this.getClass().getName();
    return className.substring(className.lastIndexOf('.') + 1);
  }

  private void addNodeToCluster(String clusterName, String host, int port)
  {
    // TODO use ClusterSetup
    String nodeId = host + "_" + port;
    ZNRecord nodeConfig = new ZNRecord(nodeId);
    nodeConfig.setSimpleField(InstanceConfigProperty.HOST.toString(), host);
    nodeConfig.setSimpleField(InstanceConfigProperty.PORT.toString(),
        Integer.toString(port));
    nodeConfig.setSimpleField(InstanceConfigProperty.ENABLED.toString(),
        Boolean.toString(true));
    _mgmtTool.addInstance(CLUSTER_NAME, new InstanceConfig(nodeConfig));
  }

  protected void rebalanceStorageCluster(String clusterName,
      String resourceGroupName, int replica)
  {
    List<String> nodeNames = _mgmtTool.getInstancesInCluster(clusterName);

    IdealState idealState = _mgmtTool.getResourceGroupIdealState(clusterName, resourceGroupName);
    int partitions = idealState.getNumPartitions();

    ZNRecord newIdealState = IdealStateCalculatorForStorageNode
        .calculateIdealState(nodeNames, partitions, replica, resourceGroupName,
            "MASTER", "SLAVE");

    newIdealState.merge(idealState.getRecord());
    _mgmtTool.setResourceGroupIdealState(clusterName, resourceGroupName, new IdealState(newIdealState));
  }

  protected void verifyCluster()
  {
    TestHelper.verifyWithTimeout("verifyBestPossAndExtViewFile",
                                 TEST_DB,
                                 10,
                                 "MasterSlave",
                                 TestHelper.<String>setOf(CLUSTER_NAME),
                                 _fileStore);
  }
}

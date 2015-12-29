hydra.Prms-testRequirement = "snapshot (export/import) (baseline) test";
hydra.Prms-testDescription = "
Create cache and configured regions. Regions can be configured with eviction, TTL or IdleTO. All members contribute to loading the region.  After writing the region snapshot to the blackboard, export the region to disk.  Restart all vms and import the data (verifying snapshot on import).
";

INCLUDE $JTESTS/hydraconfig/hydraparams1.inc;
INCLUDE $JTESTS/hydraconfig/topology_2.inc;

// all topology groups are peers
hydra.GemFirePrms-distributedSystem = ds;

// force the working threads to be 0 based (threadIds) so we 
// can control uniqueKeys based on a mod of numThreads (with this task)
// threadIds need to be zero based for this to work
hydra.VmPrms-extraVMArgs   = fcn "hydra.TestConfigFcns.duplicate
                                  (\"-Xmx20m\", ${${A}Hosts}, true)"
                             ncf
                             ,
                             fcn "hydra.TestConfigFcns.duplicate
                                  (\"-Xmx250m\", ${${B}Hosts}, true)"
                             ncf
                             ;

THREADGROUP controllerThread
    totalThreads = 1
    clientNames  = fcn "hydra.TestConfigFcns.generateNames
                        (\"${A}\", ${${A}Hosts}, true)"
                   ncf;

THREADGROUP workerThreads
    totalThreads = fcn
                   ${${B}Hosts} * ${${B}VMsPerHost} * ${${B}ThreadsPerVM}
                   ncf
    clientNames  = fcn "hydra.TestConfigFcns.generateNames
                        (\"${B}\", ${${B}Hosts}, true)"
                   ncf;

// setup for stop/start of gemfire (caching) members
INITTASK     taskClass   = util.StopStartVMs  taskMethod = StopStart_initTask
             threadGroups = workerThreads;

// on dynamic restart we need to wipe out the disk files before creating the cache/regions
INITTASK     taskClass   = snapshot.SnapshotTest taskMethod = HydraTask_removeDiskFiles
             runMode = dynamic
             threadGroups = workerThreads;

// initTasks with runMode always
INITTASK     taskClass   = snapshot.SnapshotTest  taskMethod = HydraTask_initialize
             threadGroups = workerThreads
             runMode = always;

INITTASK     taskClass = util.AdminHelper  taskMethod = HydraTask_initializeAdminDS
             threadGroups = controllerThread;

// initTask run at startup only
INITTASK     taskClass   = snapshot.SnapshotTest  taskMethod = HydraTask_loadRegion
             threadGroups = workerThreads
             batch;

INITTASK     taskClass   = snapshot.SnapshotTest  taskMethod = HydraTask_resetUniqueKeyIndex
             threadGroups = workerThreads;

INITTASK     taskClass   = snapshot.SnapshotTest  taskMethod = HydraTask_initializeController
             threadGroups = controllerThread;

TASK         taskClass   = snapshot.SnapshotTest  taskMethod = HydraTask_snapshotController
             threadGroups = controllerThread;

TASK         taskClass   = snapshot.SnapshotTest  taskMethod = HydraTask_snapshotResponder
             threadGroups = workerThreads;

CLOSETASK    taskClass   = snapshot.SnapshotTest taskMethod = CloseTask_closeCache
             runMode = dynamic
             threadGroups = workerThreads;

hydra.Prms-totalTaskTimeSec = 1200; 
hydra.Prms-totalTaskTimeSec = 31536000;  // test terminated by secondsToRun
snapshot.SnapshotPrms-secondsToRun = 900;
hydra.Prms-maxResultWaitSec = 600;

INCLUDE $JTESTS/util/randomValues.inc;
util.RandomValuesPrms-objectType = byte[];
util.RandomValuesPrms-elementSize = RANGE 500 1000 EGNAR;
util.ValueHolderPrms-useExtraObject = true;
hydra.GemFirePrms-conserveSockets = true;

hydra.ConfigPrms-cacheConfig    = cache1;
hydra.CachePrms-names           = cache1;
hydra.CachePrms-searchTimeout   = 600;

hydra.Prms-useFixedRandomInMaster= true;
hydra.ConfigPrms-regionConfig    = ${regionConfigName};
hydra.RegionPrms-names           = persistentReplicateRegion replicateRegion persistentPartitionRegion partitionRegion;
hydra.RegionPrms-regionName      = TestRegion;
hydra.RegionPrms-dataPolicy      = persistentReplicate       replicate       persistentPartition       partition;
hydra.RegionPrms-scope           = ack                       ack             default;
hydra.RegionPrms-partitionName   = none                      none            pr;

hydra.PartitionPrms-names = pr;
hydra.PartitionPrms-redundantCopies = ${redundantCopies};   

hydra.RegionPrms-diskStoreName   = diskStore1;
hydra.RegionPrms-diskSynchronous = true;
hydra.RegionPrms-cacheListeners =  util.SummaryLogListener;

hydra.DiskStorePrms-names = diskStore1;
hydra.DiskStorePrms-allowForceCompaction = true;  // this test explicitly calls compaction
hydra.DiskStorePrms-autoCompact = false;  // this test explicitly calls compaction
hydra.DiskStorePrms-maxOplogSize = 1; // frequent new logs

snapshot.SnapshotPrms-useUniqueKeys = true;
snapshot.SnapshotPrms-numToLoad = 5000;  // start small, todo@lhughes - increase once test is running
snapshot.SnapshotPrms-exportStrategy = apiFromOneVm;
snapshot.SnapshotPrms-executeConcurrentOps = false;
snapshot.SnapshotPrms-executeConcurrentRebalance = false;
snapshot.SnapshotPrms-useFilterOnExport = ${useFilterOnExport};
snapshot.SnapshotPrms-useFilterOnImport = ${useFilterOnImport};

snapshot.SnapshotPrms-operations = ONEOF add add destroy destroy invalidate invalidate update update get get getNew getNew putAll FOENO;
snapshot.SnapshotPrms-lowerThreshold = 1000;
snapshot.SnapshotPrms-upperThreshold = 10000;
snapshot.SnapshotPrms-lowerThresholdOperations = ONEOF add putAll FOENO;
snapshot.SnapshotPrms-upperThresholdOperations = ONEOF destroy FOENO;
snapshot.SnapshotPrms-numPutAllNewKeys = RANGE 1 10 EGNAR;
snapshot.SnapshotPrms-numPutAllExistingKeys = RANGE 1 10 EGNAR;

util.TestHelperPrms-minTaskGranularitySec = 30;
util.StopStartPrms-stopModes = nice_exit;  // allow closetask to run to remove disk files (nice_exit vs. nice_kill)

package org.ovirt.engine.core.vdsbroker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.ovirt.engine.core.common.AuditLogType;
import org.ovirt.engine.core.common.FeatureSupported;
import org.ovirt.engine.core.common.businessentities.NonOperationalReason;
import org.ovirt.engine.core.common.businessentities.SELinuxMode;
import org.ovirt.engine.core.common.businessentities.VDS;
import org.ovirt.engine.core.common.businessentities.VDSDomainsData;
import org.ovirt.engine.core.common.businessentities.VDSStatus;
import org.ovirt.engine.core.common.businessentities.VM;
import org.ovirt.engine.core.common.businessentities.VMStatus;
import org.ovirt.engine.core.common.businessentities.VdsDynamic;
import org.ovirt.engine.core.common.businessentities.VdsNumaNode;
import org.ovirt.engine.core.common.businessentities.VdsSpmStatus;
import org.ovirt.engine.core.common.businessentities.VdsStatistics;
import org.ovirt.engine.core.common.businessentities.VmDynamic;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.locks.LockingGroup;
import org.ovirt.engine.core.common.utils.Pair;
import org.ovirt.engine.core.common.vdscommands.DestroyVmVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.SetVdsStatusVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.SetVmStatusVDSCommandParameters;
import org.ovirt.engine.core.common.vdscommands.VDSCommandType;
import org.ovirt.engine.core.common.vdscommands.VDSReturnValue;
import org.ovirt.engine.core.common.vdscommands.VdsIdAndVdsVDSCommandParametersBase;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.compat.TransactionScopeOption;
import org.ovirt.engine.core.compat.Version;
import org.ovirt.engine.core.dal.dbbroker.DbFacade;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogDirector;
import org.ovirt.engine.core.dal.dbbroker.auditloghandling.AuditLogableBase;
import org.ovirt.engine.core.utils.NumaUtils;
import org.ovirt.engine.core.utils.crypt.EngineEncryptionUtils;
import org.ovirt.engine.core.utils.lock.EngineLock;
import org.ovirt.engine.core.utils.lock.LockManagerFactory;
import org.ovirt.engine.core.utils.threadpool.ThreadPoolUtil;
import org.ovirt.engine.core.utils.timer.OnTimerMethodAnnotation;
import org.ovirt.engine.core.utils.timer.SchedulerUtil;
import org.ovirt.engine.core.utils.timer.SchedulerUtilQuartzImpl;
import org.ovirt.engine.core.utils.transaction.TransactionMethod;
import org.ovirt.engine.core.utils.transaction.TransactionSupport;
import org.ovirt.engine.core.vdsbroker.irsbroker.IRSErrorException;
import org.ovirt.engine.core.vdsbroker.irsbroker.IrsBrokerCommand;
import org.ovirt.engine.core.vdsbroker.vdsbroker.GetCapabilitiesVDSCommand;
import org.ovirt.engine.core.vdsbroker.vdsbroker.HostNetworkTopologyPersister;
import org.ovirt.engine.core.vdsbroker.vdsbroker.HostNetworkTopologyPersisterImpl;
import org.ovirt.engine.core.vdsbroker.vdsbroker.IVdsServer;
import org.ovirt.engine.core.vdsbroker.vdsbroker.VDSNetworkException;
import org.ovirt.engine.core.vdsbroker.vdsbroker.VDSRecoveringException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class VdsManager {
    private static Logger log = LoggerFactory.getLogger(VdsManager.class);
    private static Map<Guid, String> recoveringJobIdMap = new ConcurrentHashMap<Guid, String>();
    private final int numberRefreshesBeforeSave = Config.<Integer> getValue(ConfigValues.NumberVmRefreshesBeforeSave);
    private final Object lockObj = new Object();
    private final AtomicInteger mFailedToRunVmAttempts;
    private final AtomicInteger mUnrespondedAttempts;
    private final AtomicBoolean sshSoftFencingExecuted;
    private final Guid vdsId;
    private final VdsMonitor vdsMonitor = new VdsMonitor();
    private VDS vds;
    private final HostNetworkTopologyPersister hostNetworkTopologyPersister;
    private long lastUpdate;
    private long updateStartTime;
    private long nextMaintenanceAttemptTime;
    private String onTimerJobId;
    private int refreshIteration = 1;
    private boolean isSetNonOperationalExecuted;
    private MonitoringStrategy monitoringStrategy;
    private EngineLock monitoringLock;
    private boolean initialized;
    private IVdsServer vdsProxy;
    private boolean mBeforeFirstRefresh = true;
    private HostMonitoring hostMonitoring;

    private VdsManager(VDS vds) {
        log.info("Entered VdsManager constructor");
        this.vds = vds;
        vdsId = vds.getId();
        monitoringStrategy = MonitoringStrategyFactory.getMonitoringStrategyForVds(vds);
        mUnrespondedAttempts = new AtomicInteger();
        mFailedToRunVmAttempts = new AtomicInteger();
        sshSoftFencingExecuted = new AtomicBoolean(false);
        monitoringLock = new EngineLock(Collections.singletonMap(vdsId.toString(),
                new Pair<String, String>(LockingGroup.VDS_INIT.name(), "")), null);
        hostNetworkTopologyPersister = HostNetworkTopologyPersisterImpl.getInstance();

        handlePreviousStatus();
        handleSecureSetup();
        initVdsBroker();
        this.vds = null;

    }

    public void handleSecureSetup() {
        // if ssl is on and no certificate file
        if (Config.<Boolean> getValue(ConfigValues.EncryptHostCommunication)
                && !EngineEncryptionUtils.haveKey()) {
            if (vds.getStatus() != VDSStatus.Maintenance && vds.getStatus() != VDSStatus.InstallFailed) {
                setStatus(VDSStatus.NonResponsive, vds);
                updateDynamicData(vds.getDynamicData());
            }
            log.error("Could not find VDC Certificate file.");
            AuditLogableBase logable = new AuditLogableBase(vdsId);
            AuditLogDirector.log(logable, AuditLogType.CERTIFICATE_FILE_NOT_FOUND);
        }
    }

    public void handlePreviousStatus() {
        if (vds.getStatus() == VDSStatus.PreparingForMaintenance) {
            vds.setPreviousStatus(vds.getStatus());
        } else {
            vds.setPreviousStatus(VDSStatus.Up);
        }
    }

    public static VdsManager buildVdsManager(VDS vds) {
        VdsManager vdsManager = new VdsManager(vds);
        return vdsManager;
    }

    public void schedulJobs() {
        SchedulerUtil sched = SchedulerUtilQuartzImpl.getInstance();
        int refreshRate = Config.<Integer> getValue(ConfigValues.VdsRefreshRate) * 1000;

        // start with refresh statistics
        refreshIteration = numberRefreshesBeforeSave - 1;

        onTimerJobId =
                sched.scheduleAFixedDelayJob(
                        this,
                        "onTimer",
                        new Class[0],
                        new Object[0],
                        refreshRate,
                        refreshRate,
                        TimeUnit.MILLISECONDS);
    }

    private void initVdsBroker() {
        log.info("Initialize vdsBroker '{}:{}'", vds.getHostName(), vds.getPort());

        // Get the values of the timeouts:
        int clientTimeOut = Config.<Integer> getValue(ConfigValues.vdsTimeout) * 1000;
        int connectionTimeOut = Config.<Integer> getValue(ConfigValues.vdsConnectionTimeout) * 1000;
        int heartbeat = Config.<Integer> getValue(ConfigValues.vdsHeartbeatInSeconds) * 1000;
        int clientRetries = Config.<Integer> getValue(ConfigValues.vdsRetries);
        vdsProxy = TransportFactory.createVdsServer(
                vds.getProtocol(),
                vds.getHostName(),
                vds.getPort(),
                clientTimeOut,
                connectionTimeOut,
                clientRetries,
                heartbeat);
    }

    public void updateVmDynamic(VmDynamic vmDynamic) {
        DbFacade.getInstance().getVmDynamicDao().update(vmDynamic);
    }

    @OnTimerMethodAnnotation("onTimer")
    public void onTimer() {
        if (LockManagerFactory.getLockManager().acquireLock(monitoringLock).getFirst()) {
            try {
                setIsSetNonOperationalExecuted(false);
                Guid storagePoolId = null;
                ArrayList<VDSDomainsData> domainsList = null;
                VDS tmpVds;
                synchronized (getLockObj()) {
                    tmpVds = vds = DbFacade.getInstance().getVdsDao().get(getVdsId());
                    if (vds == null) {
                        log.error("VdsManager::refreshVdsRunTimeInfo - onTimer is NULL for '{}'",
                                getVdsId());
                        return;
                    }

                    try {
                        if (refreshIteration == numberRefreshesBeforeSave) {
                            refreshIteration = 1;
                        } else {
                            refreshIteration++;
                        }
                        if (isMonitoringNeeded()) {
                            setStartTime();
                            hostMonitoring = new HostMonitoring(VdsManager.this, vds, monitoringStrategy);
                            hostMonitoring.refresh();
                            mUnrespondedAttempts.set(0);
                            sshSoftFencingExecuted.set(false);
                            setLastUpdate();
                        }
                        if (!isInitialized() && vds.getStatus() != VDSStatus.NonResponsive
                                && vds.getStatus() != VDSStatus.PendingApproval
                                && vds.getStatus() != VDSStatus.InstallingOS) {
                            log.info("Initializing Host: '{}'", vds.getName());
                            ResourceManager.getInstance().HandleVdsFinishedInit(vds.getId());
                            setInitialized(true);
                        }
                    } catch (VDSNetworkException e) {
                        logNetworkException(e);
                    } catch (VDSRecoveringException ex) {
                        HandleVdsRecoveringException(ex);
                    } catch (RuntimeException ex) {
                        logFailureMessage(ex);
                    }
                    try {
                        if (hostMonitoring != null) {
                            hostMonitoring.afterRefreshTreatment();

                            // Get vds data for updating domains list, ignoring vds which is down, since it's not
                            // connected
                            // to
                            // the storage anymore (so there is no sense in updating the domains list in that case).
                            if (vds != null && vds.getStatus() != VDSStatus.Maintenance) {
                                storagePoolId = vds.getStoragePoolId();
                                domainsList = vds.getDomains();
                            }
                        }

                        vds = null;
                        hostMonitoring = null;
                    } catch (IRSErrorException ex) {
                        logAfterRefreshFailureMessage(ex);
                        if (log.isDebugEnabled()) {
                            logException(ex);
                        }
                    } catch (RuntimeException ex) {
                        logAfterRefreshFailureMessage(ex);
                        logException(ex);
                    }

                }

                // Now update the status of domains, this code should not be in
                // synchronized part of code
                if (domainsList != null) {
                    IrsBrokerCommand.updateVdsDomainsData(tmpVds, storagePoolId, domainsList);
                }
            } catch (Exception e) {
                log.error("Timer update runtimeinfo failed. Exception:", e);
            } finally {
                LockManagerFactory.getLockManager().releaseLock(monitoringLock);
            }
        }
    }

    private void logFailureMessage(RuntimeException ex) {
        log.warn(
                "Failed to refresh VDS, continuing, vds ='{}' ('{}'): {}",
                vds.getName(),
                vds.getId(),
                ex.getMessage());
        log.error("Exception", ex);
    }

    private static void logException(final RuntimeException ex) {
        log.error("ResourceManager::refreshVdsRunTimeInfo", ex);
    }

    private void logAfterRefreshFailureMessage(RuntimeException ex) {
        log.warn(
                "Failed to AfterRefreshTreatment VDS, continuing: {}",
                ex.getMessage());
        log.debug("Exception", ex);
    }

    public boolean isMonitoringNeeded() {
        return (monitoringStrategy.isMonitoringNeeded(vds) &&
                vds.getStatus() != VDSStatus.Installing &&
                vds.getStatus() != VDSStatus.InstallFailed &&
                vds.getStatus() != VDSStatus.Reboot &&
                vds.getStatus() != VDSStatus.Maintenance &&
                vds.getStatus() != VDSStatus.PendingApproval &&
                vds.getStatus() != VDSStatus.InstallingOS &&
                vds.getStatus() != VDSStatus.Down &&
                vds.getStatus() != VDSStatus.Kdumping);
    }

    private void HandleVdsRecoveringException(VDSRecoveringException ex) {
        if (vds.getStatus() != VDSStatus.Initializing && vds.getStatus() != VDSStatus.NonOperational) {
            setStatus(VDSStatus.Initializing, vds);
            DbFacade.getInstance().getVdsDynamicDao().updateStatus(vds.getId(), VDSStatus.Initializing);
            AuditLogableBase logable = new AuditLogableBase(vds.getId());
            logable.addCustomValue("ErrorMessage", ex.getMessage());
            logable.updateCallStackFromThrowable(ex);
            AuditLogDirector.log(logable, AuditLogType.VDS_INITIALIZING);
            log.warn(
                    "Failed to refresh VDS, continuing, vds='{}'({}): {}",
                    vds.getName(),
                    vds.getId(),
                    ex.getMessage());
            log.debug("Exception", ex);
            final int VDS_RECOVERY_TIMEOUT_IN_MINUTES = Config.<Integer> getValue(ConfigValues.VdsRecoveryTimeoutInMinutes);
            String jobId = SchedulerUtilQuartzImpl.getInstance().scheduleAOneTimeJob(this, "onTimerHandleVdsRecovering", new Class[0],
                    new Object[0], VDS_RECOVERY_TIMEOUT_IN_MINUTES, TimeUnit.MINUTES);
            recoveringJobIdMap.put(vds.getId(), jobId);
        }
    }

    @OnTimerMethodAnnotation("onTimerHandleVdsRecovering")
    public void onTimerHandleVdsRecovering() {
        recoveringJobIdMap.remove(getVdsId());
        VDS vds = DbFacade.getInstance().getVdsDao().get(getVdsId());
        if (vds.getStatus() == VDSStatus.Initializing) {
            try {
                ResourceManager
                            .getInstance()
                            .getEventListener()
                            .vdsNonOperational(vds.getId(),
                                    NonOperationalReason.TIMEOUT_RECOVERING_FROM_CRASH,
                                    true,
                                Guid.Empty);
                setIsSetNonOperationalExecuted(true);
            } catch (RuntimeException exp) {
                log.error(
                            "HandleVdsRecoveringException::Error in recovery timer treatment, vds='{}'({}): {}",
                            vds.getName(),
                            vds.getId(),
                            exp.getMessage());
                log.debug("Exception", exp);
            }
        }
    }

    /**
     * Save dynamic data to cache and DB.
     *
     * @param dynamicData
     */
    public void updateDynamicData(VdsDynamic dynamicData) {
        DbFacade.getInstance().getVdsDynamicDao().updateIfNeeded(dynamicData);
    }

    /**
     * Save statistics data to cache and DB.
     *
     * @param statisticsData
     */
    public void updateStatisticsData(VdsStatistics statisticsData) {
        DbFacade.getInstance().getVdsStatisticsDao().update(statisticsData);
    }

    /**
     * Save or update numa data to DB
     *
     * @param vds
     */
    public void updateNumaData(final VDS vds) {
        if (vds.getNumaNodeList() == null || vds.getNumaNodeList().isEmpty()) {
            return;
        }

        final List<VdsNumaNode> numaNodesToSave = new ArrayList<>();
        final List<VdsNumaNode> numaNodesToUpdate = new ArrayList<>();
        final List<Guid> numaNodesToRemove = new ArrayList<>();

        List<VdsNumaNode> dbVdsNumaNodes = DbFacade.getInstance()
                .getVdsNumaNodeDAO().getAllVdsNumaNodeByVdsId(vds.getId());
        for (VdsNumaNode node : vds.getNumaNodeList()) {
            VdsNumaNode searchNode = NumaUtils.getVdsNumaNodeByIndex(dbVdsNumaNodes, node.getIndex());
            if (searchNode != null) {
                node.setId(searchNode.getId());
                numaNodesToUpdate.add(node);
                dbVdsNumaNodes.remove(searchNode);
            }
            else {
                node.setId(Guid.newGuid());
                numaNodesToSave.add(node);
            }
        }
        for (VdsNumaNode node : dbVdsNumaNodes) {
            numaNodesToRemove.add(node.getId());
        }

        //The database operation should be in one transaction
        TransactionSupport.executeInScope(TransactionScopeOption.Required,
                new TransactionMethod<Void>() {
                    @Override
                    public Void runInTransaction() {
                        if (!numaNodesToRemove.isEmpty()) {
                            DbFacade.getInstance()
                                    .getVdsNumaNodeDAO()
                                    .massRemoveNumaNodeByNumaNodeId(numaNodesToRemove);
                        }
                        if (!numaNodesToUpdate.isEmpty()) {
                            DbFacade.getInstance().getVdsNumaNodeDAO().massUpdateNumaNode(numaNodesToUpdate);
                        }
                        if (!numaNodesToSave.isEmpty()) {
                            DbFacade.getInstance()
                                    .getVdsNumaNodeDAO()
                                    .massSaveNumaNode(numaNodesToSave, vds.getId(), null);
                        }
                        return null;
                    }
                });
    }

    public void refreshHost(VDS vds) {
        try {
            refreshCapabilities(new AtomicBoolean(), vds);
        } finally {
            if (vds != null) {
                updateDynamicData(vds.getDynamicData());
                updateNumaData(vds);

                // Update VDS after testing special hardware capabilities
                monitoringStrategy.processHardwareCapabilities(vds);

                // Always check VdsVersion
                ResourceManager.getInstance().getEventListener().handleVdsVersion(vds.getId());
            }
        }
    }

    public void setStatus(VDSStatus status, VDS vds) {
        synchronized (getLockObj()) {
            if (vds == null) {
                vds = DbFacade.getInstance().getVdsDao().get(getVdsId());
            }
            if (vds.getStatus() != status) {
                if (status == VDSStatus.PreparingForMaintenance) {
                    calculateNextMaintenanceAttemptTime();
                }
                vds.setPreviousStatus(vds.getStatus());
                if (this.vds != null) {
                    this.vds.setPreviousStatus(vds.getStatus());
                 }
            }
            // update to new status
            vds.setStatus(status);
            if (this.vds != null) {
                this.vds.setStatus(status);
            }

            switch (status) {
            case NonOperational:
                if (this.vds != null) {
                    this.vds.setNonOperationalReason(vds.getNonOperationalReason());
                }
                if (vds.getVmCount() > 0) {
                    break;
                }
            case NonResponsive:
            case Down:
            case Maintenance:
                vds.setCpuSys(Double.valueOf(0));
                vds.setCpuUser(Double.valueOf(0));
                vds.setCpuIdle(Double.valueOf(0));
                vds.setCpuLoad(Double.valueOf(0));
                vds.setUsageCpuPercent(0);
                vds.setUsageMemPercent(0);
                vds.setUsageNetworkPercent(0);
                if (this.vds != null) {
                    this.vds.setCpuSys(Double.valueOf(0));
                    this.vds.setCpuUser(Double.valueOf(0));
                    this.vds.setCpuIdle(Double.valueOf(0));
                    this.vds.setCpuLoad(Double.valueOf(0));
                    this.vds.setUsageCpuPercent(0);
                    this.vds.setUsageMemPercent(0);
                    this.vds.setUsageNetworkPercent(0);
                }
            default:
                break;
            }
        }
    }

    /**
     * This scheduled method allows this vds to recover from
     * Error status.
     */
    @OnTimerMethodAnnotation("recoverFromError")
    public void recoverFromError() {
        VDS vds = DbFacade.getInstance().getVdsDao().get(getVdsId());

        /**
         * Move vds to Up status from error
         */
        if (vds != null && vds.getStatus() == VDSStatus.Error) {
            setStatus(VDSStatus.Up, vds);
            DbFacade.getInstance().getVdsDynamicDao().updateStatus(getVdsId(), VDSStatus.Up);
            log.info("Settings host '{}' to up after {} failed attempts to run a VM",
                    vds.getName(),
                    mFailedToRunVmAttempts);
            mFailedToRunVmAttempts.set(0);
        }
    }

    /**
     * This callback method notifies this vds that an attempt to run a vm on it
     * failed. above a certain threshold such hosts are marked as
     * VDSStatus.Error.
     *
     * @param vds
     */
    public void failedToRunVm(VDS vds) {
        if (mFailedToRunVmAttempts.get() < Config.<Integer> getValue(ConfigValues.NumberOfFailedRunsOnVds)
                && mFailedToRunVmAttempts.incrementAndGet() >= Config
                        .<Integer> getValue(ConfigValues.NumberOfFailedRunsOnVds)) {
            //Only one thread at a time can enter here
            ResourceManager.getInstance().runVdsCommand(VDSCommandType.SetVdsStatus,
                    new SetVdsStatusVDSCommandParameters(vds.getId(), VDSStatus.Error));

            SchedulerUtil sched = SchedulerUtilQuartzImpl.getInstance();
            sched.scheduleAOneTimeJob(
                    this,
                    "recoverFromError",
                    new Class[0],
                    new Object[0],
                    Config.<Integer>getValue(ConfigValues.TimeToReduceFailedRunOnVdsInMinutes),
                    TimeUnit.MINUTES);
            AuditLogDirector.log(
                    new AuditLogableBase(vds.getId()).addCustomValue(
                            "Time",
                            Config.<Integer> getValue(ConfigValues.TimeToReduceFailedRunOnVdsInMinutes).toString()),
                    AuditLogType.VDS_FAILED_TO_RUN_VMS);
            log.info("Vds '{}' moved to Error mode after {} attempts. Time: {}", vds.getName(),
                    mFailedToRunVmAttempts, new Date());
        }
    }

    /**
     */
    public void succededToRunVm(Guid vmId) {
        mUnrespondedAttempts.set(0);
        sshSoftFencingExecuted.set(false);
        ResourceManager.getInstance().succededToRunVm(vmId, vds.getId());
    }

    public VDSStatus refreshCapabilities(AtomicBoolean processHardwareCapsNeeded, VDS vds) {
        log.debug("monitoring: refresh '{}' capabilities", vds);
        VDS oldVDS = vds.clone();
        GetCapabilitiesVDSCommand<VdsIdAndVdsVDSCommandParametersBase> vdsBrokerCommand =
                new GetCapabilitiesVDSCommand<VdsIdAndVdsVDSCommandParametersBase>(new VdsIdAndVdsVDSCommandParametersBase(vds));
        vdsBrokerCommand.execute();
        if (vdsBrokerCommand.getVDSReturnValue().getSucceeded()) {
            // Verify version capabilities
            HashSet<Version> hostVersions = null;
            Version clusterCompatibility = vds.getVdsGroupCompatibilityVersion();
            if (FeatureSupported.hardwareInfo(clusterCompatibility) &&
                // If the feature is enabled in cluster level, we continue by verifying that this VDS also
                // supports the specific cluster level. Otherwise getHardwareInfo API won't exist for the
                // host and an exception will be raised by VDSM.
                (hostVersions = vds.getSupportedClusterVersionsSet()) != null &&
                hostVersions.contains(clusterCompatibility)) {
                VDSReturnValue ret = ResourceManager.getInstance().runVdsCommand(VDSCommandType.GetHardwareInfo,
                        new VdsIdAndVdsVDSCommandParametersBase(vds));
                if (!ret.getSucceeded()) {
                    AuditLogableBase logable = new AuditLogableBase(vds.getId());
                    logable.updateCallStackFromThrowable(ret.getExceptionObject());
                    AuditLogDirector.log(logable, AuditLogType.VDS_FAILED_TO_GET_HOST_HARDWARE_INFO);
                }
            }

            if (vds.getSELinuxEnforceMode() == null || vds.getSELinuxEnforceMode().equals(SELinuxMode.DISABLED)) {
                AuditLogDirector.log(new AuditLogableBase(vds.getId()), AuditLogType.VDS_NO_SELINUX_ENFORCEMENT);
                if (vds.getSELinuxEnforceMode() != null) {
                    log.warn("Host '{}' is running with disabled SELinux.", vds.getName());
                } else {
                    log.warn("Host '{}' does not report SELinux enforcement information.", vds.getName());
                }
            }

            VDSStatus returnStatus = vds.getStatus();
            NonOperationalReason nonOperationalReason =
                    hostNetworkTopologyPersister.persistAndEnforceNetworkCompliance(vds);

            if (nonOperationalReason != NonOperationalReason.NONE) {
                setIsSetNonOperationalExecuted(true);

                if (returnStatus != VDSStatus.NonOperational) {
                    log.debug(
                            "monitoring: vds '{}' networks do not match its cluster networks, vds will be moved to NonOperational",
                            vds);
                    vds.setStatus(VDSStatus.NonOperational);
                    vds.setNonOperationalReason(nonOperationalReason);
                }
            }

            // We process the software capabilities.
            VDSStatus oldStatus = vds.getStatus();
            if (oldStatus != VDSStatus.Up) {
                // persist to db the host's cpu_flags.
                // TODO this needs to be revisited - either all the logic is in-memory or based on db
                DbFacade.getInstance().getVdsDynamicDao().updateCpuFlags(vds.getId(), vds.getCpuFlags());
                monitoringStrategy.processHardwareCapabilities(vds);
            }
            monitoringStrategy.processSoftwareCapabilities(vds);

            returnStatus = vds.getStatus();

            if (returnStatus != oldStatus && returnStatus == VDSStatus.NonOperational) {
                setIsSetNonOperationalExecuted(true);
            }

            processHardwareCapsNeeded.set(monitoringStrategy.processHardwareCapabilitiesNeeded(oldVDS, vds));

            return returnStatus;
        } else if (vdsBrokerCommand.getVDSReturnValue().getExceptionObject() != null) {
            // if exception is VDSNetworkException then call to
            // handleNetworkException
            if (vdsBrokerCommand.getVDSReturnValue().getExceptionObject() instanceof VDSNetworkException
                    && handleNetworkException((VDSNetworkException) vdsBrokerCommand.getVDSReturnValue()
                            .getExceptionObject(), vds)) {
                updateDynamicData(vds.getDynamicData());
                updateStatisticsData(vds.getStatisticsData());
            }
            throw vdsBrokerCommand.getVDSReturnValue().getExceptionObject();
        } else {
            log.error("refreshCapabilities:GetCapabilitiesVDSCommand failed with no exception!");
            throw new RuntimeException(vdsBrokerCommand.getVDSReturnValue().getExceptionString());
        }
    }

    private long calcTimeoutToFence(int vmCount, VdsSpmStatus spmStatus) {
        int spmIndicator = 0;
        if (spmStatus != VdsSpmStatus.None) {
            spmIndicator = 1;
        }
        int secToFence = (int) (
                // delay time can be fracture number, casting it to int should be enough
                Config.<Integer> getValue(ConfigValues.TimeoutToResetVdsInSeconds) +
                (Config.<Double> getValue(ConfigValues.DelayResetForSpmInSeconds) * spmIndicator) +
                (Config.<Double> getValue(ConfigValues.DelayResetPerVmInSeconds) * vmCount));

        if (sshSoftFencingExecuted.get()) {
            // VDSM restart by SSH has been executed, wait more to see if host is OK
            secToFence = 2 * secToFence;
        }

        return TimeUnit.SECONDS.toMillis(secToFence);
    }

    /**
     * Handle network exception, return true if save vdsDynamic to DB is needed.
     *
     * @param ex
     * @return
     */
    public boolean handleNetworkException(VDSNetworkException ex, VDS vds) {
        if (vds.getStatus() != VDSStatus.Down) {
            long timeoutToFence = calcTimeoutToFence(vds.getVmCount(), vds.getSpmStatus());
            log.warn("Host '{}' is not responding. It will stay in Connecting state for a grace period " +
                    "of {} seconds and after that an attempt to fence the host will be issued.",
                vds.getName(),
                TimeUnit.MILLISECONDS.toSeconds(timeoutToFence));
            AuditLogableBase logable = new AuditLogableBase();
            logable.setVdsId(vds.getId());
            logable.addCustomValue("Seconds", Long.toString(TimeUnit.MILLISECONDS.toSeconds(timeoutToFence)));
            AuditLogDirector.log(logable, AuditLogType.VDS_HOST_NOT_RESPONDING_CONNECTING);
            if (mUnrespondedAttempts.get() < Config.<Integer> getValue(ConfigValues.VDSAttemptsToResetCount)
                    || (lastUpdate + timeoutToFence) > System.currentTimeMillis()) {
                boolean result = false;
                if (vds.getStatus() != VDSStatus.Connecting && vds.getStatus() != VDSStatus.PreparingForMaintenance
                        && vds.getStatus() != VDSStatus.NonResponsive) {
                    setStatus(VDSStatus.Connecting, vds);
                    result = true;
                }
                mUnrespondedAttempts.incrementAndGet();
                return result;
            }

            if (vds.getStatus() == VDSStatus.NonResponsive || vds.getStatus() == VDSStatus.Maintenance) {
                setStatus(VDSStatus.NonResponsive, vds);
                return true;
            }
            setStatus(VDSStatus.NonResponsive, vds);
            moveVMsToUnknown();
            log.info(
                    "Server failed to respond, vds_id='{}', vds_name='{}', vm_count={}, " +
                    "spm_status='{}', non-responsive_timeout (seconds)={}, error: {}",
                    vds.getId(), vds.getName(), vds.getVmCount(), vds.getSpmStatus(),
                    TimeUnit.MILLISECONDS.toSeconds(timeoutToFence), ex.getMessage());

            logable = new AuditLogableBase(vds.getId());
            logable.updateCallStackFromThrowable(ex);
            AuditLogDirector.log(logable, AuditLogType.VDS_FAILURE);
            boolean executeSshSoftFencing = false;
            if (!sshSoftFencingExecuted.getAndSet(true)) {
                executeSshSoftFencing = true;
            }
            ResourceManager.getInstance().getEventListener().vdsNotResponding(vds, executeSshSoftFencing, lastUpdate);
        }
        return true;
    }

    public void dispose() {
        log.info("vdsManager::disposing");
        SchedulerUtilQuartzImpl.getInstance().deleteJob(onTimerJobId);
        vdsProxy.close();
    }

    /**
     * Log the network exception depending on the VDS status.
     *
     * @param e
     *            The exception to log.
     */
    private void logNetworkException(VDSNetworkException e) {
        switch (vds.getStatus()) {
        case Down:
            break;
        case NonResponsive:
            log.debug(
                    "Failed to refresh VDS, network error, continuing, vds='{}'({}): {}",
                    vds.getName(),
                    vds.getId(),
                    e.getMessage());
            break;
        default:
            log.warn(
                    "Failed to refresh VDS, network error, continuing, vds='{}'({}): {}",
                    vds.getName(),
                    vds.getId(),
                    e.getMessage());
        }
        log.debug("Exception", e);
    }

    public void setIsSetNonOperationalExecuted(boolean isExecuted) {
        this.isSetNonOperationalExecuted = isExecuted;
    }

    public boolean isSetNonOperationalExecuted() {
        return isSetNonOperationalExecuted;
    }

    private void setStartTime() {
        updateStartTime = System.currentTimeMillis();
    }
    private void setLastUpdate() {
        lastUpdate = System.currentTimeMillis();
    }

    /**
     * @return elapsed time in milliseconds it took to update the Host run-time info. 0 means the updater never ran.
     */
    public long getLastUpdateElapsed() {
        return lastUpdate - updateStartTime;
    }

    /**
     * @return VdsMonitor a class with means for lock and conditions for signaling
     */
    public VdsMonitor getVdsMonitor() {
        return vdsMonitor;
    }

    /**
     * Resets counter to test VDS response and changes state to Connecting after successful SSH Soft Fencing execution.
     * Changing state to Connecting tells VdsManager to monitor VDS and if VDS doesn't change state to Up, VdsManager
     * will execute standard fencing after timeout interval.
     *
     * @param vds
     *            VDS that SSH Soft Fencing has been executed on
     */
    public void finishSshSoftFencingExecution(VDS vds) {
        // reset the unresponded counter to wait if VDSM restart helps
        mUnrespondedAttempts.set(0);
        // change VDS state to connecting
        setStatus(VDSStatus.Connecting, vds);
        updateDynamicData(vds.getDynamicData());
    }

    public void calculateNextMaintenanceAttemptTime() {
        this.nextMaintenanceAttemptTime = System.currentTimeMillis() + TimeUnit.MILLISECONDS.convert(
                Config.<Integer> getValue(ConfigValues.HostPreparingForMaintenanceIdleTime), TimeUnit.SECONDS);
    }

    public boolean isTimeToRetryMaintenance() {
        return System.currentTimeMillis() > nextMaintenanceAttemptTime;
    }

    private void moveVMsToUnknown() {
        List<VM> vmList = getVmsToMoveToUnknown();
        for (VM vm :vmList) {
            destroyVmOnDestination(vm);
            ResourceManager.getInstance()
                    .runVdsCommand(VDSCommandType.SetVmStatus,
                            new SetVmStatusVDSCommandParameters(vm.getId(), VMStatus.Unknown));
            // log VM transition to unknown status
            AuditLogableBase logable = new AuditLogableBase();
            logable.setVmId(vm.getId());
            AuditLogDirector.log(logable, AuditLogType.VM_SET_TO_UNKNOWN_STATUS);
        }
    }

    private void destroyVmOnDestination(final VM vm) {
        if (vm.getStatus() != VMStatus.MigratingFrom || vm.getMigratingToVds() == null) {
            return;
        }
        // avoid nested locks by doing this in a separate thread
        ThreadPoolUtil.execute(new Runnable() {
            @Override
            public void run() {
                VDSReturnValue returnValue = null;
                returnValue =
                        ResourceManager.getInstance()
                                .runVdsCommand(
                                        VDSCommandType.DestroyVm,
                                        new DestroyVmVDSCommandParameters(vm.getMigratingToVds()
                                                , vm.getId(), true, false, 0)
                                );
                if (returnValue != null && returnValue.getSucceeded()) {
                    log.info("Stopped migrating vm: '{}' on vds: '{}'", vm.getName(), vm.getMigratingToVds());
                }
                else {
                    log.info("Could not stop migrating vm: '{}' on vds: '{}'", vm.getName(),
                            vm.getMigratingToVds());
                }
            }
        });
    }

    private List<VM> getVmsToMoveToUnknown() {
        List<VM> vmList = DbFacade.getInstance().getVmDao().getAllRunningForVds(
                getVdsId());
        List<VM> migratingVms = DbFacade.getInstance().getVmDao().getAllMigratingToHost(
                getVdsId());
        for (VM incomingVm : migratingVms) {
            if (incomingVm.getStatus() == VMStatus.MigratingTo) {
                // this VM is finished the migration handover and is running on this host now
                // and should be treated as well.
                vmList.add(incomingVm);
            }
        }
        return vmList;
    }

    public boolean isInitialized() {
        return initialized;
    }

    public void setInitialized(boolean value) {
        initialized = value;
    }

    public IVdsServer getVdsProxy() {
        return vdsProxy;
    }

    public Guid getVdsId() {
        return vdsId;
    }

    public static void cancelRecoveryJob(Guid vdsId) {
        String jobId = recoveringJobIdMap.remove(vdsId);
        if (jobId != null) {
            log.info("Cancelling the recovery from crash timer for VDS '{}' because vds started initializing", vdsId);
            try {
                SchedulerUtilQuartzImpl.getInstance().deleteJob(jobId);
            } catch (Exception e) {
                log.warn("Failed deleting job '{}' at cancelRecoveryJob: {}", jobId, e.getMessage());
                log.debug("Exception", e);
            }
        }
    }

    public boolean getRefreshStatistics() {
        return (refreshIteration == numberRefreshesBeforeSave);
    }

    public Object getLockObj() {
        return lockObj;
    }

    public boolean getbeforeFirstRefresh() {
        return mBeforeFirstRefresh;
    }

    public void setbeforeFirstRefresh(boolean value) {
        mBeforeFirstRefresh = value;
    }
}

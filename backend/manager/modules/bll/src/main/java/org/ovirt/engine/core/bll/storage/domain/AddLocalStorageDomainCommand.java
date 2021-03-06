package org.ovirt.engine.core.bll.storage.domain;

import javax.inject.Inject;

import org.ovirt.engine.core.bll.Backend;
import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.common.action.AttachStorageDomainToPoolParameters;
import org.ovirt.engine.core.common.action.StorageDomainManagementParameter;
import org.ovirt.engine.core.common.action.VdcActionType;
import org.ovirt.engine.core.common.action.VdcReturnValueBase;
import org.ovirt.engine.core.common.businessentities.StoragePool;
import org.ovirt.engine.core.common.businessentities.StoragePoolStatus;
import org.ovirt.engine.core.common.businessentities.StorageServerConnections;
import org.ovirt.engine.core.common.businessentities.storage.StorageType;
import org.ovirt.engine.core.common.config.Config;
import org.ovirt.engine.core.common.config.ConfigValues;
import org.ovirt.engine.core.common.errors.EngineMessage;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.StoragePoolDao;
import org.ovirt.engine.core.dao.StorageServerConnectionDao;

public class AddLocalStorageDomainCommand<T extends StorageDomainManagementParameter> extends AddStorageDomainCommon<T> {

    @Inject
    private StorageServerConnectionDao storageServerConnectionDao;
    @Inject
    private StoragePoolDao storagePoolDao;

    /**
     * Constructor for command creation when compensation is applied on startup
     */
    public AddLocalStorageDomainCommand(Guid commandId) {
        super(commandId);
    }

    public AddLocalStorageDomainCommand(T parameters, CommandContext commandContext) {
        super(parameters, commandContext);
    }

    @Override
    protected boolean validate() {
        if (!super.validate()) {
            return false;
        }

        StoragePool storagePool = storagePoolDao.getForVds(getParameters().getVdsId());

        if (storagePool == null) {
            return failValidation(EngineMessage.NETWORK_CLUSTER_HAVE_NOT_EXISTING_DATA_CENTER_NETWORK);
        }

        setStoragePool(storagePool);

        if (getStorageDomain().getStorageType() == StorageType.LOCALFS && !storagePool.isLocal()) {
            return failValidation(EngineMessage.ACTION_TYPE_FAILED_STORAGE_POOL_IS_NOT_LOCAL);
        }

        if (storagePool.getStatus() != StoragePoolStatus.Uninitialized) {
            if (!checkMasterDomainIsUp()) {
                return false;
            }
        }

        // we limit RHEV-H local storage to its persistence mount - /data/images/rhev/
        if (getVds().isOvirtVintageNode()) {

            StorageServerConnections conn =
                    storageServerConnectionDao.get(getParameters().getStorageDomain().getStorage());

            String rhevhLocalFSPath = Config.getValue(ConfigValues.RhevhLocalFSPath);
            if (!conn.getConnection().equals(rhevhLocalFSPath)) {
                addValidationMessage(EngineMessage.RHEVH_LOCALFS_WRONG_PATH_LOCATION);
                addValidationMessageVariable("path", rhevhLocalFSPath);
                return false;
            }
        }

        return true;
    }

    @Override
    protected void executeCommand() {
        super.executeCommand();
        if (getSucceeded()) {
            VdcReturnValueBase returnValue = Backend.getInstance()
                    .runInternalAction(
                            VdcActionType.AttachStorageDomainToPool,
                            new AttachStorageDomainToPoolParameters(getStorageDomain().getId(), getStoragePool().getId()));
            if(!returnValue.getSucceeded()) {
                getReturnValue().setSucceeded(false);
                getReturnValue().setFault(returnValue.getFault());
            }
        }
    }
}

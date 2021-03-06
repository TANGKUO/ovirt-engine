package org.ovirt.engine.core.bll;

import java.util.Collections;
import java.util.List;

import javax.inject.Inject;

import org.ovirt.engine.core.bll.context.CommandContext;
import org.ovirt.engine.core.bll.utils.PermissionSubject;
import org.ovirt.engine.core.common.action.RemoveDiskParameters;
import org.ovirt.engine.core.common.action.RemoveMemoryVolumesParameters;
import org.ovirt.engine.core.common.action.VdcActionType;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.SnapshotDao;
import org.ovirt.engine.core.utils.GuidUtils;

/**
 * Command for removing the given memory volumes.
 * Note that no tasks are created, so we don't monitor whether
 * the operation succeed or not as we can't do much when if fails.
 */
@NonTransactiveCommandAttribute
@InternalCommandAttribute
public class RemoveMemoryVolumesCommand<T extends RemoveMemoryVolumesParameters> extends CommandBase<T> {

    @Inject
    private SnapshotDao snapshotDao;

    public RemoveMemoryVolumesCommand(T parameters, CommandContext cmdContext) {
        super(parameters, cmdContext);
    }

    protected RemoveMemoryVolumesCommand(Guid commandId) {
        super(commandId);
    }

    @Override
    protected void executeCommand() {
        if (isMemoryRemovable()) {
            List<Guid> guids = GuidUtils.getGuidListFromString(getParameters().getMemoryVolumes());

            RemoveDiskParameters removeMemoryDumpDiskParameters = new RemoveDiskParameters(guids.get(2));
            removeMemoryDumpDiskParameters.setShouldBeLogged(false);
            runInternalAction(VdcActionType.RemoveDisk, removeMemoryDumpDiskParameters);

            RemoveDiskParameters removeMemoryMetadataDiskParameters = new RemoveDiskParameters(guids.get(4));
            removeMemoryMetadataDiskParameters.setShouldBeLogged(false);
            runInternalAction(VdcActionType.RemoveDisk, removeMemoryMetadataDiskParameters);
        }
        setSucceeded(true);
    }

    private boolean isMemoryRemovable() {
        return snapshotDao.getNumOfSnapshotsByMemory(getParameters().getMemoryVolumes()) == 1
                || getParameters().isForceRemove();
    }

    @Override
    public List<PermissionSubject> getPermissionCheckSubjects() {
        return Collections.emptyList();
    }
}

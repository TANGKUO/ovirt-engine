package org.ovirt.engine.core.dao;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;

import javax.inject.Inject;

import org.junit.Test;
import org.ovirt.engine.core.common.businessentities.VDSStatus;
import org.ovirt.engine.core.common.businessentities.VdsDynamic;
import org.ovirt.engine.core.common.businessentities.VdsStatic;
import org.ovirt.engine.core.common.businessentities.VdsStatistics;
import org.ovirt.engine.core.common.businessentities.network.VdsNetworkInterface;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dao.network.InterfaceDao;

public class VdsStaticDaoTest extends BaseDaoTestCase {
    @Inject
    private InterfaceDao interfaceDao;

    private VdsStaticDao dao;
    private VdsDynamicDao dynamicDao;
    private VdsStatisticsDao statisticsDao;
    private VdsStatic existingVds;
    private VdsStatic newStaticVds;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        dao = dbFacade.getVdsStaticDao();
        dynamicDao = dbFacade.getVdsDynamicDao();
        statisticsDao = dbFacade.getVdsStatisticsDao();
        existingVds = dao.get(FixturesTool.VDS_GLUSTER_SERVER2);
        newStaticVds = new VdsStatic();
        newStaticVds.setHostName("farkle.redhat.com");
        newStaticVds.setSshPort(22);
        newStaticVds.setSshUsername("root");
        newStaticVds.setClusterId(existingVds.getClusterId());
        newStaticVds.setSshKeyFingerprint("b5:ad:16:19:06:9f:b3:41:69:eb:1c:42:1d:12:b5:31");
        newStaticVds.setCurrentKernelCmdline("a=b");
        newStaticVds.setLastStoredKernelCmdline("c=d");
        newStaticVds.setKernelCmdlineIommu(true);
    }

    /**
     * Ensures that an invalid id returns null.
     */
    @Test
    public void testGetWithInvalidId() {
        VdsStatic result = dao.get(Guid.newGuid());

        assertNull(result);
    }

    /**
     * Ensures that the right object is returned.
     */
    @Test
    public void testGet() {
        VdsStatic result = dao.get(existingVds.getId());

        assertNotNull(result);
        assertEquals(existingVds.getId(), result.getId());
    }

    /**
     * Ensures all the right VdsStatic instances are returned.
     */
    @Test
    public void testGetByHostName() {
        VdsStatic vds = dao.getByHostName(existingVds
                .getHostName());

        assertNotNull(vds);
        assertEquals(existingVds.getHostName(), vds.getHostName());
    }

    /**
     * Ensures all the right set of VdsStatic instances are returned.
     */
    @Test
    public void testGetAllForCluster() {
        List<VdsStatic> result = dao.getAllForCluster(existingVds
                .getClusterId());

        assertNotNull(result);
        assertFalse(result.isEmpty());
        for (VdsStatic vds : result) {
            assertEquals(existingVds.getClusterId(), vds.getClusterId());
        }
    }

    /**
     * Ensures saving a VDS instance works.
     */
    @Test
    public void testSave() {
        dao.save(newStaticVds);

        VdsStatic staticResult = dao.get(newStaticVds.getId());

        assertNotNull(staticResult);
        assertEquals(newStaticVds, staticResult);
    }

    /**
     * Ensures removing a VDS instance works.
     */
    @Test
    public void testRemove() {
        statisticsDao.remove(existingVds.getId());
        dynamicDao.remove(existingVds.getId());
        dao.remove(existingVds.getId());

        VdsStatic resultStatic = dao.get(existingVds.getId());
        assertNull(resultStatic);
        VdsDynamic resultDynamic = dynamicDao.get(existingVds.getId());
        assertNull(resultDynamic);
        VdsStatistics resultStatistics = statisticsDao.get(existingVds.getId());
        assertNull(resultStatistics);
    }

    @Test
    public void testIfExistsHostThatMissesNetworkInCluster() {
        final String networkName = "networkName";
        final VDSStatus initiallyNonExistingHostStatus = VDSStatus.Initializing;
        final boolean resultBeforeStatusUpdate = dao.checkIfExistsHostThatMissesNetworkInCluster(
                existingVds.getClusterId(),
                networkName,
                initiallyNonExistingHostStatus);
        assertFalse(resultBeforeStatusUpdate);

        dynamicDao.updateStatus(existingVds.getId(), initiallyNonExistingHostStatus);

        final boolean resultBeforeAddingNic = dao.checkIfExistsHostThatMissesNetworkInCluster(
                existingVds.getClusterId(),
                networkName,
                initiallyNonExistingHostStatus);

        assertTrue(resultBeforeAddingNic);

        final VdsNetworkInterface nic = createNic(existingVds.getId(), "nic1", networkName);
        interfaceDao.saveInterfaceForVds(nic);

        final boolean resultAfterAddingNic = dao.checkIfExistsHostThatMissesNetworkInCluster(
                existingVds.getClusterId(),
                networkName,
                initiallyNonExistingHostStatus);

        assertFalse(resultAfterAddingNic);

        nic.setNetworkName("not" + networkName);
        interfaceDao.updateInterfaceForVds(nic);

        final boolean resultAfterChangingNetwork = dao.checkIfExistsHostThatMissesNetworkInCluster(
                existingVds.getClusterId(),
                networkName,
                initiallyNonExistingHostStatus);

        assertTrue(resultAfterChangingNetwork);
    }

    private VdsNetworkInterface createNic(Guid hostId, String nicName, String networkName) {
        final VdsNetworkInterface result = new VdsNetworkInterface();
        result.setId(Guid.newGuid());
        result.setName(nicName);
        result.setVdsId(hostId);
        result.setNetworkName(networkName);
        return result;
    }
}

package org.ovirt.engine.core.bll.network.macpool;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.junit.Assert.assertThat;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.anyListOf;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.runners.MockitoJUnitRunner;
import org.ovirt.engine.core.bll.DbDependentTestBase;
import org.ovirt.engine.core.common.businessentities.Cluster;
import org.ovirt.engine.core.common.businessentities.MacPool;
import org.ovirt.engine.core.common.businessentities.MacRange;
import org.ovirt.engine.core.common.businessentities.network.VmNic;
import org.ovirt.engine.core.common.errors.EngineException;
import org.ovirt.engine.core.compat.Guid;
import org.ovirt.engine.core.dal.dbbroker.DbFacade;
import org.ovirt.engine.core.dao.AuditLogDao;
import org.ovirt.engine.core.dao.ClusterDao;
import org.ovirt.engine.core.dao.MacPoolDao;
import org.ovirt.engine.core.dao.network.VmNicDao;

@RunWith(MockitoJUnitRunner.class)
public class MacPoolPerClusterTest extends DbDependentTestBase {
    @Rule
    public ExpectedException expectedException = ExpectedException.none();

    @Mock
    private ClusterDao clusterDao;

    @Mock
    private VmNicDao vmNicDao;

    @Mock
    private MacPoolDao macPoolDao;

    @Mock
    private AuditLogDao auditLogDao;

    @Mock
    private DecoratedMacPoolFactory decoratedMacPoolFactory;


    private MacPool macPool;
    private Cluster cluster;
    private VmNic vmNic;
    private static final String MAC_FROM = "00:1a:4a:15:c0:00";
    private static final String MAC_TO = "00:1a:4a:15:c0:ff";
    private MacPoolPerCluster macPoolPerCluster;

    @Before
    public void setUp() throws Exception {
        when(DbFacade.getInstance().getAuditLogDao()).thenReturn(auditLogDao);

        macPool = createMacPool(MAC_FROM, MAC_TO);
        cluster = createCluster(macPool);
        vmNic = createVmNic();

        when(decoratedMacPoolFactory.createDecoratedPool(any(Guid.class),
                any(org.ovirt.engine.core.bll.network.macpool.MacPool.class),
                anyListOf(MacPoolDecorator.class)))
                .thenAnswer(invocation -> invocation.getArguments()[1]);

        macPoolPerCluster = new MacPoolPerCluster(macPoolDao,
                clusterDao,
                new MacPoolFactory(),
                decoratedMacPoolFactory);
    }

    @Test()
    public void testPoolDoesNotExistForGivenCluster() throws Exception {
        macPoolPerCluster.initialize();
        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage(MacPoolPerCluster.INEXISTENT_POOL_EXCEPTION_MESSAGE);
        macPoolPerCluster.getMacPoolForCluster(Guid.newGuid(), null);
    }

    @Test
    public void testPoolOfGivenGuidExist() {
        mockCluster(cluster);
        mockGettingAllMacPools(macPool);
        macPoolPerCluster.initialize();
        assertThat(macPoolPerCluster.getMacPoolForCluster(cluster.getId(), null), is(notNullValue()));
    }

    @Test
    public void testNicIsCorrectlyAllocatedInScopedPool() throws Exception {
        mockCluster(cluster);
        mockGettingAllMacPools(macPool);
        mockAllMacsForCluster(cluster, vmNic.getMacAddress());

        macPoolPerCluster.initialize();
        assertThat("scoped pool for this nic should exist",
                macPoolPerCluster.getMacPoolForCluster(cluster.getId(), null), is(notNullValue()));

        assertThat("provided mac should be used in returned pool",
                macPoolPerCluster.getMacPoolForCluster(cluster.getId(), null).isMacInUse(vmNic.getMacAddress()), is(true));
    }

    @Test
    public void testCreatePool() throws Exception {
        macPoolPerCluster.initialize();

        mockCluster(cluster);
        macPoolPerCluster.createPool(macPool);
        assertThat("scoped pool for this data center should exist",
                macPoolPerCluster.getMacPoolForCluster(cluster.getId(), null), is (notNullValue()));
    }

    @Test
    public void testCreatePoolWhichExists() throws Exception {
        mockGettingAllMacPools(macPool);
        macPoolPerCluster.initialize();

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage(MacPoolPerCluster.UNABLE_TO_CREATE_MAC_POOL_IT_ALREADY_EXIST);
        macPoolPerCluster.createPool(macPool);
    }

    @Test
    public void testModifyOfExistingMacPool() throws Exception {
        final String macAddress1 = "00:00:00:00:00:01";
        final String macAddress2 = "00:00:00:00:00:02";

        MacPool macPool = createMacPool(macAddress1, macAddress1);
        Cluster cluster = createCluster(macPool);

        mockCluster(cluster);
        mockGettingAllMacPools(macPool);
        macPoolPerCluster.initialize();

        assertThat(macPoolPerCluster.getMacPoolForCluster(cluster.getId(), null).addMac(MAC_FROM), is(true));
        assertThat(macPoolPerCluster.getMacPoolForCluster(cluster.getId(), null).addMac(MAC_FROM), is(false));

        final String allocatedMac = allocateMac(cluster);

        /*needed due to implementation of modifyPool;
        modify assumes, that all allocated macs is used for vmNics. If allocatedMac succeeded it's expected that all
        vmNics were also successfully persisted to db or all allocated macs were returned to the pool. So after
        allocation we need to mock db, otherwise re-init in modifyPool would return improper results.*/
        mockAllMacsForCluster(cluster, allocatedMac);

        assertThat(allocatedMac, is(macAddress1));
        try {
            allocateMac(cluster);
            Assert.fail("this allocation should not succeed.");
        } catch (EngineException e) {
            //ok, this exception should occur.
        }

        macPool.setAllowDuplicateMacAddresses(true);
        final MacRange macRange = new MacRange();
        macRange.setMacFrom(macAddress1);
        macRange.setMacTo(macAddress2);

        macPool.setRanges(Collections.singletonList(macRange));
        macPoolPerCluster.modifyPool(macPool);

        assertThat(macPoolPerCluster.getMacPoolForCluster(cluster.getId(), null).addMac(MAC_FROM), is(true));
        assertThat(allocateMac(cluster), is(macAddress2));
    }

    protected String allocateMac(Cluster cluster) {
        return macPoolPerCluster.getMacPoolForCluster(cluster.getId(), null).allocateNewMac();
    }

    @Test
    public void testModifyOfNotExistingMacPool() throws Exception {
        macPoolPerCluster.initialize();

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage(MacPoolPerCluster.INEXISTENT_POOL_EXCEPTION_MESSAGE);
        macPoolPerCluster.modifyPool(createMacPool(null, null));
    }

    @Test
    public void testRemoveOfMacPool() throws Exception {
        mockCluster(cluster);
        mockGettingAllMacPools(macPool);
        macPoolPerCluster.initialize();

        assertThat(macPoolPerCluster.getMacPoolForCluster(cluster.getId(), null), is(notNullValue()));

        macPoolPerCluster.removePool(macPool.getId());

        expectedException.expect(IllegalStateException.class);
        expectedException.expectMessage(MacPoolPerCluster.INEXISTENT_POOL_EXCEPTION_MESSAGE);
        macPoolPerCluster.getMacPoolForCluster(cluster.getId(), null);
    }

    @Test
    public void testRemoveOfInexistentMacPool() throws Exception {
        macPoolPerCluster.initialize();

        try {
            macPoolPerCluster.getMacPoolForCluster(cluster.getId(), null);
            Assert.fail("pool for given data center should not exist");
        } catch (IllegalStateException e) {
            //ignore this exception.
        }

        macPoolPerCluster.removePool(macPool.getId());
        //nothing to test, should not fail.
    }

    private Cluster createCluster(MacPool macPool) {

        //mock existing data centers.
        final Cluster cluster = new Cluster();
        cluster.setId(Guid.newGuid());
        cluster.setMacPoolId(macPool.getId());
        return cluster;
    }

    private MacPool createMacPool(String macFrom, String macTo) {
        final MacRange macRange = new MacRange();
        macRange.setMacFrom(macFrom);
        macRange.setMacTo(macTo);

        final MacPool macPool = new MacPool();
        macPool.setId(Guid.newGuid());
        macPool.setRanges(Collections.singletonList(macRange));
        return macPool;
    }

    protected void mockAllMacsForCluster(Cluster cluster, String... macAddress) {
        when(macPoolDao.getAllMacsForMacPool(eq(cluster.getMacPoolId()))).thenReturn(Arrays.asList(macAddress));
    }

    protected void mockGettingAllMacPools(MacPool... macPool) {
        when(macPoolDao.getAll()).thenReturn(Arrays.asList(macPool));
    }

    protected VmNic createVmNic() {
        final VmNic vmNic = new VmNic();
        vmNic.setMacAddress("00:1a:4a:15:c0:fe");
        return vmNic;
    }

    protected void mockCluster(Cluster cluster) {
        when(clusterDao.get(eq(cluster.getId()))).thenReturn(cluster);
    }
}
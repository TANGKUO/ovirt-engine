package org.ovirt.engine.core.common.businessentities.network;

import java.util.List;
import java.util.Objects;

import javax.validation.Valid;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.ovirt.engine.core.common.businessentities.BusinessEntitiesDefinitions;
import org.ovirt.engine.core.common.businessentities.BusinessEntity;
import org.ovirt.engine.core.common.utils.ToStringBuilder;
import org.ovirt.engine.core.compat.Guid;

public class DnsResolverConfiguration implements BusinessEntity<Guid> {

    private Guid id;

    @NotNull
    @Size(min = 1, max = BusinessEntitiesDefinitions.MAX_SUPPORTED_DNS_CONFIGURATIONS)
    @Valid
    private List<NameServer> nameServers;

    @Override
    public Guid getId() {
        return id;
    }

    @Override
    public void setId(Guid id) {
        this.id = id;
    }

    public List<NameServer> getNameServers() {
        return nameServers;
    }

    public void setNameServers(List<NameServer> nameServers) {
        this.nameServers = nameServers;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof DnsResolverConfiguration)) {
            return false;
        }
        DnsResolverConfiguration that = (DnsResolverConfiguration) o;
        return Objects.equals(getId(), that.getId()) &&
                Objects.equals(getNameServers(), that.getNameServers());
    }

    @Override
    public int hashCode() {
        return Objects.hash(getId(), getNameServers());
    }

    public String toString() {
        return ToStringBuilder.forInstance(this)
                .append("id", getId())
                .append("nameServers", getNameServers())
                .build();
    }
}

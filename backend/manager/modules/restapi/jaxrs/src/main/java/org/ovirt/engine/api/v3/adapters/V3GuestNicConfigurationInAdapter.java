/*
Copyright (c) 2016 Red Hat, Inc.

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

  http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
*/

package org.ovirt.engine.api.v3.adapters;

import static org.ovirt.engine.api.v3.adapters.V3InAdapters.adaptIn;

import org.ovirt.engine.api.model.BootProtocol;
import org.ovirt.engine.api.model.NicConfiguration;
import org.ovirt.engine.api.v3.V3Adapter;
import org.ovirt.engine.api.v3.types.V3GuestNicConfiguration;

public class V3GuestNicConfigurationInAdapter implements V3Adapter<V3GuestNicConfiguration, NicConfiguration> {
    @Override
    public NicConfiguration adapt(V3GuestNicConfiguration from) {
        NicConfiguration to = new NicConfiguration();
        if (from.isSetBootProtocol()) {
            to.setBootProtocol(BootProtocol.fromValue(from.getBootProtocol()));
        }
        if (from.isSetIp()) {
            to.setIp(adaptIn(from.getIp()));
        }
        if (from.isSetName()) {
            to.setName(from.getName());
        }
        if (from.isSetOnBoot()) {
            to.setOnBoot(from.isOnBoot());
        }
        return to;
    }
}

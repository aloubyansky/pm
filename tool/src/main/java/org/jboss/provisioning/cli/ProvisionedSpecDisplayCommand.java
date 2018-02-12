/*
 * Copyright 2016-2018 Red Hat, Inc. and/or its affiliates
 * and other contributors as indicated by the @author tags.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.provisioning.cli;

import org.aesh.command.CommandDefinition;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.config.FeaturePackConfig;
import org.jboss.provisioning.config.ProvisioningConfig;
import org.jboss.provisioning.state.ProvisionedFeaturePack;
import org.jboss.provisioning.state.ProvisionedState;

/**
 *
 * @author Alexey Loubyansky
 */
@CommandDefinition(name="display", description="Prints provisioned spec for the specified installation.")
public class ProvisionedSpecDisplayCommand extends ProvisioningCommand {

    @Override
    protected void runCommand(PmSession session) throws CommandExecutionException {
        if(verbose) {
            final ProvisionedState provisionedState;
            try {
                provisionedState = getManager(session).getProvisionedState();
            } catch (ProvisioningException e) {
                throw new CommandExecutionException("Failed to read provisioned state", e);
            }
            if (provisionedState == null || !provisionedState.hasFeaturePacks()) {
                return;
            }
            for (ProvisionedFeaturePack fp : provisionedState.getFeaturePacks()) {
                session.println(fp.getGav().toString());
            }
        } else {
            final ProvisioningConfig provisionedState;
            try {
                provisionedState = getManager(session).getProvisioningConfig();
            } catch (ProvisioningException e) {
                throw new CommandExecutionException("Failed to read provisioned state", e);
            }
            if (provisionedState == null || !provisionedState.hasFeaturePackDeps()) {
                return;
            }
            for (FeaturePackConfig fp : provisionedState.getFeaturePackDeps()) {
                session.println(fp.getGav().toString());
            }
        }
    }
}

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
package org.jboss.provisioning.cli.cmd.state.configuration;

import org.jboss.provisioning.cli.cmd.state.FPDependentCommandActivator;
import java.io.IOException;
import org.aesh.command.CommandDefinition;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.cli.CommandExecutionException;
import org.jboss.provisioning.cli.PmCommandInvocation;
import org.jboss.provisioning.cli.model.state.State;
import org.jboss.provisioning.config.FeaturePackConfig;

/**
 *
 * @author jdenise@redhat.com
 */
@CommandDefinition(name = "exclude", description = "Exclude a configuration", activator = FPDependentCommandActivator.class)
public class StateExcludeConfigCommand extends AbstractDefaultConfigCommand {

    @Override
    protected void runCommand(PmCommandInvocation invoc, State session, FeaturePackConfig config) throws IOException, ProvisioningException, CommandExecutionException {
        try {
            session.excludeConfiguration(invoc.getPmSession(), ConfigurationUtil.getConfigurations(invoc.getPmSession(), config, getConfiguration()));
        } catch (Exception ex) {
            throw new CommandExecutionException(ex);
        }
    }
}

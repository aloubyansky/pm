/*
 * Copyright 2016-2017 Red Hat, Inc. and/or its affiliates
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

import java.nio.file.Path;
import org.aesh.command.impl.completer.FileOptionCompleter;
import org.aesh.command.option.Option;
import org.jboss.provisioning.DefaultMessageWriter;
import org.jboss.provisioning.ProvisioningManager;

/**
 *
 * @author Alexey Loubyansky
 */
public abstract class ProvisioningCommand extends PmSessionCommand {

    @Option(name="dir", completer=FileOptionCompleter.class, required=false,
            description="Target installation directory.")
    protected String targetDirArg;

    @Option(name = "verbose", shortName = 'v', hasValue = false,
            description = "Whether or not the output should be verbose")
    boolean verbose;

    protected Path getTargetDir(PmSession session) {
        return targetDirArg == null ? session.getWorkDir() : session.getWorkDir().resolve(targetDirArg);
    }

    protected ProvisioningManager getManager(PmSession session) {
        return ProvisioningManager.builder()
                .setArtifactResolver(MavenArtifactRepositoryManager.getInstance())
                .setInstallationHome(getTargetDir(session))
                .setMessageWriter(new DefaultMessageWriter(session.getOut(), session.getErr(), verbose))
                .build();
    }
}

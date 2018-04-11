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
package org.jboss.provisioning.cli.cmd;

import java.util.List;
import org.jboss.provisioning.cli.PmCompleterInvocation;
import org.jboss.provisioning.cli.PmSession;
import org.jboss.provisioning.cli.model.FeatureContainer;
import org.jboss.provisioning.cli.path.FeatureContainerPathConsumer;

/**
 *
 * @author jdenise@redhat.com
 */
public class StateFullPathCompleter extends AbstractPathCompleter {
    @Override
    protected String getCurrentPath(PmCompleterInvocation session) {
        return session.getPmSession().getCurrentPath();
    }

    @Override
    protected void filterCandidates(FeatureContainerPathConsumer consumer, List<String> candidates) {
        // No OP.
    }

    @Override
    protected FeatureContainer getContainer(PmCompleterInvocation completerInvocation) {
        PmSession session = completerInvocation.getPmSession();
        FeatureContainer container = session.getContainer();
        return container;
    }
}

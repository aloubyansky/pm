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

package org.jboss.provisioning.runtime;

import java.util.Collections;
import java.util.List;

import org.jboss.provisioning.config.FeatureGroup;
import org.jboss.provisioning.util.PmCollections;

/**
 *
 * @author Alexey Loubyansky
 */
class FeatureGroupTree {

    private final FeatureGroup fg;
    private List<FeatureGroupTree> branches = Collections.emptyList();

    FeatureGroupTree() {
        this.fg = null;
    }

    FeatureGroupTree(FeatureGroup fg) {
        this.fg = fg;
    }

    void addBranch(FeatureGroupTree branch) {
        branches = PmCollections.add(branches, branch);
    }

    boolean isEmpty() {
        return branches.isEmpty();
    }

    FeatureGroup getLastGroup() {
        return branches.get(branches.size() - 1).fg;
    }
}

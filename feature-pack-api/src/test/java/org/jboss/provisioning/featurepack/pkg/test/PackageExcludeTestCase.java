/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
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

package org.jboss.provisioning.featurepack.pkg.test;

import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.descr.ProvisionedFeaturePackDescription;
import org.jboss.provisioning.descr.ProvisioningDescriptionException;
import org.jboss.provisioning.test.PmInstallFeaturePackTestBase;
import org.jboss.provisioning.test.util.fs.state.DirState;
import org.jboss.provisioning.test.util.fs.state.DirState.DirBuilder;
import org.jboss.provisioning.test.util.repomanager.FeaturePackRepoManager;

/**
 *
 * @author Alexey Loubyansky
 */
public class PackageExcludeTestCase extends PmInstallFeaturePackTestBase {

    @Override
    protected void setupRepo(FeaturePackRepoManager repoManager) {
        repoManager.installer()
        .newFeaturePack(ArtifactCoords.newGav("org.pm.test", "fp-install", "1.0.0.Beta1"))
            .newPackage("a", true)
                .addDependency("b")
                .writeContent("a", "a.txt")
                .getFeaturePack()
            .newPackage("b")
                .addDependency("c")
                .writeContent("b", "b/b.txt")
                .getFeaturePack()
            .newPackage("c", true)
                .addDependency("d")
                .writeContent("c", "c/c/c.txt")
                .getFeaturePack()
            .newPackage("d")
                .writeContent("d", "c/d.txt")
                .getFeaturePack()
            .getInstaller()
        .install();
    }

    @Override
    protected ProvisionedFeaturePackDescription provisionedFeaturePack() throws ProvisioningDescriptionException {
        return ProvisionedFeaturePackDescription.builder()
                .setGav(ArtifactCoords.newGav("org.pm.test", "fp-install", "1.0.0.Beta1"))
                .excludePackage("b")
                .build();
    }

    @Override
    protected DirState provisionedHomeDir(DirBuilder builder) {
        return builder
                .addFile("a.txt", "a")
                .addFile("c/c/c.txt", "c")
                .addFile("c/d.txt", "d")
                .build();
    }
}
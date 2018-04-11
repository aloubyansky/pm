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

package org.jboss.provisioning.featurepack.layout.test;

import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.ArtifactCoords.Gav;
import org.jboss.provisioning.Errors;
import org.jboss.provisioning.ProvisioningDescriptionException;
import org.jboss.provisioning.config.FeaturePackConfig;
import org.jboss.provisioning.layout.FeaturePackLayout;
import org.jboss.provisioning.layout.ProvisioningLayout;
import org.jboss.provisioning.spec.FeaturePackSpec;
import org.jboss.provisioning.spec.PackageSpec;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author Alexey Loubyansky
 */
public class ExcludedExternalPackageDependencyTestCase {

    private static final Gav fp1Gav = ArtifactCoords.newGav("g", "a1", "v");
    private static final Gav fp2Gav = ArtifactCoords.newGav("g", "a2", "v");

    @Test
    public void testRequiredDependency() throws Exception {

        final ProvisioningLayout.Builder builder = ProvisioningLayout.builder()
                .addFeaturePack(FeaturePackLayout.builder(FeaturePackSpec.builder(fp1Gav)
                        .addFeaturePackDep("fp2dep", FeaturePackConfig.builder(fp2Gav)
                                .excludePackage("p1")
                                .build())
                        .addDefaultPackage("p1"))
                        .addPackage(PackageSpec.builder("p1")
                                .addPackageDep("fp2dep", "p1")
                                .build())
                        .build())
                .addFeaturePack(FeaturePackLayout.builder(FeaturePackSpec.builder(fp2Gav))
                        .addPackage(PackageSpec.forName("p1"))
                        .build());

        try {
            builder.build();
            Assert.fail("Cannot build feature-pack description with inconsistent package dependencies.");
        } catch (ProvisioningDescriptionException e) {
            Assert.assertEquals(Errors.unsatisfiedExternalPackageDependency(fp1Gav, "p1", fp2Gav, "p1"), e.getMessage());
        }
    }

    @Test
    public void testOptionalDependency() throws Exception {

        final ProvisioningLayout.Builder builder = ProvisioningLayout.builder()
                .addFeaturePack(FeaturePackLayout.builder(FeaturePackSpec.builder(fp1Gav)
                        .addFeaturePackDep("fp2dep", FeaturePackConfig.builder(fp2Gav)
                                .excludePackage("p1")
                                .build())
                        .addDefaultPackage("p1"))
                        .addPackage(PackageSpec.builder("p1")
                                .addPackageDep("fp2dep", "p1", true)
                                .build())
                        .build())
                .addFeaturePack(FeaturePackLayout.builder(FeaturePackSpec.builder(fp2Gav))
                        .addPackage(PackageSpec.forName("p1"))
                        .build());
        builder.build();
    }
}

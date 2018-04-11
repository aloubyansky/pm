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

package org.jboss.provisioning.config.model.defined;

import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.ArtifactCoords.Gav;
import org.jboss.provisioning.ProvisioningDescriptionException;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.config.FeatureConfig;
import org.jboss.provisioning.config.FeaturePackConfig;
import org.jboss.provisioning.config.ConfigModel;
import org.jboss.provisioning.repomanager.FeaturePackRepositoryManager;
import org.jboss.provisioning.runtime.ResolvedFeatureId;
import org.jboss.provisioning.spec.FeatureParameterSpec;
import org.jboss.provisioning.spec.FeatureSpec;
import org.jboss.provisioning.state.ProvisionedFeaturePack;
import org.jboss.provisioning.state.ProvisionedState;
import org.jboss.provisioning.test.PmInstallFeaturePackTestBase;
import org.jboss.provisioning.xml.ProvisionedConfigBuilder;
import org.jboss.provisioning.xml.ProvisionedFeatureBuilder;

/**
 *
 * @author Alexey Loubyansky
 */
public class SameFeaturePackConfigModelMergeTestCase extends PmInstallFeaturePackTestBase {

    private static final Gav FP_GAV = ArtifactCoords.newGav("org.jboss.pm.test", "fp1", "1.0.0.Final");

    @Override
    protected void setupRepo(FeaturePackRepositoryManager repoManager) throws ProvisioningDescriptionException {
        repoManager.installer()
        .newFeaturePack(FP_GAV)
            .addSpec(FeatureSpec.builder("specA")
                    .addParam(FeatureParameterSpec.createId("name"))
                    .addParam(FeatureParameterSpec.create("p1"))
                    .addParam(FeatureParameterSpec.create("p2"))
                    .addParam(FeatureParameterSpec.create("p3"))
                    .addParam(FeatureParameterSpec.create("p4", "spec"))
                    .build())
            .addConfig(ConfigModel.builder().setModel("model1")
                    .setProperty("prop1", "config1")
                    .setProperty("prop2", "config1")
                    .setProperty("prop3", "config1")
                    .addFeature(new FeatureConfig().setSpecName("specA")
                            .setParam("name", "a1")
                            .setParam("p1", "config1")
                            .setParam("p2", "config1")
                            .setParam("p3", "config1"))
                    .build())
            .addConfig(ConfigModel.builder().setModel("model1")
                    .setProperty("prop2", "config2")
                    .setProperty("prop3", "config2")
                    .addFeature(new FeatureConfig().setSpecName("specA")
                            .setParam("name", "a1")
                            .setParam("p2", "config2")
                            .setParam("p3", "config2"))
                    .build())
            .addConfig(ConfigModel.builder().setModel("model1").setName("main")
                    .setProperty("prop3", "main")
                    .addFeature(new FeatureConfig().setSpecName("specA")
                            .setParam("name", "a1")
                            .setParam("p3", "main"))
                    .build())
            .addConfig(ConfigModel.builder().setModel("model2")
                    .setProperty("prop2", "config2")
                    .setProperty("prop3", "config2")
                    .addFeature(new FeatureConfig().setSpecName("specA")
                            .setParam("name", "a1")
                            .setParam("p2", "config2")
                            .setParam("p3", "config2"))
                    .build())
            .getInstaller()
        .install();
    }

    @Override
    protected FeaturePackConfig featurePackConfig() {
        return FeaturePackConfig.forGav(FP_GAV);
    }

    @Override
    protected ProvisionedState provisionedState() throws ProvisioningException {
        return ProvisionedState.builder()
                .addFeaturePack(ProvisionedFeaturePack.forGav(FP_GAV))
                .addConfig(ProvisionedConfigBuilder.builder()
                        .setModel("model1")
                        .setName("main")
                        .setProperty("prop1", "config1")
                        .setProperty("prop2", "config2")
                        .setProperty("prop3", "main")
                        .addFeature(ProvisionedFeatureBuilder.builder(ResolvedFeatureId.create(FP_GAV, "specA", "name", "a1"))
                                .setConfigParam("p1", "config1")
                                .setConfigParam("p2", "config2")
                                .setConfigParam("p3", "main")
                                .setConfigParam("p4", "spec")
                                .build())
                        .build())
                .build();
    }
}

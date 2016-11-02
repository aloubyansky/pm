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
package org.jboss.provisioning.xml.test;

import java.nio.file.Paths;

import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.descr.ProvisionedFeaturePackDescription;
import org.jboss.provisioning.descr.ProvisionedInstallationDescription;
import org.jboss.provisioning.test.util.XmlParserValidator;
import org.jboss.provisioning.xml.ProvisioningXmlParser;
import org.junit.Assert;
import org.junit.Test;

/**
 *
 * @author Alexey Loubyansky
 */
public class IncludeDefaultTestCase {

    private static final XmlParserValidator<ProvisionedInstallationDescription> validator = new XmlParserValidator<>(
            Paths.get("src/main/resources/schema/pm-provisioning-1_0.xsd"), new ProvisioningXmlParser());

    @Test
    public void testMain() throws Exception {
        ProvisionedInstallationDescription found = validator
                .validateAndParse("xml/provisioning/packages-inherit.xml", null, null);
        ProvisionedInstallationDescription expected = ProvisionedInstallationDescription.builder()
                .addFeaturePack(ProvisionedFeaturePackDescription
                        .forGav(ArtifactCoords.newGav("org.jboss.group1", "fp1", "0.0.1")))
                .addFeaturePack(ProvisionedFeaturePackDescription
                        .forGav(ArtifactCoords.newGav("org.jboss.group1", "fp2", "0.0.2")))
                .addFeaturePack(ProvisionedFeaturePackDescription
                        .forGav(ArtifactCoords.newGav("org.jboss.group2", "fp3", "0.0.3")))
                .addFeaturePack(ProvisionedFeaturePackDescription
                        .builder(ArtifactCoords.newGav("org.jboss.group2", "fp4", "0.0.3"), false).build())
                .build();
        Assert.assertEquals(expected, found);
    }
}

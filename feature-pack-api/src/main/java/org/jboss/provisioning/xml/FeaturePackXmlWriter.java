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
package org.jboss.provisioning.xml;

import java.util.Arrays;

import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.config.FeaturePackConfig;
import org.jboss.provisioning.config.IncludedConfig;
import org.jboss.provisioning.config.PackageConfig;
import org.jboss.provisioning.spec.ConfigSpec;
import org.jboss.provisioning.spec.FeaturePackDependencySpec;
import org.jboss.provisioning.spec.FeaturePackSpec;
import org.jboss.provisioning.xml.FeaturePackXmlParser10.Attribute;
import org.jboss.provisioning.xml.FeaturePackXmlParser10.Element;
import org.jboss.provisioning.xml.util.ElementNode;
import org.jboss.provisioning.xml.util.TextNode;

/**
 *
 * @author Alexey Loubyansky
 */
public class FeaturePackXmlWriter extends BaseXmlWriter<FeaturePackSpec> {

    private static final FeaturePackXmlWriter INSTANCE = new FeaturePackXmlWriter();

    public static FeaturePackXmlWriter getInstance() {
        return INSTANCE;
    }

    private FeaturePackXmlWriter() {
    }

    protected ElementNode toElement(FeaturePackSpec fpSpec) {
        final ElementNode fp = addElement(null, Element.FEATURE_PACK);
        final ArtifactCoords.Gav fpGav = fpSpec.getGav();
        addGAV(fp, fpGav);

        if (fpSpec.hasDependencies()) {
            final ElementNode deps = addElement(fp, Element.DEPENDENCIES);
            for (FeaturePackDependencySpec dep : fpSpec.getDependencies()) {
                write(deps, dep);
            }
        }

        if(fpSpec.hasConfigs()) {
            for(ConfigSpec config : fpSpec.getConfigs()) {
                fp.addChild(ConfigXmlWriter.getInstance().toElement(config, FeaturePackXmlParser10.NAMESPACE_1_0));
            }
        }

        if (fpSpec.hasDefaultPackages()) {
            final ElementNode pkgs = addElement(fp, Element.DEFAULT_PACKAGES);
            final String[] pkgNames = fpSpec.getDefaultPackageNames().toArray(new String[0]);
            Arrays.sort(pkgNames);
            for (String name : pkgNames) {
                writeDefaultPackage(pkgs, name);
            }
        }

        return fp;
    }

    private void addGAV(final ElementNode fp, final ArtifactCoords.Gav fpGav) {
        addAttribute(fp, Attribute.GROUP_ID, fpGav.getGroupId());
        addAttribute(fp, Attribute.ARTIFACT_ID, fpGav.getArtifactId());
        addAttribute(fp, Attribute.VERSION, fpGav.getVersion());
    }

    private static void writeDefaultPackage(ElementNode pkgs, String name) {
        addAttribute(addElement(pkgs, Element.PACKAGE), Attribute.NAME, name);
    }

    private static void write(ElementNode deps, FeaturePackDependencySpec dependency) {
        final ElementNode depElement = addElement(deps, Element.DEPENDENCY);
        final FeaturePackConfig target = dependency.getTarget();
        final ArtifactCoords.Gav gav = target.getGav();
        addAttribute(depElement, Attribute.GROUP_ID, gav.getGroupId());
        addAttribute(depElement, Attribute.ARTIFACT_ID, gav.getArtifactId());
        if(gav.getVersion() != null) {
            addAttribute(depElement, Attribute.VERSION, gav.getVersion());
        }

        if(dependency.getName() != null) {
            addElement(depElement, Element.NAME).addChild(new TextNode(dependency.getName()));
        }

        ElementNode packages = null;
        if (!target.isInheritPackages()) {
            packages = addElement(depElement, Element.PACKAGES);
            addAttribute(packages, Attribute.INHERIT, "false");
        }
        if (target.hasExcludedPackages()) {
            if (packages == null) {
                packages = addElement(depElement, Element.PACKAGES);
            }
            for (String excluded : target.getExcludedPackages()) {
                final ElementNode exclude = addElement(packages, Element.EXCLUDE);
                addAttribute(exclude, Attribute.NAME, excluded);
            }
        }
        if (target.hasIncludedPackages()) {
            if (packages == null) {
                packages = addElement(depElement, Element.PACKAGES);
            }
            for (PackageConfig included : target.getIncludedPackages()) {
                final ElementNode include = addElement(packages, Element.INCLUDE);
                addAttribute(include, Attribute.NAME, included.getName());
            }
        }

        ElementNode defaultConfigs = null;
        if (!target.isInheritConfigs()) {
            defaultConfigs = addElement(depElement, Element.DEFAULT_CONFIGS);
            addAttribute(defaultConfigs, Attribute.INHERIT, "false");
        }
        if (target.hasFullModelsExcluded()) {
            if (defaultConfigs == null) {
                defaultConfigs = addElement(depElement, Element.DEFAULT_CONFIGS);
            }
            for (String excluded : target.getFullModelsExcluded()) {
                final ElementNode exclude = addElement(defaultConfigs, Element.EXCLUDE);
                addAttribute(exclude, Attribute.MODEL, excluded);
            }
        }
        if (target.hasFullModelsIncluded()) {
            if (defaultConfigs == null) {
                defaultConfigs = addElement(depElement, Element.DEFAULT_CONFIGS);
            }
            for (String included : target.getFullModelsIncluded()) {
                final ElementNode include = addElement(defaultConfigs, Element.INCLUDE);
                addAttribute(include, Attribute.MODEL, included);
            }
        }
        if (target.hasExcludedConfigs()) {
            if (defaultConfigs == null) {
                defaultConfigs = addElement(depElement, Element.DEFAULT_CONFIGS);
            }
            for (String model : target.getExcludedModels()) {
                for(String name : target.getExcludedConfigs(model)) {
                    final ElementNode exclude = addElement(defaultConfigs, Element.EXCLUDE);
                    if(model != null) {
                        addAttribute(exclude, Attribute.MODEL, model);
                    }
                    addAttribute(exclude, Attribute.NAME, name);
                }
            }
        }
        if (target.hasIncludedConfigs()) {
            if (defaultConfigs == null) {
                defaultConfigs = addElement(depElement, Element.DEFAULT_CONFIGS);
            }
            for (IncludedConfig config : target.getIncludedConfigs()) {
                final ElementNode includeElement = addElement(defaultConfigs, Element.INCLUDE);
                if(config.getModel() != null) {
                    addAttribute(includeElement, Attribute.MODEL, config.getModel());
                }
                FeatureGroupXmlWriter.addFeatureGroupDepBody(config, Element.FEATURE_PACK.getNamespace(), includeElement);
            }
        }
    }
}

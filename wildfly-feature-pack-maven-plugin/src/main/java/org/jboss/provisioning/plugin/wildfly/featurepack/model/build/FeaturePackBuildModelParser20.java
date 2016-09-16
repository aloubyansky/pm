/*
 * Copyright 2014 Red Hat, Inc.
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

package org.jboss.provisioning.plugin.wildfly.featurepack.model.build;

import org.jboss.provisioning.GAV;
import org.jboss.provisioning.descr.FeaturePackDependencyDescription;
import org.jboss.provisioning.plugin.wildfly.BuildPropertyReplacer;
import org.jboss.provisioning.plugin.wildfly.PropertyResolver;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.ConfigModelParser20;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.FileFilter;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.FileFilterModelParser20;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.FilePermissionsModelParser20;
import org.jboss.provisioning.util.ParsingUtils;
import org.jboss.staxmapper.XMLElementReader;
import org.jboss.staxmapper.XMLExtendedStreamReader;

import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Parses the WildFly-based feature pack build config file (i.e. the config file that is
 * used to create a WildFly-based feature pack, not the config file inside the feature pack).
 *
 * @author Stuart Douglas
 * @author Eduardo Martins
 * @author Alexey Loubyansky
 */
class FeaturePackBuildModelParser20 implements XMLElementReader<WildFlyFeaturePackBuild.Builder> {

    public static final String NAMESPACE_2_0 = "urn:wildfly:feature-pack-build:2.0";

    enum Element {

        ARTIFACT("artifact"),
        BUILD("build"),
        CONFIG(ConfigModelParser20.ELEMENT_LOCAL_NAME),
        COPY_ARTIFACTS(CopyArtifactsModelParser20.ELEMENT_LOCAL_NAME),
        DEPENDENCIES("dependencies"),
        DEPENDENCY("dependency"),
        DIR("dir"),
        EXCLUDES("excludes"),
        FILE_PERMISSIONS(FilePermissionsModelParser20.ELEMENT_LOCAL_NAME),
        FILTER(FileFilterModelParser20.ELEMENT_LOCAL_NAME),
        GROUP("group"),
        LINE_ENDINGS("line-endings"),
        MKDIRS("mkdirs"),
        PACKAGE("package"),
        PACKAGE_SCHEMAS("package-schemas"),
        UNIX("unix"),
        WINDOWS("windows"),

        // default unknown element
        UNKNOWN(null);

        private static final Map<QName, Element> elements;

        static {
            Map<QName, Element> elementsMap = new HashMap<QName, Element>();
            elementsMap.put(new QName(NAMESPACE_2_0, Element.ARTIFACT.getLocalName()), Element.ARTIFACT);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.BUILD.getLocalName()), Element.BUILD);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.CONFIG.getLocalName()), Element.CONFIG);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.COPY_ARTIFACTS.getLocalName()), Element.COPY_ARTIFACTS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.DEPENDENCIES.getLocalName()), Element.DEPENDENCIES);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.DEPENDENCY.getLocalName()), Element.DEPENDENCY);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.DIR.getLocalName()), Element.DIR);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.EXCLUDES.getLocalName()), Element.EXCLUDES);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.FILE_PERMISSIONS.getLocalName()), Element.FILE_PERMISSIONS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.FILTER.getLocalName()), Element.FILTER);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.GROUP.getLocalName()), Element.GROUP);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.LINE_ENDINGS.getLocalName()), Element.LINE_ENDINGS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.MKDIRS.getLocalName()), Element.MKDIRS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.PACKAGE.getLocalName()), Element.PACKAGE);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.PACKAGE_SCHEMAS.getLocalName()), Element.PACKAGE_SCHEMAS);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.UNIX.getLocalName()), Element.UNIX);
            elementsMap.put(new QName(NAMESPACE_2_0, Element.WINDOWS.getLocalName()), Element.WINDOWS);
            elements = elementsMap;
        }

        static Element of(QName qName) {
            QName name;
            if (qName.getNamespaceURI().equals("")) {
                name = new QName(NAMESPACE_2_0, qName.getLocalPart());
            } else {
                name = qName;
            }
            final Element element = elements.get(name);
            return element == null ? UNKNOWN : element;
        }

        private final String name;

        Element(final String name) {
            this.name = name;
        }

        /**
         * Get the local name of this element.
         *
         * @return the local name
         */
        public String getLocalName() {
            return name;
        }
    }

    enum Attribute {

        ARTIFACT_ID("artifact-id"),
        GROUP_ID("group-id"),
        NAME("name"),
        VERSION("version"),

        // default unknown attribute
        UNKNOWN(null);

        private static final Map<QName, Attribute> attributes;

        static {
            Map<QName, Attribute> attributesMap = new HashMap<QName, Attribute>();
            attributesMap.put(new QName(ARTIFACT_ID.getLocalName()), ARTIFACT_ID);
            attributesMap.put(new QName(GROUP_ID.getLocalName()), GROUP_ID);
            attributesMap.put(new QName(NAME.getLocalName()), NAME);
            attributesMap.put(new QName(VERSION.getLocalName()), VERSION);
            attributes = attributesMap;
        }

        static Attribute of(QName qName) {
            final Attribute attribute = attributes.get(qName);
            return attribute == null ? UNKNOWN : attribute;
        }

        private final String name;

        Attribute(final String name) {
            this.name = name;
        }

        /**
         * Get the local name of this element.
         *
         * @return the local name
         */
        public String getLocalName() {
            return name;
        }
    }

    private final BuildPropertyReplacer propertyReplacer;
    private final ConfigModelParser20 configModelParser;
    private final CopyArtifactsModelParser20 copyArtifactsModelParser;
    private final FileFilterModelParser20 fileFilterModelParser;
    private final FilePermissionsModelParser20 filePermissionsModelParser;

    FeaturePackBuildModelParser20(PropertyResolver resolver) {
        this.propertyReplacer = new BuildPropertyReplacer(resolver);
        this.configModelParser = new ConfigModelParser20(this.propertyReplacer);
        this.fileFilterModelParser = new FileFilterModelParser20(this.propertyReplacer);
        this.copyArtifactsModelParser = new CopyArtifactsModelParser20(this.propertyReplacer, this.fileFilterModelParser);
        this.filePermissionsModelParser = new FilePermissionsModelParser20(this.propertyReplacer, this.fileFilterModelParser);
    }

    @Override
    public void readElement(final XMLExtendedStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {

        final Set<Attribute> required = EnumSet.noneOf(Attribute.class);
        final int count = reader.getAttributeCount();
        if (count != 0) {
            throw ParsingUtils.unexpectedContent(reader);
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());

                    switch (element) {
                        case DEPENDENCIES:
                            parseDependencies(reader, builder);
                            break;
                        case CONFIG:
                            builder.setConfig(configModelParser.parseConfig(reader));
                            break;
                        case PACKAGE_SCHEMAS:
                            builder.setPackageSchemas();
                            parsePackageSchemas(reader, builder);
                            break;
                        case COPY_ARTIFACTS:
                            builder.addCopyArtifacts(copyArtifactsModelParser.parseCopyArtifacts(reader));
                            break;
                        case FILE_PERMISSIONS:
                            builder.addFilePermissions(filePermissionsModelParser.parseFilePermissions(reader));
                            break;
                        case MKDIRS:
                            parseMkdirs(reader, builder);
                            break;
                        case LINE_ENDINGS:
                            parseLineEndings(reader, builder);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private void parseDependencies(final XMLStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case DEPENDENCY:
                            parseDependency(reader, builder);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private void parseDependency(XMLStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        String groupId = null;
        String artifactId = null;
        String version = null;
        final int count = reader.getAttributeCount();
        final Set<Attribute> required = EnumSet.of(Attribute.GROUP_ID, Attribute.ARTIFACT_ID);
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            required.remove(attribute);
            switch (attribute) {
                case GROUP_ID:
                    groupId = reader.getAttributeValue(i);
                    break;
                case ARTIFACT_ID:
                    artifactId = reader.getAttributeValue(i);
                    break;
                case VERSION:
                    version = reader.getAttributeValue(i);
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }
        final FeaturePackDependencyDescription.Builder depBuilder = FeaturePackDependencyDescription.builder(new GAV(groupId, artifactId, version));
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    builder.addDependency(depBuilder.build());
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case EXCLUDES:
                            parseExcludes(reader, depBuilder);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
    }

    private void parseExcludes(XMLStreamReader reader, FeaturePackDependencyDescription.Builder depBuilder) throws XMLStreamException {
        ParsingUtils.parseNoAttributes(reader);
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case PACKAGE:
                            depBuilder.excludePackage(parseName(reader));
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private String parseName(final XMLStreamReader reader) throws XMLStreamException {
        final int count = reader.getAttributeCount();
        String name = null;
        final Set<Attribute> required = EnumSet.of(Attribute.NAME);
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            required.remove(attribute);
            switch (attribute) {
                case NAME:
                    name = reader.getAttributeValue(i);
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }
        ParsingUtils.parseNoContent(reader);
        return propertyReplacer.replaceProperties(name);
    }

    private void parsePackageSchemas(XMLStreamReader reader, WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        ParsingUtils.parseNoAttributes(reader);
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case GROUP:
                            builder.addSchemaGroup(parseName(reader));
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private void parseMkdirs(final XMLStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case DIR:
                            builder.addMkdirs(parseName(reader));
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private void parseLineEndings(final XMLStreamReader reader, final WildFlyFeaturePackBuild.Builder builder) throws XMLStreamException {
        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case WINDOWS:
                            parseLineEnding(reader, builder, true);
                            break;
                        case UNIX:
                            parseLineEnding(reader, builder, true);
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

    private void parseLineEnding(XMLStreamReader reader, final WildFlyFeaturePackBuild.Builder builder, boolean windows) throws XMLStreamException {
        if(reader.getAttributeCount() != 0) {
            throw ParsingUtils.unexpectedContent(reader);
        }

        while (reader.hasNext()) {
            switch (reader.nextTag()) {
                case XMLStreamConstants.END_ELEMENT: {
                    return;
                }
                case XMLStreamConstants.START_ELEMENT: {
                    final Element element = Element.of(reader.getName());
                    switch (element) {
                        case FILTER:
                            final FileFilter.Builder filterBuilder = FileFilter.builder();
                            fileFilterModelParser.parseFilter(reader, filterBuilder);
                            if(windows) {
                                builder.addWindowsLineEndFilter(filterBuilder.build());
                            } else {
                                builder.addUnixLineEndFilter(filterBuilder.build());
                            }
                            break;
                        default:
                            throw ParsingUtils.unexpectedContent(reader);
                    }
                    break;
                }
                default: {
                    throw ParsingUtils.unexpectedContent(reader);
                }
            }
        }
        throw ParsingUtils.endOfDocument(reader.getLocation());
    }

}

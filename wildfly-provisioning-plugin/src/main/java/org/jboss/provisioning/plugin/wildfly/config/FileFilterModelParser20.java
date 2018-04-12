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
package org.jboss.provisioning.plugin.wildfly.config;



import javax.xml.namespace.QName;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

import org.jboss.galleon.util.ParsingUtils;
import org.jboss.galleon.xml.XmlNameProvider;

import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 *
 * @author Eduardo Martins
 * @author Alexey Loubyansky
 */
public class FileFilterModelParser20 {

    public static final String ELEMENT_LOCAL_NAME = "filter";

    enum Attribute implements XmlNameProvider {

        // default unknown attribute
        UNKNOWN(null),
        PATTERN("pattern"),
        INCLUDE("include");

        private static final Map<String, Attribute> attributes;

        static {
            Map<String, Attribute> attributesMap = new HashMap<>();
            attributesMap.put(PATTERN.getLocalName(), PATTERN);
            attributesMap.put(INCLUDE.getLocalName(), INCLUDE);
            attributes = attributesMap;
        }

        static Attribute of(QName qName) {
            final Attribute attribute = attributes.get(qName.getLocalPart());
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
        @Override
        public String getLocalName() {
            return name;
        }

        @Override
        public String getNamespace() {
            return null;
        }
    }

    public FileFilterModelParser20() {
    }

    public void parseFilter(XMLStreamReader reader, FileFilter.Builder builder) throws XMLStreamException {
        final Set<Attribute> required = EnumSet.of(Attribute.PATTERN, Attribute.INCLUDE);
        final int count = reader.getAttributeCount();
        for (int i = 0; i < count; i++) {
            final Attribute attribute = Attribute.of(reader.getAttributeName(i));
            required.remove(attribute);
            switch (attribute) {
                case PATTERN:
                    builder.setPatternString(reader.getAttributeValue(i));
                    break;
                case INCLUDE:
                    if(Boolean.parseBoolean(reader.getAttributeValue(i))) {
                        builder.setInclude();
                    }
                    break;
                default:
                    throw ParsingUtils.unexpectedContent(reader);
            }
        }
        if (!required.isEmpty()) {
            throw ParsingUtils.missingAttributes(reader.getLocation(), required);
        }
        ParsingUtils.parseNoContent(reader);
    }
}

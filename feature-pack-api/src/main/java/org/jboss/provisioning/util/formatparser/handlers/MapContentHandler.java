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

package org.jboss.provisioning.util.formatparser.handlers;

import java.util.Collections;
import java.util.Map;

import org.jboss.provisioning.util.PmCollections;
import org.jboss.provisioning.util.formatparser.FormatContentHandler;
import org.jboss.provisioning.util.formatparser.FormatErrors;
import org.jboss.provisioning.util.formatparser.FormatParsingException;
import org.jboss.provisioning.util.formatparser.ParsingFormat;
import org.jboss.provisioning.util.formatparser.formats.KeyValueParsingFormat;
import org.jboss.provisioning.util.formatparser.formats.MapParsingFormat;

/**
 *
 * @author Alexey Loubyansky
 */
public class MapContentHandler extends FormatContentHandler {

    protected Map<Object, Object> map = Collections.emptyMap();

    public MapContentHandler(ParsingFormat format, int strIndex) {
        super(format, strIndex);
    }

    /* (non-Javadoc)
     * @see org.jboss.provisioning.spec.type.ParsingCallbackHandler#addChild(org.jboss.provisioning.spec.type.ParsingCallbackHandler)
     */
    @Override
    public void addChild(FormatContentHandler childHandler) throws FormatParsingException {
        if(!childHandler.getFormat().getName().equals(KeyValueParsingFormat.NAME)) {
            throw new FormatParsingException(FormatErrors.unexpectedChildFormat(format, childHandler.getFormat()));
        }
        final KeyValueContentHandler entry = (KeyValueContentHandler) childHandler;
        final MapParsingFormat objectFormat = (MapParsingFormat)format;
        if(objectFormat.isAcceptsKey(entry.key)) {
            map = PmCollections.putLinked(map, entry.key, entry.value);
        } else {
            throw new FormatParsingException(FormatErrors.unexpectedCompositeFormatElement(format, entry.key));
        }
    }

    /* (non-Javadoc)
     * @see org.jboss.provisioning.spec.type.ParsingCallbackHandler#getParsedValue()
     */
    @Override
    public Object getContent() throws FormatParsingException {
        return map;
    }
}

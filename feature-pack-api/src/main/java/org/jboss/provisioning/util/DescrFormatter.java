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
package org.jboss.provisioning.util;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringWriter;

/**
 *
 * @author Alexey Loubyansky
 *
 * @deprecated this is only used in the {@link org.jboss.provisioning.spec.FeaturePackSpec} and the method there does
 * not seemed to be used. If we need messages written we should use the {@link org.jboss.provisioning.MessageWriter}
 */
@Deprecated
public class DescrFormatter {

    private final StringWriter writer = new StringWriter();
    private BufferedWriter buf = new BufferedWriter(writer);
    private final int offsetStep = 2;
    private int offset;
    private boolean doOffset;

    public DescrFormatter increaseOffset() {
        offset += offsetStep;
        return this;
    }

    public DescrFormatter decreaseOffset() {
        if(offset >= offsetStep) {
            offset -= offsetStep;
        }
        return this;
    }

    public DescrFormatter print(String str) throws IOException {
        if(doOffset) {
            offset();
            doOffset = false;
        }
        buf.write(str);
        return this;
    }

    public DescrFormatter newLine() throws IOException {
        buf.newLine();
        doOffset = true;
        return this;
    }

    public DescrFormatter println(String str) throws IOException {
        print(str);
        newLine();
        return this;
    }

    private void offset() throws IOException {
        for(int i = 0; i < offset; ++i) {
            buf.append(' ');
        }
    }

    public String toString() {
        try {
            buf.flush();
            buf.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
        buf = null;
        return writer.toString();
    }
}

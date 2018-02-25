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

package org.jboss.provisioning.plugin.wildfly.sandbox;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.jboss.provisioning.ProvisioningException;

/**
 *
 * @author Alexey Loubyansky
 */
public class EmbeddedScriptWriter implements AutoCloseable {

    protected final BufferedWriter writer;
    private boolean inEmbedded;
    private boolean inComposite;
    private boolean inOp;
    private Boolean inOpAddr;

    public EmbeddedScriptWriter(Path file) throws ProvisioningException {
        try {
            Files.createDirectories(file.getParent());
            writer = Files.newBufferedWriter(file);
        } catch (IOException e) {
            throw new ProvisioningException("Failed to initialize writer for " + file, e);
        }
    }

    public EmbeddedScriptWriter(BufferedWriter writer) throws ProvisioningException {
        this.writer = writer;
    }

    @Override
    public void close() throws ProvisioningException {
        try {
            if (inOp) {
                throw new ProvisioningException("Operation is missing end");
            }
            if (inComposite) {
                throw new ProvisioningException("Composite is missing end");
            }
            if(inEmbedded) {
                throw new ProvisioningException("Embedded is missing stop");
            }
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException e) {
                    throw new ProvisioningException("Failed to close writer", e);
                }
            }
        }
    }

    public void embedServer(String... args) throws ProvisioningException {
        if(inEmbedded) {
            throw new ProvisioningException("Already in embedded");
        }
        if(inOp || inComposite) {
            throw new ProvisioningException("Cannot embed a server while inside an operation");
        }
        writeLine(EmbeddedScriptConstants.SCRIPT);
        writeLine(EmbeddedScriptConstants.SERVER);
        for (String arg : args) {
            writeLine(arg);
        }
        writeLine(EmbeddedScriptConstants.END);
        inEmbedded = true;
    }

    public void embedHc(String... args) throws ProvisioningException {
        if(inEmbedded) {
            throw new ProvisioningException("Already in embedded");
        }
        if(inOp || inComposite) {
            throw new ProvisioningException("Cannot embed a server while inside an operation");
        }
        writeLine(EmbeddedScriptConstants.SCRIPT);
        writeLine(EmbeddedScriptConstants.HC);
        for (String arg : args) {
            writeLine(arg);
        }
        writeLine(EmbeddedScriptConstants.END);
        inEmbedded = true;
    }

    public void stopEmbedded() throws ProvisioningException {
        if(!inEmbedded) {
            throw new ProvisioningException("Not in embedded");
        }
        inEmbedded = false;
        writeLine(EmbeddedScriptConstants.END);
    }

    public void startOp(String name) throws ProvisioningException {
        if(!inEmbedded) {
            throw new ProvisioningException("Not in embedded");
        }
        if(inOp) {
            throw new ProvisioningException("Cannot nest operations");
        }
        inOp = true;
        writeLine(EmbeddedScriptConstants.OP);
        writeLine(name);
    }

    public void endOp() throws ProvisioningException {
        if(!inOp) {
            throw new ProvisioningException("Not in operation");
        }
        if(inOpAddr != null && inOpAddr) {
            throw new ProvisioningException("Address is missing end");
        }
        writeLine(EmbeddedScriptConstants.END);
        inOpAddr = null;
        inOp = false;
    }

    public void addOpAddress(String... parts) throws ProvisioningException {
        if(!inOp) {
            throw new ProvisioningException("Not in operation");
        }
        if(inOpAddr != null) {
            if(!inOpAddr) {
                throw new ProvisioningException("Operation already includes address");
            }
        } else {
            inOpAddr = true;
            writeLine(EmbeddedScriptConstants.ADDR);
        }
        if(parts.length % 2 != 0) {
            throw new ProvisioningException("There has to be an even number of parts: " + parts.length);
        }
        int i = 0;
        while(i < parts.length) {
            writeLine(parts[i++]);
            writeLine(parts[i++]);
        }
    }

    public void endOpAddress() throws ProvisioningException {
        if(inOpAddr == null || !inOpAddr) {
            throw new ProvisioningException("Not in operation address");
        }
        writeLine(EmbeddedScriptConstants.END);
        inOpAddr = false;
    }

    public void setOpAddress(String... parts) throws ProvisioningException {
        if(inOpAddr != null) {
            throw new ProvisioningException("Operation already includes address");
        }
        if(parts.length % 2 != 0) {
            throw new ProvisioningException("There has to be an even number of parts: " + parts.length);
        }
        writeLine(EmbeddedScriptConstants.ADDR);
        int i = 0;
        while(i < parts.length) {
            writeLine(parts[i++]);
            writeLine(parts[i++]);
        }
        writeLine(EmbeddedScriptConstants.END);
        inOpAddr = false;
    }

    public void addOpParam(String name, String value) throws ProvisioningException {
        if(!inOp) {
            throw new ProvisioningException("Not in operation");
        }
        if(inOpAddr != null && inOpAddr) {
            throw new ProvisioningException("Operation address is missing end");
        }
        writeLine(name);
        writeLine(value);
    }

    public void startComposite() throws ProvisioningException {
        if(!inEmbedded) {
            throw new ProvisioningException("Not in embedded");
        }
        if(inOp || inComposite) {
            throw new ProvisioningException("Cannot nest composite");
        }
        writeLine(EmbeddedScriptConstants.COMPOSITE);
        inComposite = true;
    }

    public void endComposite() throws ProvisioningException {
        if(!inComposite) {
            throw new ProvisioningException("Not in composite");
        }
        if(inOp) {
            throw new ProvisioningException("Operation is missing end");
        }
        writeLine(EmbeddedScriptConstants.END);
        inComposite = false;
    }

    private void writeLine(String line) throws ProvisioningException {
        try {
            writer.write(line);
            writer.newLine();
        } catch (IOException e) {
            throw new ProvisioningException("Failed to write line '" + line + "'", e);
        }
    }
}

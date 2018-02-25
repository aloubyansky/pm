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

import java.util.ArrayList;
import java.util.List;

import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.parsing.StateParser;
import org.jboss.as.cli.parsing.arguments.ArgumentValueCallbackHandler;
import org.jboss.as.cli.parsing.arguments.ArgumentValueInitialState;
import org.jboss.dmr.ModelNode;


/**
 *
 * @author Alexey Loubyansky
 */
public class EmbeddedScriptHandler {

    private boolean inScript;
    private Boolean stopServer;

    private boolean inHc;
    private boolean inServer;

    private boolean inComposite;

    private boolean inOp;
    private boolean inOpAddr;
    private String opName;
    private ModelNode op;

    private ModelNode composite;

    private String prevLine;
    private List<String> args = new ArrayList<>();
    private int lineNumber;

    private EmbeddedScriptRunner agent;

    EmbeddedScriptHandler(EmbeddedScriptRunner agent) {
        this.agent = agent;
    }

    private void embedServer(String[] args) throws Exception {
        agent.startServer(args);
    }

    private void stopEmbeddedServer() throws Exception {
        agent.stopEmbedded();
    }

    private void embedHc(String[] args) throws Exception {
        agent.startHc(args);
    }

    private void stopEmbeddedHc() throws Exception {
        agent.stopEmbedded();
    }

    private void execute(ModelNode op) throws Exception {
        agent.execute(op);
    }

    private void setOpName(String name) throws Exception {
        op.get("operation").set(name);
    }

    private void setOpParam(String name, String value) throws Exception {
        ModelNode toSet = null;
        try {
            toSet = ModelNode.fromString(value);
        } catch (Exception e) {
            final ArgumentValueCallbackHandler handler = new ArgumentValueCallbackHandler();
            try {
                StateParser.parse(value, handler, ArgumentValueInitialState.INSTANCE);
            } catch (CommandFormatException e1) {
                throw new Exception("Failed to parse parameter '" + value + "'", e1);
            }
            toSet = handler.getResult();
        }
        op.get(name).set(toSet);
    }

    private void addOpAddr(String name, String value) throws Exception {
        op.get("address").add(name, value);
    }

    private void startScript() throws Exception {
        inScript = true;
    }

    private void scriptContent(String line) throws Exception {
        if(line.equals(EmbeddedScriptConstants.OP)) {
            startOp();
        } else if(line.equals(EmbeddedScriptConstants.COMPOSITE)) {
            startComposite();
        } else if(line.equals(EmbeddedScriptConstants.HC)) {
            startHc();
        } else if(line.equals(EmbeddedScriptConstants.SERVER)) {
            startServer();
        } else {
            unexpectedLine(line);
        }
    }

    private void endScript() throws Exception {
        if(stopServer == null) {
            throw new Exception("Embedded process type was not set");
        } else if(stopServer) {
            stopEmbeddedServer();
        } else {
            stopEmbeddedHc();
        }
        inScript = false;
        stopServer = null;
    }

    private void startHc() throws Exception {
        inHc = true;
        stopServer = false;
    }

    private void hcContent(String line) throws Exception {
        args.add(line);
    }

    private void endHc() throws Exception {
        embedHc(args.toArray(new String[args.size()]));
        inHc = false;
        args.clear();
    }

    private void startServer() throws Exception {
        inServer = true;
        stopServer = true;
    }

    private void serverContent(String line) throws Exception {
        args.add(line);
    }

    private void endServer() throws Exception {
        embedServer(args.toArray(new String[args.size()]));
        inServer = false;
        args.clear();
    }

    private void startOpAddr() throws Exception {
        inOpAddr = true;
    }

    private void opAddrContent(String line) throws Exception {
        if(prevLine == null) {
            prevLine = line;
        } else {
            addOpAddr(prevLine, line);
            prevLine = null;
        }
    }

    private void endOpAddr() throws Exception {
        inOpAddr = false;
    }

    private void startOp() throws Exception {
        inOp = true;
        op = new ModelNode();
        op.get("address").setEmptyList();
    }

    private void opContent(String line) throws Exception {
        if(opName == null) {
            opName = line;
            setOpName(opName);
            return;
        }
        if(line.equals(EmbeddedScriptConstants.ADDR)) {
            startOpAddr();
        } else if(prevLine == null){
            prevLine = line;
        } else {
            setOpParam(prevLine, line);
            prevLine = null;
        }
    }

    private void endOp() throws Exception {
        if(composite != null) {
            composite.get("steps").add(op);
        } else {
            execute(op);
        }
        inOp = false;
        op = null;
        opName = null;
    }

    private void startComposite() throws Exception {
        inComposite = true;
        composite = new ModelNode();
        composite.get("address").setEmptyList();
        composite.get("operation").set("composite");
        composite.get("steps").setEmptyList();
    }

    private void compositeContent(String line) throws Exception {
        if(line.equals(EmbeddedScriptConstants.OP)) {
            startOp();
        } else {
            unexpectedLine(line);
        }
    }

    private void endComposite() throws Exception {
        execute(composite);
        inComposite = false;
        composite = null;
    }

    public void handle(String line) throws Exception {
        ++lineNumber;
        if(line.equals(EmbeddedScriptConstants.END)) {
            if(inOpAddr) {
                endOpAddr();
            } else if(inOp) {
                endOp();
            } else if(inComposite) {
                endComposite();
            } else if(inServer) {
                endServer();
            } else if(inHc) {
                endHc();
            } else if(inScript) {
                endScript();
            }
            return;
        }
        if(inOpAddr) {
            opAddrContent(line);
        } else if(inOp) {
            opContent(line);
        } else if(inComposite) {
            compositeContent(line);
        } else if(inServer) {
            serverContent(line);
        } else if(inHc) {
            hcContent(line);
        } else if(inScript) {
            scriptContent(line);
        } else if(line.equals(EmbeddedScriptConstants.SCRIPT)) {
            startScript();
        } else {
            unexpectedLine(line);
        }
    }

    private void unexpectedLine(String line) throws Exception {
        final StringBuilder buf = new StringBuilder();
        buf.append("Unexpected ");
        if(this.inOpAddr) {
            buf.append("child of ").append(EmbeddedScriptConstants.ADDR);
        } else if(inOp) {
            buf.append("child of ").append(EmbeddedScriptConstants.OP);
        } else if(inComposite) {
            buf.append("child of ").append(EmbeddedScriptConstants.COMPOSITE);
        } else if(inServer) {
            buf.append("child of ").append(EmbeddedScriptConstants.SERVER);
        } else if(inHc) {
            buf.append("child of ").append(EmbeddedScriptConstants.HC);
        } else if(inScript) {
            buf.append("child of ").append(EmbeddedScriptConstants.SCRIPT);
        } else {
            buf.append("root");
        }
        buf.append(": ").append(line);
        error(buf.toString());
    }

    private void error(String msg) throws Exception {
        throw new Exception("Line " + lineNumber + ": " + msg);
    }
}

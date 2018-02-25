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

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.client.helpers.ClientConstants;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.provisioning.util.PmCollections;
import org.wildfly.core.embedded.EmbeddedManagedProcess;
import org.wildfly.core.embedded.EmbeddedProcessFactory;
import org.wildfly.core.embedded.EmbeddedProcessStartException;

/**
 *
 * @author Alexey Loubyansky
 */
public class EmbeddedScriptRunner {

    public static void run(Path jbossHome, Path script) throws Exception {
        final Map<?, ?> originalProps = new HashMap<>(System.getProperties());
        final ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        try {
            doRun(jbossHome, script);
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
            Set<String> toClear = Collections.emptySet();
            final Map<?, ?> props = System.getProperties();
            for(Map.Entry<?, ?> prop : props.entrySet()) {
                final Object value = originalProps.get(prop.getKey());
                if(value != null) {
                    System.setProperty(prop.getKey().toString(), value.toString());
                } else {
                    toClear = PmCollections.add(toClear, prop.getKey().toString());
                }
            }
            if(!toClear.isEmpty()) {
                for(String prop : toClear) {
                    System.clearProperty(prop);
                }
            }
        }
    }

    private static void addToCp(ClassLoader cl, List<URL> cp) throws Exception {
        if(!(cl instanceof URLClassLoader)) {
            throw new Exception("Expected a URLClassLoader but got " + cl.getClass().getName());
        }
        final URL[] cclUrls = ((URLClassLoader)cl).getURLs();
        for(URL url : cclUrls) {
            cp.add(url);
        }
    }

    private static void doRun(Path jbossHome, Path script) throws Exception {

        final List<URL> cp = new ArrayList<>();

        try {
            addJars(jbossHome, cp);
        } catch (IOException e) {
            throw new Exception("Failed to add JARs from " + jbossHome + " to the classpath list");
        }

        addToCp(EmbeddedScriptRunner.class.getClassLoader(), cp);

        final URL[] newClUrls = new URL[cp.size()];
        int i = 0;
        while(i < cp.size()) {
            newClUrls[i] = cp.get(i++);
        }

        final URLClassLoader newCl = new URLClassLoader(newClUrls, null);
        Thread.currentThread().setContextClassLoader(newCl);

        String className = EmbeddedScriptRunner.class.getName();
        Class<?> cliTest;
        try {
            cliTest = newCl.loadClass(className);
        } catch (ClassNotFoundException e) {
            throw new Exception("Failed to load class " + className, e);
        }
        Method test;
        try {
            test = cliTest.getMethod("run", Map.class);
        } catch (Exception e) {
            throw new Exception("Failed to locate method 'run' in " + className, e);
        }

        try {
            final Map<String, String> params = new HashMap<>();
            params.put("jboss.home", jbossHome.toString());
            params.put("script", script.toString());
            test.invoke(cliTest.newInstance(), params);
        } catch (Exception e) {
            throw new Exception("Failed to execute the script", e);
        }
    }

    private static List<URL> addJars(Path dir, List<URL> urls) throws IOException {
        Files.walkFileTree(dir, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                        return FileVisitResult.CONTINUE;
                    }
                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                        throws IOException {
                        if(!file.getFileName().toString().endsWith(".jar")) {
                            return FileVisitResult.CONTINUE;
                        }
                        urls.add(file.toUri().toURL());
                        return FileVisitResult.CONTINUE;
                    }
                });
        return urls;
    }

    private Long bootTimeout = null;

    private String jbossHome;
    private EmbeddedManagedProcess embeddedProcess;
    private ModelControllerClient mcc;

    private boolean hc;
    private String[] args;

    public void run(Map<String, String> params) throws Exception {

        jbossHome = getParam(params, "jboss.home");
        final Path script = getPathParam(params, "script");

        final EmbeddedScriptHandler parser = new EmbeddedScriptHandler(this);
        try(BufferedReader reader = Files.newBufferedReader(script)) {
            String line = reader.readLine();
            while(line != null) {
                parser.handle(line);
                line = reader.readLine();
            }
        } catch (IOException e) {
            throw new Exception("Failed to read " + script, e);
        } catch(Throwable e) {
            Throwable t = e;
            while(t != null) {
                System.out.println(t.getClass().getSimpleName() + ": " + t.getMessage());
                t = t.getCause();
            }
            throw new Exception(e);
        } finally {
            stopEmbedded();
        }
    }

    void startServer(String[] args) throws Exception {
        //System.out.println("embed server " + Arrays.asList(args));
        this.args = args;
        this.hc = false;
        embeddedProcess = EmbeddedProcessFactory.createStandaloneServer(jbossHome, null, null, args);
        try {
            embeddedProcess.start();
        } catch (EmbeddedProcessStartException e) {
            throw new Exception("Failed to start embedded server", e);
        }
        mcc = embeddedProcess.getModelControllerClient();
        waitForServer();
    }

    void startHc(String[] args) throws Exception {
        //System.out.println("embed hc " + Arrays.asList(args));
        this.args = args;
        this.hc = true;
        embeddedProcess = EmbeddedProcessFactory.createHostController(jbossHome, null, null, args);
        try {
            embeddedProcess.start();
        } catch (EmbeddedProcessStartException e) {
            throw new Exception("Failed to start embedded hc", e);
        }
        mcc = embeddedProcess.getModelControllerClient();
        //waitForHc();
    }

    void stopEmbedded() throws Exception {
        //System.out.println("stop embedded");
        if(mcc != null) {
            try {
                mcc.close();
            } catch (IOException e) {
                throw new Exception("Failed to close ModelControllerClient", e);
            }
            mcc = null;
        }
        if(embeddedProcess != null) {
            embeddedProcess.stop();
            embeddedProcess = null;
        }
    }

    void execute(ModelNode op) throws Exception {
        try {
            final ModelNode response = mcc.execute(op);
            if(Operations.isSuccessfulOutcome(response)) {
                return;
            }

            final StringBuilder buf = new StringBuilder();
            buf.append("Failed to");
            if(hc) {
                String domainConfig = null;
                boolean emptyDomain = false;
                String hostConfig = null;
                boolean emptyHost = false;
                int i = 0;
                while(i < args.length) {
                    final String arg = args[i++];
                    if(arg.startsWith("--domain-config")) {
                        if(arg.length() == "--domain-config".length()) {
                            domainConfig = args[i++];
                        } else {
                            domainConfig = arg.substring("--domain-config=".length());
                        }
                    } else if(arg.startsWith("--host-config")) {
                        if(arg.length() == "--host-config".length()) {
                            hostConfig = args[i++];
                        } else {
                            hostConfig = arg.substring("--host-config=".length());
                        }
                    } else if(arg.equals("--empty-host-config")) {
                        emptyHost = true;
                    } else if(arg.equals("--empty-domain-config")) {
                        emptyDomain = true;
                    }
                }
                if(emptyDomain) {
                    buf.append(" generate ").append(domainConfig);
                    if(emptyHost) {
                        buf.append(" and ").append(hostConfig);
                    }
                } else if(emptyHost) {
                    buf.append(" generate ").append(hostConfig);
                } else {
                    buf.append(" execute script");
                }
            } else {
                String serverConfig = null;
                boolean emptyConfig = false;
                int i = 0;
                while(i < args.length) {
                    final String arg = args[i++];
                    if(arg.equals("--server-config")) {
                        if(arg.length() == "--server-config".length()) {
                            serverConfig = args[i++];
                        } else {
                            serverConfig = arg.substring("--server-config=".length());
                        }
                    } else if(arg.equals("--internal-empty-config")) {
                        emptyConfig = true;
                    }
                }
                if(emptyConfig) {
                    buf.append(" generate ").append(serverConfig);
                } else {
                    buf.append(" execute script");
                }
            }
            buf.append(" on ").append(op).append(": ").append(Operations.getFailureDescription(response));
            throw new Exception(buf.toString());
        } catch (IOException e) {
            throw new Exception("Failed to execute " + op);
        }
    }

    private void waitForServer() throws Exception {
        if (bootTimeout == null || bootTimeout > 0) {
            // Poll for server state. Alternative would be to get ControlledProcessStateService
            // and do reflection stuff to read the state and register for change notifications
            long expired = bootTimeout == null ? Long.MAX_VALUE : System.nanoTime() + bootTimeout;
            String status = "starting";
            final ModelNode getStateOp = new ModelNode();
            getStateOp.get(ClientConstants.OP).set(ClientConstants.READ_ATTRIBUTE_OPERATION);
            getStateOp.get(ClientConstants.NAME).set("server-state");
            do {
                try {
                    final ModelNode response = mcc.execute(getStateOp);
                    if (Operations.isSuccessfulOutcome(response)) {
                        status = response.get(ClientConstants.RESULT).asString();
                    }
                } catch (Exception e) {
                    // ignore and try again
                }

                if ("starting".equals(status)) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Interrupted while waiting for embedded server to start");
                    }
                } else {
                    break;
                }
            } while (System.nanoTime() < expired);

            if ("starting".equals(status)) {
                assert bootTimeout != null; // we'll assume the loop didn't run for decades
                // Stop server and restore environment
                stopEmbedded();
                throw new Exception("Embedded server did not exit 'starting' status within " +
                        TimeUnit.NANOSECONDS.toSeconds(bootTimeout) + " seconds");
            }

        }
    }

    private void waitForHc() throws Exception {
        if (bootTimeout == null || bootTimeout > 0) {
            long expired = bootTimeout == null ? Long.MAX_VALUE : System.nanoTime() + bootTimeout;

            String status = "starting";

            // read out the host controller name
            final ModelNode getNameOp = new ModelNode();
            getNameOp.get(ClientConstants.OP).set(ClientConstants.READ_ATTRIBUTE_OPERATION);
            getNameOp.get(ClientConstants.NAME).set("local-host-name");

            final ModelNode getStateOp = new ModelNode();
            getStateOp.get(ClientConstants.OP).set(ClientConstants.READ_ATTRIBUTE_OPERATION);
            ModelNode address = getStateOp.get(ClientConstants.ADDRESS);
            getStateOp.get(ClientConstants.NAME).set(ClientConstants.HOST_STATE);
            do {
                try {
                    final ModelNode nameResponse = mcc.execute(getNameOp);
                    if (Operations.isSuccessfulOutcome(nameResponse)) {
                        // read out the connected HC name
                        final String localName = nameResponse.get(ClientConstants.RESULT).asString();
                        address.set(ClientConstants.HOST, localName);
                        final ModelNode stateResponse = mcc.execute(getStateOp);
                        if (Operations.isSuccessfulOutcome(stateResponse)) {
                            status = stateResponse.get(ClientConstants.RESULT).asString();
                        }
                    }
                } catch (Exception e) {
                    // ignore and try again
                }

                if ("starting".equals(status)) {
                    try {
                        Thread.sleep(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new Exception("Interrupted while waiting for embedded server to start");
                    }
                } else {
                    break;
                }
            } while (System.nanoTime() < expired);

            if ("starting".equals(status)) {
                assert bootTimeout != null; // we'll assume the loop didn't run for decades
                // Stop server and restore environment
                stopEmbedded();
                throw new Exception("Embedded host controller did not exit 'starting' status within " +
                        TimeUnit.NANOSECONDS.toSeconds(bootTimeout) + " seconds");
            }
        }
    }

    private static Path getPathParam(Map<String, String> params, final String param) throws Exception {
        final Path p = Paths.get(getParam(params, param));
        if(Files.exists(p)) {
            return p;
        }
        throw new Exception("Path does not exist " + p);
    }

    private static String getParam(Map<String, String> params, final String param) throws Exception {
        final String value = params.get(param);
        if(value == null) {
            throw new Exception("Missing parameter " + param);
        }
        return value;
    }
}

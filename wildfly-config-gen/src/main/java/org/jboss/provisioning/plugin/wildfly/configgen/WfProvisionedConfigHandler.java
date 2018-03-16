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

package org.jboss.provisioning.plugin.wildfly.configgen;

import static org.jboss.provisioning.Constants.PM_UNDEFINED;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.as.cli.CommandFormatException;
import org.jboss.as.cli.parsing.StateParser;
import org.jboss.as.cli.parsing.arguments.ArgumentValueCallbackHandler;
import org.jboss.as.cli.parsing.arguments.ArgumentValueInitialState;
import org.jboss.as.controller.client.helpers.Operations;
import org.jboss.dmr.ModelNode;
import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.Constants;
import org.jboss.provisioning.MessageWriter;
import org.jboss.provisioning.ProvisioningDescriptionException;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.plugin.ProvisionedConfigHandler;
import org.jboss.provisioning.plugin.wildfly.WfConstants;
import org.jboss.provisioning.runtime.ProvisioningRuntime;
import org.jboss.provisioning.runtime.ResolvedFeatureSpec;
import org.jboss.provisioning.spec.FeatureAnnotation;
import org.jboss.provisioning.state.ProvisionedConfig;
import org.jboss.provisioning.state.ProvisionedFeature;

/**
 *
 * @author Alexey Loubyansky
 */
public class WfProvisionedConfigHandler implements ProvisionedConfigHandler {

    private interface NameFilter {
        boolean accepts(String name, int position);
    }

    private class ManagedOp {
        String name;
        List<String> addrParams = Collections.emptyList();
        List<String> opParams = Collections.emptyList();
        int op;

        @Override
        public String toString() {
            return "ManagedOp{name=" + name + ", addrParams=" + addrParams + ", opParams=" + opParams + ", op=" + op + '}';
        }

        private void writeOp(ProvisionedFeature feature) throws ProvisioningException {
            final ModelNode op = writeOpAddress(feature);
            if (!opParams.isEmpty()) {
                int i = 0;
                while (i < opParams.size()) {
                    final String featureParam = opParams.get(i++);
                    String value = feature.getConfigParam(featureParam);
                    if (value == null) {
                        ++i;
                        continue;
                    }
                    setOpParam(op, opParams.get(i++), value.trim().isEmpty() ? '\"' + value + '\"' : value);
                }
            }
            handleOp(op);
        }

        private void writeList(ProvisionedFeature feature) throws ProvisioningException {
            final ModelNode op = writeOpAddress(feature);
            String value = feature.getConfigParam(opParams.get(0));
            if (value == null) {
                throw new ProvisioningDescriptionException(opParams.get(0) + " parameter is null: " + feature);
            }
            op.get("name").set(opParams.get(1));
            setOpParam(op, "value", value);
            handleOp(op);
        }

        private ModelNode writeOpAddress(ProvisionedFeature feature) throws ProvisioningException {
            final ModelNode op = Operations.createOperation(name);
            if(addrParams.isEmpty()) {
                return op;
            }
            final ModelNode addr = Operations.getOperationAddress(op);
            int i = 0;
            while (i < addrParams.size()) {
                final String featureParam = addrParams.get(i++);
                String value = feature.getConfigParam(featureParam);
                if(value == null) {
                    throw new ProvisioningException("Address parameter " + featureParam + " of " + feature.getId() + " is null");
                }
                if(PM_UNDEFINED.equals(value)) {
                    ++i;
                    continue;
                }
                addr.add(addrParams.get(i++), value);
            }
            return op;
        }

        void toCommandLine(ProvisionedFeature feature) throws ProvisioningException {
            switch (op) {
                case OP: {
                    writeOp(feature);
                    break;
                }
                case LIST_ADD: {
                    writeList(feature);
                    break;
                }
                case WRITE_ATTR: {
                    writeAttributes(feature);
                    break;
                }
                default:
                    throw new ProvisioningException("Unexpected op " + op);
            }
        }

        private void writeAttributes(ProvisionedFeature feature) throws ProvisioningDescriptionException, ProvisioningException {
            int i = 0;
            while (i < opParams.size()) {
                Object value = feature.getResolvedParam(opParams.get(i++));
                if (value == null) {
                    ++i;
                    continue;
                }
                final ModelNode op = writeOpAddress(feature);
                op.get("name").set(opParams.get(i++));
                setOpParam(op, "value", value.toString());
                handleOp(op);
            }
        }
    }

    private static final String DOMAIN = "domain";
    private static final String HOST = "host";
    private static final String PROFILE = "profile";
    private static final String STANDALONE = "standalone";

    private static final int OP = 0;
    private static final int WRITE_ATTR = 1;
    private static final int LIST_ADD = 2;

    private static NameFilter STANDALONE_PARAM_FILTER;
    private static NameFilter getStandaloneParamFilter() {
        if(STANDALONE_PARAM_FILTER == null) {
            STANDALONE_PARAM_FILTER = new NameFilter() {
                @Override
                public boolean accepts(String name, int position) {
                    return position > 0 || !(PROFILE.equals(name) || HOST.equals(name));
                }
            };
        }
        return STANDALONE_PARAM_FILTER;
    }

    private static NameFilter DOMAIN_PARAM_FILTER;
    private static NameFilter getDomainParamFilter() {
        if(DOMAIN_PARAM_FILTER == null) {
            DOMAIN_PARAM_FILTER = new NameFilter() {
                @Override
                public boolean accepts(String name, int position) {
                    return position > 0 || !HOST.equals(name);
                }
            };
        }
        return DOMAIN_PARAM_FILTER;
    }

    private static NameFilter HOST_PARAM_FILTER;
    private static NameFilter getHostParamFilter() {
        if(HOST_PARAM_FILTER == null) {
            HOST_PARAM_FILTER = new NameFilter() {
                @Override
                public boolean accepts(String name, int position) {
                    return position > 0 || !PROFILE.equals(name);
                }
            };
        }
        return HOST_PARAM_FILTER;
    }

    private List<ManagedOp> createWriteAttributeManagedOperation(ResolvedFeatureSpec spec, FeatureAnnotation annotation) throws ProvisioningException {
        List<ManagedOp> operations = new ArrayList<>();

        String elemValue = annotation.getElement(WfConstants.ADDR_PARAMS);
        if (elemValue == null) {
            throw new ProvisioningException("Required element " + WfConstants.ADDR_PARAMS + " is missing for " + spec.getId());
        }
        List<String> addrParams  = null;
        try {
            addrParams = parseList(annotation.getElementAsList(WfConstants.ADDR_PARAMS), paramFilter, annotation.getElementAsList(WfConstants.ADDR_PARAMS_MAPPING));
        } catch (ProvisioningDescriptionException e) {
            throw new ProvisioningDescriptionException("Saw an empty parameter name in annotation " + WfConstants.ADDR_PARAMS + "="
                    + elemValue + " of " + spec.getId());
        }
        if (addrParams == null) {
            return Collections.emptyList();
        }

        elemValue = annotation.getElement(WfConstants.OP_PARAMS, Constants.PM_UNDEFINED);
        if (Constants.PM_UNDEFINED.equals(elemValue)) {
            if (spec.hasParams()) {
                final Set<String> allParams = spec.getParamNames();
                final int opParams = allParams.size() - addrParams.size() / 2;
                if (opParams == 0) {
                     throw new ProvisioningDescriptionException(WfConstants.OP_PARAMS + " element of "
                        + WfConstants.WRITE_ATTRIBUTE + " annotation of " + spec.getId()
                        + " accepts only one parameter: " + annotation);
                } else {
                    for (String paramName : allParams) {
                        boolean inAddr = false;
                        int j = 0;
                        while (!inAddr && j < (opParams * 2)) {
                            if (addrParams.get(j).equals(paramName)) {
                                inAddr = true;
                            }
                            j += 2;
                        }
                        if (!inAddr) {
                            if (paramFilter.accepts(paramName, j)) {
                                final ManagedOp mop = new ManagedOp();
                                mop.name = WfConstants.WRITE_ATTRIBUTE;
                                mop.op = WRITE_ATTR;
                                mop.addrParams = addrParams;
                                mop.opParams = new ArrayList<>(2);
                                mop.opParams.add(paramName);
                                mop.opParams.add(paramName);
                                operations.add(mop);
                            }
                        }
                    }
                }
            } else {
                throw new ProvisioningDescriptionException(WfConstants.OP_PARAMS + " element of "
                        + WfConstants.WRITE_ATTRIBUTE + " annotation of " + spec.getId()
                        + " accepts only one parameter: " + annotation);
            }
        } else {
            try {
                final List<String> params = parseList(annotation.getElementAsList(WfConstants.OP_PARAMS, PM_UNDEFINED), null, annotation.getElementAsList(WfConstants.OP_PARAMS_MAPPING));
                for (int i = 0; i < params.size(); i++) {
                    if (i % 2 == 0) {
                        final ManagedOp mop = new ManagedOp();
                        mop.name = WfConstants.WRITE_ATTRIBUTE;
                        mop.op = WRITE_ATTR;
                        mop.addrParams = addrParams;
                        mop.opParams = new ArrayList<>(2);
                        mop.opParams.add(params.get(i));
                        mop.opParams.add(params.get(i + 1));
                        operations.add(mop);
                    }
                }
            } catch (ProvisioningDescriptionException e) {
                throw new ProvisioningDescriptionException("Saw empty parameter name in note " + WfConstants.ADDR_PARAMS
                        + "=" + elemValue + " of " + spec.getId());
            }
        }
        return operations;
    }

    private List<ManagedOp> createAddListManagedOperation(ResolvedFeatureSpec spec, FeatureAnnotation annotation) throws ProvisioningException {
        return createManagedOperation(spec, annotation, WfConstants.LIST_ADD, LIST_ADD);
    }

    private List<ManagedOp> createManagedOperation(ResolvedFeatureSpec spec, FeatureAnnotation annotation, String name, int operation) throws ProvisioningException {
        final ManagedOp mop = new ManagedOp();
        mop.name = name;
        mop.op = operation;

        String elemValue = annotation.getElement(WfConstants.ADDR_PARAMS);
        if (elemValue == null) {
            throw new ProvisioningException("Required element " + WfConstants.ADDR_PARAMS + " is missing for " + spec.getId());
        }

        try {
            mop.addrParams = parseList(annotation.getElementAsList(WfConstants.ADDR_PARAMS), paramFilter, annotation.getElementAsList(WfConstants.ADDR_PARAMS_MAPPING));
        } catch (ProvisioningDescriptionException e) {
            throw new ProvisioningDescriptionException("Saw an empty parameter name in annotation " + WfConstants.ADDR_PARAMS + "="
                    + elemValue + " of " + spec.getId());
        }
        if (mop.addrParams == null) {
            return Collections.emptyList();
        }

        elemValue = annotation.getElement(WfConstants.OP_PARAMS, PM_UNDEFINED);
        if (PM_UNDEFINED.equals(elemValue)) {
            if (spec.hasParams()) {
                final Set<String> allParams = spec.getParamNames();
                final int opParams = allParams.size() - mop.addrParams.size() / 2;
                if (opParams == 0) {
                    mop.opParams = Collections.emptyList();
                } else {
                    mop.opParams = new ArrayList<>(opParams * 2);
                    for (String paramName : allParams) {
                        boolean inAddr = false;
                        int j = 0;
                        while (!inAddr && j < mop.addrParams.size()) {
                            if (mop.addrParams.get(j).equals(paramName)) {
                                inAddr = true;
                            }
                            j += 2;
                        }
                        if (!inAddr) {
                            if (paramFilter.accepts(paramName, j)) {
                                mop.opParams.add(paramName);
                                mop.opParams.add(paramName);
                            }
                        }
                    }
                }
            } else {
                mop.opParams = Collections.emptyList();
            }
        } else {
            try {
                mop.opParams = parseList(annotation.getElementAsList(WfConstants.OP_PARAMS, PM_UNDEFINED), null, annotation.getElementAsList(WfConstants.OP_PARAMS_MAPPING));
            } catch (ProvisioningDescriptionException e) {
                throw new ProvisioningDescriptionException("Saw empty parameter name in note " + WfConstants.ADDR_PARAMS
                        + "=" + elemValue + " of " + spec.getId());
            }
        }
        return Collections.singletonList(mop);
    }

    private final MessageWriter messageWriter;
    private final WfConfigGenerator configGen;

    private List<ManagedOp> ops = new ArrayList<>();
    private NameFilter paramFilter;

    private ModelNode composite;

    public WfProvisionedConfigHandler(ProvisioningRuntime runtime, WfConfigGenerator configGen) throws ProvisioningException {
        this.messageWriter = runtime.getMessageWriter();
        this.configGen = configGen;
    }

    @Override
    public void prepare(ProvisionedConfig config) throws ProvisioningException {
        if(STANDALONE.equals(config.getModel())) {
            configGen.startServer(getEmbeddedArgs(config));
            paramFilter = getStandaloneParamFilter();
        } else if(DOMAIN.equals(config.getModel())) {
            configGen.startHc(getEmbeddedArgs(config));
            configGen.execute(Operations.createAddOperation(Operations.createAddress("host", "tmp")));
            paramFilter = getDomainParamFilter();
        } else if (HOST.equals(config.getModel())) {
            configGen.startHc(getEmbeddedArgs(config));
            paramFilter = getHostParamFilter();
        } else {
            throw new ProvisioningException("Unsupported config model " + config.getModel());
        }
    }

    @Override
    public void nextFeaturePack(ArtifactCoords.Gav fpGav) throws ProvisioningException {
        messageWriter.verbose("  %s", fpGav);
    }

    @Override
    public void nextSpec(ResolvedFeatureSpec spec) throws ProvisioningException {
        ops.clear();
        messageWriter.verbose("    SPEC %s", spec.getName());
        if(!spec.hasAnnotations()) {
            return;
        }

        for (FeatureAnnotation annotation : spec.getAnnotations()) {
            if(annotation.getName().equals(WfConstants.JBOSS_OP)) {
                ops.addAll(nextAnnotation(spec, annotation));
            }
        }
    }

    private List<ManagedOp> nextAnnotation(final ResolvedFeatureSpec spec, final FeatureAnnotation annotation) throws ProvisioningException {
        final String name = annotation.getElement(WfConstants.NAME);
        switch (name) {
            case WfConstants.WRITE_ATTRIBUTE:
                return createWriteAttributeManagedOperation(spec, annotation);
            case WfConstants.LIST_ADD:
                return createAddListManagedOperation(spec, annotation);
            default:
                return createManagedOperation(spec, annotation, name, OP);
        }
    }

    @Override
    public void nextFeature(ProvisionedFeature feature) throws ProvisioningException {
        if (ops.isEmpty()) {
            messageWriter.verbose("      %s", feature.getResolvedParams());
            return;
        }
        for(ManagedOp op : ops) {
            op.toCommandLine(feature);
        }
    }

    @Override
    public void startBatch() throws ProvisioningException {
        messageWriter.verbose("      START BATCH");
        composite = Operations.createCompositeOperation();
    }

    @Override
    public void endBatch() throws ProvisioningException {
        messageWriter.verbose("      END BATCH");
        configGen.execute(composite);
        composite = null;
    }

    @Override
    public void done() throws ProvisioningException {
        configGen.stopEmbedded();
    }

    private String[] getEmbeddedArgs(ProvisionedConfig config) {
        final List<String> embeddedArgs = new ArrayList<>(config.getProperties().size());
        for(Map.Entry<String, String> prop : config.getProperties().entrySet()) {
            if(prop.getKey().startsWith("--")) {
                embeddedArgs.add(prop.getKey());
                if(!prop.getValue().isEmpty()) {
                    embeddedArgs.add(prop.getValue());
                }
            }
        }
        return embeddedArgs.toArray(new String[embeddedArgs.size()]);
    }

    private void handleOp(ModelNode op) throws ProvisioningException {
        if(composite != null) {
            composite.get("steps").add(op);
        } else {
            configGen.execute(op);
        }
    }

    private void setOpParam(ModelNode op, String name, String value) throws ProvisioningException {
        ModelNode toSet = null;
        try {
            toSet = ModelNode.fromString(value);
        } catch (Exception e) {
            final ArgumentValueCallbackHandler handler = new ArgumentValueCallbackHandler();
            try {
                StateParser.parse(value, handler, ArgumentValueInitialState.INSTANCE);
            } catch (CommandFormatException e1) {
                throw new ProvisioningException("Failed to parse parameter " + name + " '" + value + "'", e1);
            }
            toSet = handler.getResult();
        }
        op.get(name).set(toSet);
    }

    static List<String> parseList(List<String> params, NameFilter filter, List<String> mappings) throws ProvisioningDescriptionException {
        if (params == null || params.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> list = new ArrayList<>();
        if (params.size() != mappings.size() && mappings.size() > 0) {
            throw new ProvisioningDescriptionException("Mappings and params don't match");
        }
        for (int i = 0; i < params.size(); i++) {
            final String paramName = params.get(i);
            final String mappedName;
            if(mappings.isEmpty()) {
                mappedName = paramName;
            } else {
                mappedName = mappings.get(i);
            }
            if (filter == null || filter.accepts(paramName, i)) {
                list.add(paramName);
                list.add(mappedName);
            }
        }
        return list;
    }
}

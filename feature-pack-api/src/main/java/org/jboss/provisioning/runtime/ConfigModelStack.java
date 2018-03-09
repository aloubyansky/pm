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

package org.jboss.provisioning.runtime;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jboss.provisioning.Errors;
import org.jboss.provisioning.ProvisioningDescriptionException;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.config.ConfigId;
import org.jboss.provisioning.config.ConfigModel;
import org.jboss.provisioning.config.FeatureGroupSupport;
import org.jboss.provisioning.spec.CapabilitySpec;
import org.jboss.provisioning.spec.FeatureDependencySpec;
import org.jboss.provisioning.util.PmCollections;

/**
 * @author Alexey Loubyansky
 *
 */
class ConfigModelStack {

    private class ConfigScope {

        final ConfigModel config;
        private final boolean pushedFgScope;
        private List<ResolvedFeatureGroupConfig> groupStack = new ArrayList<>();

        ConfigScope(ConfigModel config) throws ProvisioningException {
            this.config = config;
            if(config != null) {
                pushedFgScope = push(config);
                if(pushedFgScope) {
                    newFgScope();
                }
            } else {
                pushedFgScope = false;
            }
        }

        void complete() throws ProvisioningException {
            if(pushedFgScope) {
                mergeFgScope();
            }
            for (int i = groupStack.size() - 1; i >= 0; --i) {
                rt.processIncludedFeatures(groupStack.get(i));
            }
        }

        boolean push(FeatureGroupSupport fg) throws ProvisioningException {
            final ResolvedFeatureGroupConfig resolvedFg = rt.resolveFeatureGroupConfig(fg);
            if (!fg.isConfig() && !ConfigModelStack.this.isRelevant(resolvedFg)) {
                return false;
            }
            groupStack.add(resolvedFg);
            return true;
        }

        boolean pop() throws ProvisioningException {
            if(groupStack.isEmpty()) {
                throw new IllegalStateException("Feature group stack is empty");
            }
            final ResolvedFeatureGroupConfig last = groupStack.remove(groupStack.size() - 1);
            final boolean processed = rt.processIncludedFeatures(last);
            return processed;
        }

        boolean isFilteredOut(ResolvedSpecId specId, final ResolvedFeatureId id) {
            boolean included = false;
            for(int i = groupStack.size() - 1; i >= 0; --i) {
                final ResolvedFeatureGroupConfig fgConfig = groupStack.get(i);
                if (fgConfig.inheritFeatures) {
                    if (id != null && fgConfig.excludedFeatures.contains(id)) {
                        return true;
                    }
                    if (fgConfig.excludedSpecs.contains(specId)) {
                        if (id != null && fgConfig.includedFeatures.containsKey(id)) {
                            included = true;
                            continue;
                        }
                        return true;
                    }
                } else {
                    if (id != null && fgConfig.includedFeatures.containsKey(id)) {
                        included = true;
                        continue;
                    }
                    if (!fgConfig.includedSpecs.contains(specId)) {
                        return true;
                    }
                    if (id != null && fgConfig.excludedFeatures.contains(id)) {
                        return true;
                    }
                    included = true;
                }
            }
            if(included) {
                return false;
            }
            return config == null ? false : !config.isInheritFeatures();
        }

        private boolean isRelevant(ResolvedFeatureGroupConfig resolvedFg) {
            if(resolvedFg.fg.getId() == null) {
                return true;
            }
            for(int i = groupStack.size() - 1; i >= 0; --i) {
                final ResolvedFeatureGroupConfig stacked = groupStack.get(i);
                if (stacked.fg.getId() == null
                        || stacked.gav == null || resolvedFg.gav == null
                        || !stacked.gav.equals(resolvedFg.gav)
                        || !stacked.fg.getId().equals(resolvedFg.fg.getId())) {
                    continue;
                }
                return !resolvedFg.isSubsetOf(stacked);
            }
            return true;
        }

    }

    final ConfigId id;
    final ProvisioningRuntimeBuilder rt;

    Map<String, String> props = Collections.emptyMap();
    Map<String, ConfigId> configDeps = Collections.emptyMap();

    private Map<ResolvedSpecId, SpecFeatures> specFeatures = new LinkedHashMap<>();
    private List<Map<ResolvedFeatureId, ResolvedFeature>> fgFeatures = new ArrayList<>();
    private int lastFg = -1;
    private Map<ResolvedFeatureId, ResolvedFeature> features;
    private int featureIncludeCount = 0;

    private List<ConfigScope> configs = new ArrayList<>();
    private ConfigScope lastConfig;

    private CapabilityResolver capResolver = new CapabilityResolver();
    private Map<String, CapabilityProviders> capProviders = Collections.emptyMap();

    // features in the order they should be processed by the provisioning handlers
    private List<ResolvedFeature> orderedFeatures = Collections.emptyList();
    private boolean orderReferencedSpec = true;
    private boolean inBatch;

    private List<List<ResolvedFeature>> featureBranches = Collections.emptyList();
    private List<ResolvedFeature> currentBranch = Collections.emptyList();


    ConfigModelStack(ConfigId configId, ProvisioningRuntimeBuilder rt) throws ProvisioningException {
        this.id = configId;
        this.rt = rt;
        lastConfig = new ConfigScope(null);
        configs.add(lastConfig);
        newFgScope();
    }

    boolean hasProperties() {
        return !props.isEmpty();
    }

    Map<String, String> getProperties() {
        return props;
    }

    void overwriteProps(Map<String, String> props) {
        if(props.isEmpty()) {
            return;
        }
        if(this.props.isEmpty()) {
            this.props = new HashMap<>(props.size());
        }
        this.props.putAll(props);
    }

    boolean hasConfigDeps() {
        return !configDeps.isEmpty();
    }

    Map<String, ConfigId> getConfigDeps() {
        return configDeps;
    }

    void overwriteConfigDeps(Map<String, ConfigId> configDeps) {
        if(configDeps.isEmpty()) {
            return;
        }
        if(this.configDeps.isEmpty()) {
            this.configDeps = new HashMap<>(configDeps.size());
        }
        this.configDeps.putAll(configDeps);
    }

    void pushConfig(ConfigModel model) throws ProvisioningException {
        lastConfig = new ConfigScope(model);
        configs.add(lastConfig);
    }

    ConfigModel popConfig() throws ProvisioningException {
        final ConfigScope result = lastConfig;
        configs.remove(configs.size() - 1);
        lastConfig = configs.get(configs.size() - 1);
        result.complete();
        return result.config;
    }

    boolean pushGroup(FeatureGroupSupport fg) throws ProvisioningException {
        if(!lastConfig.push(fg)) {
            return false;
        }
        newFgScope();
        return true;
    }

    boolean popGroup() throws ProvisioningException {
        mergeFgScope();
        return lastConfig.pop();
    }

    private void newFgScope() {
        ++lastFg;
        if (fgFeatures.size() == lastFg) {
            features = new LinkedHashMap<>();
            fgFeatures.add(features);
        } else {
            features = fgFeatures.get(lastFg);
        }
    }

    private void mergeFgScope() throws ProvisioningException {
        if(lastFg <= 0) {
            return;
        }
        final Map<ResolvedFeatureId, ResolvedFeature> endedGroup = fgFeatures.get(lastFg--);
        final Map<ResolvedFeatureId, ResolvedFeature> parentGroup = fgFeatures.get(lastFg);
        for (Map.Entry<ResolvedFeatureId, ResolvedFeature> entry : endedGroup.entrySet()) {
            final ResolvedFeature parentFeature = parentGroup.get(entry.getKey());
            if (parentFeature == null) {
                parentGroup.put(entry.getKey(), entry.getValue());
                if (lastFg == 0) {
                    addToSpecFeatures(entry.getValue());
                }
            } else {
                parentFeature.merge(entry.getValue(), true);
            }
        }
        endedGroup.clear();
        features = parentGroup;
    }

    boolean includes(ResolvedFeatureId id) {
        return features.containsKey(id);
    }

    void addFeature(ResolvedFeature feature) throws ProvisioningDescriptionException {
        if(feature.id == null) {
            addToSpecFeatures(feature);
            return;
        }
        features.put(feature.id, feature);
        if (lastFg == 0) {
            addToSpecFeatures(feature);
        }
    }

    ResolvedFeature includeFeature(ResolvedFeatureId id, ResolvedFeatureSpec spec, Map<String, Object> resolvedParams, Map<ResolvedFeatureId, FeatureDependencySpec> resolvedDeps)
            throws ProvisioningException {
        if(id != null) {
            final ResolvedFeature feature = features.get(id);
            if(feature != null) {
                feature.merge(resolvedDeps, resolvedParams, true);
                return feature;
            }
        }
        final ResolvedFeature feature = new ResolvedFeature(id, spec, resolvedParams, resolvedDeps, ++featureIncludeCount);
        addFeature(feature);
        return feature;
    }

    boolean isFilteredOut(ResolvedSpecId specId, final ResolvedFeatureId id) {
        if(lastConfig.isFilteredOut(specId, id)) {
            return true;
        }
        if(configs.size() > 1) {
            for (int i = configs.size() - 2; i >= 0; --i) {
                if (configs.get(i).isFilteredOut(specId, id)) {
                    return true;
                }
            }
        }
        return false;
    }

    void merge(ConfigModelStack other) throws ProvisioningException {
        if(!other.props.isEmpty()) {
            if(props.isEmpty()) {
                props = other.props;
            } else {
                for(Map.Entry<String, String> prop : other.props.entrySet()) {
                    if(!props.containsKey(prop.getKey())) {
                        props.put(prop.getKey(), prop.getValue());
                    }
                }
            }
        }
        if(!other.configDeps.isEmpty()) {
            if(configDeps.isEmpty()) {
                configDeps = other.configDeps;
            } else {
                for(Map.Entry<String, ConfigId> configDep : other.configDeps.entrySet()) {
                    if(!configDeps.containsKey(configDep.getKey())) {
                        configDeps.put(configDep.getKey(), configDep.getValue());
                    }
                }
            }
        }

        if(other.specFeatures.isEmpty()) {
            return;
        }
        for (Map.Entry<ResolvedSpecId, SpecFeatures> entry : other.specFeatures.entrySet()) {
            for (ResolvedFeature feature : entry.getValue().list) {
                if(feature.id == null) {
                    addToSpecFeatures(feature);
                    continue;
                }
                final ResolvedFeature localFeature = features.get(feature.id);
                if(localFeature == null) {
                    feature = feature.copy(++featureIncludeCount);
                    features.put(feature.id, feature);
                    addToSpecFeatures(feature);
                } else {
                    localFeature.merge(feature, false);
                }
            }
        }
    }

    List<ResolvedFeature> orderFeatures() throws ProvisioningException {
        if (!features.isEmpty()) {
            try {
                doOrder();
            } catch (ProvisioningException e) {
                throw new ProvisioningException(Errors.failedToBuildConfigSpec(id.getModel(), id.getName()), e);
            }
        }
        return orderedFeatures;
    }

    private void doOrder() throws ProvisioningException {
        for (SpecFeatures features : specFeatures.values()) {
            // resolve references
            features.spec.resolveRefMappings(rt);
            // resolve and register capability providers
            if(features.spec.xmlSpec.providesCapabilities()) {
                for(CapabilitySpec cap : features.spec.xmlSpec.getProvidedCapabilities()) {
                    if(cap.isStatic()) {
                        getProviders(cap.toString(), true).add(features);
                    } else {
                        for(ResolvedFeature feature : features.list) {
                            final List<String> resolvedCaps = capResolver.resolve(cap, feature);
                            if(resolvedCaps.isEmpty()) {
                                continue;
                            }
                            for(String resolvedCap : resolvedCaps) {
                                getProviders(resolvedCap, true).add(feature);
                            }
                        }
                    }
                }
            }
        }

        orderedFeatures = new ArrayList<>(features.size());

        currentBranch = new ArrayList<>();
        featureBranches = new ArrayList<>();
        featureBranches.add(currentBranch);

        for(SpecFeatures features : specFeatures.values()) {
            orderFeaturesInSpec(features, false);
        }

        final Path file = Paths.get(System.getProperty("user.home")).resolve("pm-scripts").resolve("feature-branches.txt");
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            int i = 1;
            for (List<ResolvedFeature> branch : featureBranches) {
                writer.write("Branch " + i++);
                writer.newLine();
                int j = 1;
                for (ResolvedFeature feature : branch) {
                    writer.write("    " + j++ + ". " + feature.getId());
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private CapabilityProviders getProviders(String cap, boolean add) throws ProvisioningException {
        CapabilityProviders providers = capProviders.get(cap);
        if(providers != null) {
            return providers;
        }
        if(!add) {
            throw new ProvisioningException(Errors.noCapabilityProvider(cap));
        }
        providers = new CapabilityProviders();
        if(capProviders.isEmpty()) {
            capProviders = Collections.singletonMap(cap, providers);
            return providers;
        }
        if(capProviders.size() == 1) {
            final Map.Entry<String, CapabilityProviders> first = capProviders.entrySet().iterator().next();
            capProviders = new HashMap<>(2);
            capProviders.put(first.getKey(), first.getValue());
        }
        capProviders.put(cap, providers);
        return providers;
    }

    /**
     * Attempts to order the features of the spec.
     * Terminates immediately when a feature reference loop is detected.
     *
     * @param features  spec features
     * @return  returns the feature id on which the feature reference loop was detected,
     *   returns null if no loop was detected (despite whether any feature was processed or not)
     * @throws ProvisioningException
     */
    private List<CircularRefInfo> orderFeaturesInSpec(SpecFeatures features, boolean force) throws ProvisioningException {
        if(!force) {
            if (!features.isFree()) {
                return null;
            }
            features.schedule();
        }

        List<CircularRefInfo> allCircularRefs = null;
        int i = 0;
        while(i < features.list.size() && allCircularRefs == null) {
            allCircularRefs = orderFeature(features.list.get(i++));
/*            if(circularRefs != null) {
                if(allCircularRefs == null) {
                    allCircularRefs = circularRefs;
                } else {
                    if(allCircularRefs.size() == 1) {
                        final CircularRefInfo first = allCircularRefs.get(0);
                        allCircularRefs = new ArrayList<>(1 + circularRefs.size());
                        allCircularRefs.add(first);
                    }
                    allCircularRefs.addAll(circularRefs);
                }
            }
*/        }
        if(!force) {
            features.free();
        }
        return allCircularRefs;
    }

    /**
     * Attempts to order the feature. If the feature has already been scheduled
     * for ordering but haven't been ordered yet, it means there is a circular feature
     * reference loop, in which case the feature is not ordered and false is returned.
     *
     * @param feature  the feature to put in the ordered list
     * @return  whether the feature was added to the ordered list or not
     * @throws ProvisioningException
     */
    private List<CircularRefInfo> orderFeature(ResolvedFeature feature) throws ProvisioningException {
        if(feature.isOrdered()) {
            return null;
        }
        if(!feature.isFree()) {
            return Collections.singletonList(new CircularRefInfo(feature));
        }
        feature.schedule();

        List<CircularRefInfo> circularRefs = null;
        if(feature.spec.xmlSpec.requiresCapabilities()) {
            circularRefs = orderCapabilityProviders(feature, circularRefs);
        }
        if(!feature.deps.isEmpty()) {
            circularRefs = orderReferencedFeatures(feature, feature.deps.keySet(), false, circularRefs);
        }
        List<ResolvedFeatureId> refIds = feature.resolveRefs();
        if(!refIds.isEmpty()) {
            circularRefs = orderReferencedFeatures(feature, refIds, true, circularRefs);
        }

        List<CircularRefInfo> initiatedCircularRefs = Collections.emptyList();
        if(circularRefs != null) {
            // there is a one or more circular feature reference loop(s)

            // check whether there is a loop that this feature didn't initiate
            // if there is such a loop then propagate the loops this feature didn't start to their origins
            if(circularRefs.size() == 1) {
                final CircularRefInfo next = circularRefs.get(0);
                if (next.loopedOn.id.equals(feature.id)) { // this feature initiated the loop
                    circularRefs = Collections.emptyList();
                    initiatedCircularRefs = Collections.singletonList(next);
                } else {
                    next.setNext(feature);
                    feature.free();
                }
            } else {
                final Iterator<CircularRefInfo> i = circularRefs.iterator();
                while (i.hasNext()) {
                    final CircularRefInfo next = i.next();
                    if (next.loopedOn.id.equals(feature.id)) {
                        // this feature initiated the loop
                        i.remove();
                        initiatedCircularRefs = PmCollections.add(initiatedCircularRefs, next);
                    } else {
                        // the feature is in the middle of the loop
                        next.setNext(feature);
                        feature.free();
                    }
                }
            }
            if(!circularRefs.isEmpty()) {
                return circularRefs;
            }
            // all the loops were initiated by this feature
        }

        if (!initiatedCircularRefs.isEmpty()) {
            final boolean prevOrderRefSpec = orderReferencedSpec;
            orderReferencedSpec = false;
            // sort according to the appearance in the config
            initiatedCircularRefs.sort(CircularRefInfo.getFirstInConfigComparator());
            if(initiatedCircularRefs.get(0).firstInConfig.includeNo < feature.includeNo) {
                feature.free();
                for(CircularRefInfo ref : initiatedCircularRefs) {
                    if(orderFeature(ref.firstInConfig) != null) {
                        throw new IllegalStateException();
                    }
                }
            } else {
                final boolean endBatch;
                if(inBatch) {
                    endBatch = false;
                } else {
                    inBatch = true;
                    feature.startBatch();
                    startNewBranch();
                    endBatch = true;
                }
                feature.ordered();
                ordered(feature);
                initiatedCircularRefs.sort(CircularRefInfo.getNextOnPathComparator());
                for(CircularRefInfo ref : initiatedCircularRefs) {
                    if(orderFeature(ref.nextOnPath) != null) {
                        throw new IllegalStateException();
                    }
                }
                if(endBatch) {
                    inBatch = false;
                    orderedFeatures.get(orderedFeatures.size() - 1).endBatch();
                    startNewBranch();
                }
            }
            orderReferencedSpec = prevOrderRefSpec;
        } else {
            feature.ordered();
            ordered(feature);
        }
        return null;
    }

    private void ordered(ResolvedFeature feature) {

        if(feature.spec.startsBranchAsParent) {
            startNewBranch();
        }

        orderedFeatures.add(feature);
        currentBranch.add(feature);
    }

    private void startNewBranch() {
        if(currentBranch.isEmpty()) {
            return;
        }
        currentBranch = new ArrayList<>();
        featureBranches.add(currentBranch);
    }

    private List<CircularRefInfo> orderCapabilityProviders(ResolvedFeature feature, List<CircularRefInfo> circularRefs)
            throws ProvisioningException {
        for (CapabilitySpec capSpec : feature.spec.xmlSpec.getRequiredCapabilities()) {
            final List<String> resolvedCaps = capResolver.resolve(capSpec, feature);
            if (resolvedCaps.isEmpty()) {
                continue;
            }
            for (String resolvedCap : resolvedCaps) {
                final CapabilityProviders providers;
                try {
                    providers = getProviders(resolvedCap, false);
                } catch (ProvisioningException e) {
                    throw new ProvisioningException(Errors.noCapabilityProvider(feature, capSpec, resolvedCap));
                }
                final List<CircularRefInfo> circles = orderProviders(providers);
                if (circularRefs == null) {
                    circularRefs = circles;
                } else {
                    if (circularRefs.size() == 1) {
                        final CircularRefInfo first = circularRefs.get(0);
                        circularRefs = new ArrayList<>(1 + circles.size());
                        circularRefs.add(first);
                    }
                    circularRefs.addAll(circles);
                }
            }
        }
        return circularRefs;
    }

    private List<CircularRefInfo> orderProviders(CapabilityProviders providers) throws ProvisioningException {
        if(!providers.isProvided()) {
            List<CircularRefInfo> firstLoop = null;
            if(!providers.specs.isEmpty()) {
                for(SpecFeatures specFeatures : providers.specs) {
                    final List<CircularRefInfo> loop = orderFeaturesInSpec(specFeatures, !specFeatures.isFree());
                    if(providers.isProvided()) {
                        return null;
                    }
                    if(firstLoop == null) {
                        firstLoop = loop;
                    }
                }
            }
            if (!providers.features.isEmpty()) {
                for (ResolvedFeature provider : providers.features) {
                    final List<CircularRefInfo> loop = orderFeature(provider);
                    if(providers.isProvided()) {
                        return null;
                    }
                    if(firstLoop == null) {
                        firstLoop = loop;
                    }
                }
            }
            return firstLoop;
        }
        return null;
    }

    /**
     * Attempts to order the referenced features.
     *
     * @param feature  parent feature
     * @param refIds  referenced features ids
     * @param specRefs  whether these referenced features represent actual spec references or feature dependencies
     * @return  feature ids that form circular dependency loops
     * @throws ProvisioningException
     */
    private List<CircularRefInfo> orderReferencedFeatures(ResolvedFeature feature, Collection<ResolvedFeatureId> refIds, boolean specRefs, List<CircularRefInfo> circularRefs) throws ProvisioningException {
        for(ResolvedFeatureId refId : refIds) {
            final List<CircularRefInfo> newCircularRefs = orderReferencedFeature(feature, refId, specRefs);
            if(newCircularRefs == null) {
                continue;
            }
            if (circularRefs == null) {
                circularRefs = newCircularRefs;
            } else {
                circularRefs = PmCollections.addAll(circularRefs, newCircularRefs);
            }
        }
        return circularRefs;
    }

    /**
     * Attempts to order a feature reference.
     *
     * @param feature  parent feature
     * @param refId  referenced feature id
     * @param specRef  whether the referenced feature represents a spec reference or a feature dependency
     * @return  true if the referenced feature was ordered, false if the feature was not ordered because of the circular reference loop
     * @throws ProvisioningException
     */
    private List<CircularRefInfo> orderReferencedFeature(ResolvedFeature feature, ResolvedFeatureId refId, boolean specRef) throws ProvisioningException {
        if(orderReferencedSpec && specRef && !feature.spec.id.equals(refId.specId)) {
            final SpecFeatures targetSpecFeatures = specFeatures.get(refId.specId);
            if (targetSpecFeatures == null) {
                throw new ProvisioningDescriptionException(Errors.unresolvedFeatureDep(feature, refId));
            }
            final List<CircularRefInfo> specLoops = orderFeaturesInSpec(targetSpecFeatures, false);
            if (specLoops != null) {
                List<CircularRefInfo> featureLoops = null;
                for (int i = 0; i < specLoops.size(); ++i) {
                    final CircularRefInfo specLoop = specLoops.get(i);
                    if (specLoop.nextOnPath.id.equals(refId)) {
                        if (featureLoops == null) {
                            featureLoops = Collections.singletonList(specLoop);
                        } else {
                            if (featureLoops.size() == 1) {
                                final CircularRefInfo first = featureLoops.get(0);
                                featureLoops = new ArrayList<>(2);
                                featureLoops.add(first);
                            }
                            featureLoops.add(specLoop);
                        }
                    }
                }
                if (featureLoops != null) {
                    return featureLoops;
                }
            }
        }
        final ResolvedFeature dep = features.get(refId);
        if (dep == null) {
            throw new ProvisioningDescriptionException(Errors.unresolvedFeatureDep(feature, refId));
        }
        return orderFeature(dep);
    }

    private void addToSpecFeatures(final ResolvedFeature feature) {
        SpecFeatures features = specFeatures.get(feature.spec.id);
        if(features == null) {
            features = new SpecFeatures(feature.spec);
            specFeatures.put(feature.spec.id, features);
        }
        features.list.add(feature);
    }

    private boolean isRelevant(ResolvedFeatureGroupConfig resolvedFg) {
        if(resolvedFg.fg.getId() == null) {
            return true;
        }
        if(!lastConfig.isRelevant(resolvedFg)) {
            return false;
        }
        if(configs.size() > 1) {
            for (int i = configs.size() - 2; i >= 0; --i) {
                if (!configs.get(i).isRelevant(resolvedFg)) {
                    return false;
                }
            }
        }
        return true;
    }
}

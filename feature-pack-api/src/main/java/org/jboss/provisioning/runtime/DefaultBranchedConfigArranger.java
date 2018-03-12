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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.provisioning.Errors;
import org.jboss.provisioning.ProvisioningDescriptionException;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.spec.CapabilitySpec;
import org.jboss.provisioning.util.PmCollections;

/**
 *
 * @author Alexey Loubyansky
 */
class DefaultBranchedConfigArranger {


    private final ConfigModelStack configStack;
    private final Map<ResolvedSpecId, SpecFeatures> specFeatures;
    private final Map<ResolvedFeatureId, ResolvedFeature> features;

    private CapabilityResolver capResolver = new CapabilityResolver();
    private Map<String, CapabilityProviders> capProviders = Collections.emptyMap();

    private boolean orderReferencedSpec = true;

    private List<ConfigFeatureBranch> featureBranches = Collections.emptyList();
    private ConfigFeatureBranch currentBranch;

    private boolean onParentChildrenBranch;
    private boolean arrangeBySpec = true;
    private boolean circularDeps;


    DefaultBranchedConfigArranger(ConfigModelStack configStack) {
        this.configStack = configStack;
        this.specFeatures = configStack.specFeatures;
        this.features = configStack.features;
    }

    List<ResolvedFeature> orderFeatures() throws ProvisioningException {
        try {
            doOrder(configStack.rt);
        } catch (ProvisioningException e) {
            throw new ProvisioningException(Errors.failedToBuildConfigSpec(configStack.id.getModel(), configStack.id.getName()), e);
        }

        final List<ResolvedFeature> orderedFeatures = new ArrayList<>(features.size());
        for(ConfigFeatureBranch branch : featureBranches) {
            orderBranches(branch, orderedFeatures);
        }
        return orderedFeatures;
    }

    private void orderBranches(ConfigFeatureBranch branch, List<ResolvedFeature> features) {
        if(branch.isOrdered()) {
            return;
        }
        if(branch.hasDeps()) {
            for(ConfigFeatureBranch dep : branch.getDeps()) {
                orderBranches(dep, features);
            }
        }
        features.addAll(branch.getFeatures());
        branch.ordered();
    }

    private void doOrder(ProvisioningRuntimeBuilder rt) throws ProvisioningException {
        for (SpecFeatures specFeatures : specFeatures.values()) {
            // resolve references
            specFeatures.spec.resolveRefMappings(rt);
            // resolve and register capability providers
            if(specFeatures.spec.xmlSpec.providesCapabilities()) {
                for(CapabilitySpec cap : specFeatures.spec.xmlSpec.getProvidedCapabilities()) {
                    if(cap.isStatic()) {
                        getProviders(cap.toString(), true).add(specFeatures);
                    } else {
                        for(ResolvedFeature feature : specFeatures.getFeatures()) {
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

        currentBranch = new ConfigFeatureBranch(0, false);
        featureBranches = new ArrayList<>();
        featureBranches.add(currentBranch);

        for(SpecFeatures features : specFeatures.values()) {
            orderFeaturesInSpec(features, false);
        }
/*
        final Path file = Paths.get(System.getProperty("user.home")).resolve("pm-scripts").resolve("feature-branches.txt");
        try {
            Files.createDirectories(file.getParent());
        } catch (IOException e) {
        }
        try (BufferedWriter writer = Files.newBufferedWriter(file)) {
            int i = 1;
            for (ConfigFeatureBranch branch : featureBranches) {
                writer.write("Branch " + i++ + " batch=" + branch.isBatch());
                writer.newLine();
                int j = 1;
                for (ResolvedFeature feature : branch.getFeatures()) {
                    writer.write("    " + j++ + ". " + feature.getId());
                    if(feature.isBatchStart()) {
                        writer.write(" start batch");
                    }
                    if(feature.isBatchEnd()) {
                        writer.write(" end batch");
                    }
                    writer.newLine();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        */
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
        capProviders = PmCollections.put(capProviders, cap, providers);
        return providers;
    }

    /**
     * Attempts to order the features of the spec.
     * Terminates immediately when a feature reference loop is detected.
     *
     * @param specFeatures  spec features
     * @return  returns the feature id on which the feature reference loop was detected,
     *   returns null if no loop was detected (despite whether any feature was processed or not)
     * @throws ProvisioningException
     */
    private List<CircularRefInfo> orderFeaturesInSpec(SpecFeatures specFeatures, boolean force) throws ProvisioningException {
        if(!force) {
            if (!specFeatures.isFree()) {
                return null;
            }
            specFeatures.schedule();
        }

        List<CircularRefInfo> allCircularRefs = null;
        int i = 0;
        final List<ResolvedFeature> features = specFeatures.getFeatures();
        while(i < features.size() && allCircularRefs == null) {
            if (onParentChildrenBranch) {
                onParentChildrenBranch = false;
                startNewBranch(false);
            }
            allCircularRefs = orderFeature(features.get(i++));
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
            specFeatures.free();
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

        List<CircularRefInfo> circularRefs = Collections.emptyList();
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
        if(!circularRefs.isEmpty()) {
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
                final boolean originalCircularDeps = circularDeps;
                circularDeps = true;

                boolean endBatch = false;
                if(!currentBranch.isBatch()) {
                    startNewBranch(true);
                    endBatch = true;
                }
                ordered(feature);
                initiatedCircularRefs.sort(CircularRefInfo.getNextOnPathComparator());
                for(CircularRefInfo ref : initiatedCircularRefs) {
                    if(orderFeature(ref.nextOnPath) != null) {
                        throw new IllegalStateException();
                    }
                }
                if(endBatch) {
                    startNewBranch(false);
                }
                circularDeps = originalCircularDeps;
            }
            orderReferencedSpec = prevOrderRefSpec;
        } else {
            ordered(feature);
        }
        return null;
    }

    private void ordered(ResolvedFeature feature) throws ProvisioningException {

        ConfigFeatureBranch branch = currentBranch;
        if(circularDeps) {
            // currentBranch
        } else if(feature.spec.startNewBranch) {
            branch = startNewBranch(false);
            branch.setFkBranch();
            if(feature.spec.parentChildrenBranch) {
                onParentChildrenBranch = true;
            }
            if(feature.spec.batchBranch) {
                branch.setBatch();
            }
        } else {
            boolean branchReset = false;
            if (!feature.branchDeps.isEmpty()) {
                // System.out.println("branch deps for " + feature.id + " are " + feature.branchDeps);

                final Iterator<Map.Entry<ConfigFeatureBranch, Boolean>> branchDepIter = feature.branchDeps.entrySet().iterator();
                if (feature.branchDeps.size() == 1) {
                    final Map.Entry<ConfigFeatureBranch, Boolean> next = branchDepIter.next();
                    if (next.getValue() && next.getKey().isFkBranch()) {
                        branch = next.getKey();
                        branchReset = true;
                    }
                } else {
                    while (branchDepIter.hasNext()) {
                        final Map.Entry<ConfigFeatureBranch, Boolean> next = branchDepIter.next();
                        if (!next.getValue() || !next.getKey().isFkBranch()) {
                            continue;
                        }
                        final ConfigFeatureBranch candidate = next.getKey();
                        if (!createsDepCircle(candidate, feature)) {
                            branch = candidate;
                            branchReset = true;
                            break;
                        }
                    }
                }
            }

            if (!branchReset && arrangeBySpec) {
                //System.out.println("arranging by spec " + feature.id);
                final SpecFeatures spec = feature.getSpecFeatures();
                if (!spec.isBranchSet()) {
                    branch = startNewBranch(false);
                    spec.setBranch(branch);
                } else if (!createsDepCircle(spec.getBranch(), feature)) {
                    branch = spec.getBranch();
                    //System.out.println("  spec branch " + spec.getBranch());
                }
            }
        }

        branch.add(feature);
        feature.branch = branch;
        feature.ordered();
        if(!circularDeps && !feature.getSpecFeatures().isBranchSet()) {
            feature.getSpecFeatures().setBranch(branch);
        }
        //System.out.println(feature.getId().toString() + " landed on " + feature.branch);
    }

    private boolean createsDepCircle(ConfigFeatureBranch branch, ResolvedFeature feature) {
        //System.out.println("createDepCircle " + branch + " " + feature.branchDeps);
        Set<ConfigFeatureBranch> visitedBranches = null;
        for(ConfigFeatureBranch newDep : feature.branchDeps.keySet()) {
            if(newDep.id == branch.id) {
                continue;
            }
            if(branch.dependsOn(newDep)) {
                continue;
            }
            if(newDep.dependsOn(branch)) {
                return true;
            }
            if(visitedBranches == null) {
                visitedBranches = new HashSet<>();
                visitedBranches.add(branch);
            }
            if(createsDepCircle(newDep, visitedBranches)) {
                return true;
            }
        }
        return false;
    }

    private boolean createsDepCircle(ConfigFeatureBranch next, Set<ConfigFeatureBranch> visitedBranches) {
        if(!next.hasDeps()) {
            return false;
        }
        visitedBranches.add(next);
        for(ConfigFeatureBranch newDep : next.getDeps()) {
            if(visitedBranches.contains(newDep)) {
                return true;
            }
            if(createsDepCircle(newDep, visitedBranches)) {
                return true;
            }
        }
        visitedBranches.remove(next);
        return false;
    }

    private ConfigFeatureBranch startNewBranch(boolean batch) throws ProvisioningException {
        if(currentBranch.isEmpty()) {
            if(currentBranch.isBatch() == batch) {
                return currentBranch;
            }
            if(batch) {
                currentBranch.setBatch();
                return currentBranch;
            }
        }
        currentBranch = new ConfigFeatureBranch(featureBranches.size(), batch);
        featureBranches.add(currentBranch);
        return currentBranch;
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
                circularRefs = PmCollections.addAll(circularRefs, orderProviders(providers));
                if(providers.isProvided()) {
                    feature.addBranchDep(providers.branches.iterator().next(), false);
                    //System.out.println("added branch dep on cap provider " + feature.getId() + " -> " + providers.branches);
                }
            }
        }
        return circularRefs;
    }

    private List<CircularRefInfo> orderProviders(CapabilityProviders providers) throws ProvisioningException {
        if(providers.isProvided()) {
            return Collections.emptyList();
        }
        List<CircularRefInfo> firstLoop = null;
        if (!providers.specs.isEmpty()) {
            for (SpecFeatures specFeatures : providers.specs) {
                final List<CircularRefInfo> loop = orderFeaturesInSpec(specFeatures, !specFeatures.isFree());
                if (providers.isProvided()) {
                    return Collections.emptyList();
                }
                if (firstLoop == null) {
                    firstLoop = loop;
                }
            }
        }
        if (!providers.features.isEmpty()) {
            for (ResolvedFeature provider : providers.features) {
                final List<CircularRefInfo> loop = orderFeature(provider);
                if (providers.isProvided()) {
                    return Collections.emptyList();
                }
                if (firstLoop == null) {
                    firstLoop = loop;
                }
            }
        }
        return firstLoop == null ? Collections.emptyList() : firstLoop;
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
            circularRefs = PmCollections.addAll(circularRefs, newCircularRefs);
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
        final List<CircularRefInfo> circularRefs = orderFeature(dep);
        if(dep.branch != null) {
            feature.addBranchDep(dep.branch, refId.isChildRef());
        }
        return circularRefs;
    }
}

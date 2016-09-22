/*
 * Copyright 2016 Red Hat, Inc. and/or its affiliates
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
package org.jboss.provisioning.wildfly.descr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author Alexey Loubyansky
 */
public class WFInstallationDescription {

    public static class Builder {

        private String modulesPath;
        private List<WFFeaturePackDescription> featurePacks = Collections.emptyList();

        Builder() {
        }

        public void setModulesPath(String modulesPath) {
            if(this.modulesPath != null) {
                throw new IllegalStateException("Modules path has already been set");
            }
            this.modulesPath = modulesPath;
        }

        public void addFeaturePack(WFFeaturePackDescription fpBuilder) {
            switch(featurePacks.size()) {
                case 0:
                    featurePacks = Collections.singletonList(fpBuilder);
                    break;
                case 1:
                    featurePacks = new ArrayList<WFFeaturePackDescription>(featurePacks);
                default:
                    featurePacks.add(fpBuilder);
            }
        }

        public WFInstallationDescription build() {
            return new WFInstallationDescription(modulesPath, Collections.unmodifiableList(featurePacks));
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final String modulesPath;
    private final List<WFFeaturePackDescription> featurePacks;

    private WFInstallationDescription(String modulesPath, List<WFFeaturePackDescription> featurePacks) {
        this.modulesPath = modulesPath;
        this.featurePacks = featurePacks;
    }

    public String getModulesPath() {
        return modulesPath;
    }

    public List<WFFeaturePackDescription> getFeaturePacks() {
        return featurePacks;
    }
}
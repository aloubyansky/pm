/*
 * Copyright 2016-2017 Red Hat, Inc. and/or its affiliates
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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 *
 * @author Alexey Loubyansky
 */
public class PackageScripts {

    public static class Script {

        public static Script newScript(String name) {
            return newScript(name, false);
        }

        public static Script newScript(String name, boolean variable) {
            return new Script(name, null, variable);
        }

        public static Script newScript(String name, String prefix) {
            return new Script(name, prefix, prefix != null);
        }

        private final String name;
        private final String prefix;
        private final boolean variable;

        private Script(String name, String prefix, boolean variable) {
            this.name = name;
            this.prefix = prefix;
            this.variable = variable;
        }

        public String getName() {
            return name;
        }

        public boolean hasPrefix() {
            return prefix != null;
        }

        public String getPrefix() {
            return prefix;
        }

        public boolean isStatic() {
            return !variable;
        }
    }

    public static class Builder {

        private List<Script> standalone = Collections.emptyList();
        private List<Script> domain = Collections.emptyList();
        private List<Script> host = Collections.emptyList();

        private Builder() {
        }

        public Builder addStandalone(Script script) {
            switch(standalone.size()) {
                case 0:
                    standalone = Collections.singletonList(script);
                    break;
                case 1:
                    standalone = new ArrayList<>(standalone);
                default:
                    standalone.add(script);
            }
            return this;
        }

        public Builder addDomain(Script script) {
            switch(domain.size()) {
                case 0:
                    domain = Collections.singletonList(script);
                    break;
                case 1:
                    domain = new ArrayList<>(domain);
                default:
                    domain.add(script);
            }
            return this;
        }

        public Builder addHost(Script script) {
            switch(host.size()) {
                case 0:
                    host = Collections.singletonList(script);
                    break;
                case 1:
                    host = new ArrayList<>(host);
                default:
                    host.add(script);
            }
            return this;
        }

        public PackageScripts build() {
            return new PackageScripts(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final PackageScripts DEFAULT = builder()
            .addStandalone(Script.newScript("main.cli"))
            .addStandalone(Script.newScript("standalone.cli"))
            .addStandalone(Script.newScript("variable.cli", true))
            .addStandalone(Script.newScript("profile.cli"))
            .addDomain(Script.newScript("main.cli"))
            .addDomain(Script.newScript("domain.cli"))
            .addDomain(Script.newScript("variable.cli", true))
            .addDomain(Script.newScript("profile.cli", "/profile=$profile"))
            .addHost(Script.newScript("host.cli", "/host=${host:master}"))
            .build();

    private final List<Script> standalone;
    private final List<Script> domain;
    private final List<Script> host;

    private PackageScripts(Builder builder) {
        this.standalone = builder.standalone.isEmpty() ? builder.standalone : Collections.unmodifiableList(builder.standalone);
        this.domain = builder.domain.isEmpty() ? builder.domain : Collections.unmodifiableList(builder.domain);
        this.host = builder.host.isEmpty() ? builder.host : Collections.unmodifiableList(builder.host);
    }

    public boolean hasStandalone() {
        return !standalone.isEmpty();
    }

    public boolean hasHost() {
        return !host.isEmpty();
    }

    public boolean hasDomain() {
        return !domain.isEmpty();
    }

    public List<Script> getStandalone() {
        return standalone;
    }

    public List<Script> getDomain() {
        return domain;
    }

    public List<Script> getHost() {
        return host;
    }
}

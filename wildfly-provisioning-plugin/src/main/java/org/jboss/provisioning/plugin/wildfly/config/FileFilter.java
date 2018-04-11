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
package org.jboss.provisioning.plugin.wildfly.config;


import java.util.regex.Pattern;

import org.jboss.provisioning.util.ParsingUtils;

/**
 * @author Stuart Douglas
 */
public class FileFilter {

    public static class Builder {

        private String patternString;
        private boolean include;

        private Builder() {
        }

        public Builder setPatternString(String patternString) {
            this.patternString = patternString;
            return this;
        }

        public Builder setInclude() {
            this.include = true;
            return this;
        }

        public FileFilter build() {
            return new FileFilter(patternString, include);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    private final String patternString;
    private final Pattern pattern;
    private final boolean include;

    private FileFilter(String patternString, boolean include) {
        if (patternString == null) {
            throw new IllegalArgumentException("null pattern");
        }
        this.patternString = patternString;
        this.pattern = Pattern.compile(ParsingUtils.wildcardToJavaRegexp(patternString));
        this.include = include;
    }

    public String getPattern() {
        return patternString;
    }

    /**
     * Returns true if the file matches the regular expression
     */
    public boolean matches(final String filePath) {
        return pattern.matcher(filePath).matches();
    }

    public boolean isInclude() {
        return include;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        FileFilter that = (FileFilter) o;

        if (!patternString.equals(that.patternString)) return false;

        return true;
    }

    @Override
    public int hashCode() {
        return patternString.hashCode();
    }

    @Override
    public String toString() {
        return patternString;
    }
}

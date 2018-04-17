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
package org.wildfly.galleon.maven;

import java.util.Arrays;

import org.jboss.galleon.ArtifactCoords;

/**
 *
 * @author Alexey Loubyansky
 */
public class ArtifactCoordsUtil {

    public static String toJBossModules(ArtifactCoords coords, boolean includeVersion) {
        final StringBuilder buf = new StringBuilder();
        buf.append(coords.getGroupId()).append(':').append(coords.getArtifactId());
        if(coords.getClassifier() != null && !coords.getClassifier().isEmpty()) {
            buf.append(':');
            if(includeVersion && coords.getVersion() != null) {
                buf.append(coords.getVersion());
            }
            buf.append(':').append(coords.getClassifier());
        } else if(includeVersion && coords.getVersion() != null) {
            buf.append(':').append(coords.getVersion());
        }
        return buf.toString();
    }

    public static ArtifactCoords fromJBossModules(String str, String extension) {
        final String[] parts = str.split(":");
        if(parts.length < 2) {
            throw new IllegalArgumentException("Unexpected artifact coordinates format: " + str);
        }
        final String groupId = parts[0];
        final String artifactId = parts[1];
        String version = null;
        String classifier = null;
        if(parts.length > 2) {
            if(!parts[2].isEmpty()) {
                version = parts[2];
            }
            if(parts.length > 3 && !parts[3].isEmpty()) {
                classifier = parts[3];
                if(parts.length > 4) {
                    throw new IllegalArgumentException("Unexpected artifact coordinates format: " + str);
                }
            }
        }
        return new ArtifactCoords(groupId, artifactId, version, classifier, extension);
    }

    public static void main(String[] args) throws Exception {
        System.out.println(Arrays.asList("group:artifact:version".split(":")));
    }
}

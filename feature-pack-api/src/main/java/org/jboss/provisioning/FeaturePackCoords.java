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

package org.jboss.provisioning;

/**
 *
 * @author Alexey Loubyansky
 */
public class FeaturePackCoords {

    public static FeaturePackCoords fromString(String str) throws ProvisioningDescriptionException {
        if(str == null) {
            throw new IllegalArgumentException("str is null");
        }
        final int universeEnd = str.indexOf(':');
        if(universeEnd <= 0) {
            throw unexpectedFormat(str);
        }
        final int familyEnd = str.indexOf(':', universeEnd + 1);
        if(familyEnd - universeEnd <= 1) {
            throw unexpectedFormat(str);
        }
        final int branchEnd = str.indexOf(':', familyEnd + 1);
        if(branchEnd - familyEnd <= 1) {
            throw unexpectedFormat(str);
        }
        String classifier = null;
        int buildSeparator = str.indexOf(':', branchEnd + 1);
        if(buildSeparator > 0) {
            if(buildSeparator - branchEnd <= 1 || str.length() - buildSeparator == 1) {
                throw unexpectedFormat(str);
            }
            classifier = str.substring(branchEnd + 1, buildSeparator);
        } else if(str.length() - branchEnd == 1) {
            throw unexpectedFormat(str);
        } else {
            buildSeparator = branchEnd;
        }
        return new FeaturePackCoords(str.substring(0, universeEnd),
                str.substring(universeEnd + 1, familyEnd),
                str.substring(familyEnd + 1, branchEnd),
                classifier,
                str.substring(buildSeparator + 1));
    }

    private static ProvisioningDescriptionException unexpectedFormat(String str) {
        return new ProvisioningDescriptionException(str + " does not follow format universe:family:branch[:classifier]:build");
    }

    public class FeaturePackId {

        public String getUniverse() {
            return universe;
        }

        public String getFamily() {
            return family;
        }

        public String getBranch() {
            return branch;
        }

        public String getBuild() {
            return build;
        }

        public FeaturePackBranch getUniverseStream() {
            return FeaturePackCoords.this.fpBranch;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((build == null) ? 0 : build.hashCode());
            result = prime * result + ((branch == null) ? 0 : branch.hashCode());
            result = prime * result + ((family == null) ? 0 : family.hashCode());
            result = prime * result + ((universe == null) ? 0 : universe.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            FeaturePackId other = (FeaturePackId) obj;
            if (build == null) {
                if (other.getBuild() != null)
                    return false;
            } else if (!build.equals(other.getBuild()))
                return false;
            if (branch == null) {
                if (other.getBranch() != null)
                    return false;
            } else if (!branch.equals(other.getBranch()))
                return false;
            if (family == null) {
                if (other.getFamily() != null)
                    return false;
            } else if (!family.equals(other.getFamily()))
                return false;
            if (universe == null) {
                if (other.getUniverse() != null)
                    return false;
            } else if (!universe.equals(other.getUniverse()))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return universe + ":" + family + ":" + branch + ":" + build;
        }
    }

    public class FeaturePackBranch {

        public String getUniverse() {
            return universe;
        }

        public String getFamily() {
            return family;
        }

        public String getBranch() {
            return branch;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((branch == null) ? 0 : branch.hashCode());
            result = prime * result + ((family == null) ? 0 : family.hashCode());
            result = prime * result + ((universe == null) ? 0 : universe.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            FeaturePackBranch other = (FeaturePackBranch) obj;
            if (branch == null) {
                if (other.getBranch() != null)
                    return false;
            } else if (!branch.equals(other.getBranch()))
                return false;
            if (family == null) {
                if (other.getFamily() != null)
                    return false;
            } else if (!family.equals(other.getFamily()))
                return false;
            if (universe == null) {
                if (other.getUniverse() != null)
                    return false;
            } else if (!universe.equals(other.getUniverse()))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return universe + ":" + family + ":" + branch;
        }
    }

    public class FeaturePackStream {

        public String getUniverse() {
            return universe;
        }

        public String getFamily() {
            return family;
        }

        public String getBranch() {
            return branch;
        }

        public String getClassifier() {
            return classifier;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + ((classifier == null) ? 0 : classifier.hashCode());
            result = prime * result + ((branch == null) ? 0 : branch.hashCode());
            result = prime * result + ((family == null) ? 0 : family.hashCode());
            result = prime * result + ((universe == null) ? 0 : universe.hashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj)
                return true;
            if (obj == null)
                return false;
            if (getClass() != obj.getClass())
                return false;
            FeaturePackStream other = (FeaturePackStream) obj;
            if (classifier == null) {
                if (other.getClassifier() != null)
                    return false;
            } else if (!classifier.equals(other.getClassifier()))
                return false;
            if (branch == null) {
                if (other.getBranch() != null)
                    return false;
            } else if (!branch.equals(other.getBranch()))
                return false;
            if (family == null) {
                if (other.getFamily() != null)
                    return false;
            } else if (!family.equals(other.getFamily()))
                return false;
            if (universe == null) {
                if (other.getUniverse() != null)
                    return false;
            } else if (!universe.equals(other.getUniverse()))
                return false;
            return true;
        }

        @Override
        public String toString() {
            return universe + ":" + family + ":" + branch + ":" + classifier;
        }
    }

    private final String universe;
    private final String family;
    private final String branch;
    private final String classifier;
    private final String build;
    private FeaturePackBranch fpBranch;
    private FeaturePackStream fpStream;
    private FeaturePackId fpId;

    public FeaturePackCoords(String universe, String family, String branch, String classifier, String build) {
        this.universe = universe;
        this.family = family;
        this.branch = branch;
        this.classifier = classifier;
        this.build = build;
    }

    public String getUniverse() {
        return universe;
    }

    public String getFamily() {
        return family;
    }

    public String getBranch() {
        return branch;
    }

    public String getClassifier() {
        return classifier;
    }

    public String getBuild() {
        return build;
    }

    public FeaturePackBranch getFeaturePackBranch() {
        if(fpBranch == null) {
            fpBranch = new FeaturePackBranch();
        }
        return fpBranch;
    }

    public FeaturePackStream getFeaturePackStream() {
        if(fpStream == null) {
            fpStream = new FeaturePackStream();
        }
        return fpStream;
    }

    public FeaturePackId getFeaturePackId() {
        if(fpId == null) {
            this.fpId = new FeaturePackId();
        }
        return fpId;
    }

    @Override
    public int hashCode() {
        final int prime = 31;
        int result = 1;
        result = prime * result + ((branch == null) ? 0 : branch.hashCode());
        result = prime * result + ((build == null) ? 0 : build.hashCode());
        result = prime * result + ((classifier == null) ? 0 : classifier.hashCode());
        result = prime * result + ((family == null) ? 0 : family.hashCode());
        result = prime * result + ((fpId == null) ? 0 : fpId.hashCode());
        result = prime * result + ((universe == null) ? 0 : universe.hashCode());
        result = prime * result + ((fpBranch == null) ? 0 : fpBranch.hashCode());
        return result;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj)
            return true;
        if (obj == null)
            return false;
        if (getClass() != obj.getClass())
            return false;
        FeaturePackCoords other = (FeaturePackCoords) obj;
        if (branch == null) {
            if (other.branch != null)
                return false;
        } else if (!branch.equals(other.branch))
            return false;
        if (build == null) {
            if (other.build != null)
                return false;
        } else if (!build.equals(other.build))
            return false;
        if (classifier == null) {
            if (other.classifier != null)
                return false;
        } else if (!classifier.equals(other.classifier))
            return false;
        if (family == null) {
            if (other.family != null)
                return false;
        } else if (!family.equals(other.family))
            return false;
        if (fpId == null) {
            if (other.fpId != null)
                return false;
        } else if (!fpId.equals(other.fpId))
            return false;
        if (universe == null) {
            if (other.universe != null)
                return false;
        } else if (!universe.equals(other.universe))
            return false;
        if (fpBranch == null) {
            if (other.fpBranch != null)
                return false;
        } else if (!fpBranch.equals(other.fpBranch))
            return false;
        return true;
    }

    @Override
    public String toString() {
        return universe + ":" + family + ":" + branch + ":" + classifier + ":" + build;
    }
}

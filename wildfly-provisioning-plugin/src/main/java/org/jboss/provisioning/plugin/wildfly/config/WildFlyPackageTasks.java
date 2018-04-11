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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import javax.xml.stream.XMLStreamException;

import org.jboss.provisioning.Errors;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.util.PmCollections;


/**
 *
 * @author Alexey Loubyansky
 */
public class WildFlyPackageTasks {

    public static class Builder {

        private List<CopyArtifact> copyArtifacts = Collections.emptyList();
        private List<CopyPath> copyPaths = Collections.emptyList();
        private List<DeletePath> deletePaths = Collections.emptyList();
        private List<FilePermission> filePermissions = Collections.emptyList();
        private List<String> mkDirs = Collections.emptyList();
        private List<FileFilter> windowsLineEndFilters = Collections.emptyList();
        private List<FileFilter> unixLineEndFilters = Collections.emptyList();

        private Builder() {
        }

        public Builder addCopyArtifact(CopyArtifact copy) {
            copyArtifacts = PmCollections.add(copyArtifacts, copy);
            return this;
        }

        public Builder addCopyPath(CopyPath copy) {
            copyPaths = PmCollections.add(copyPaths, copy);
            return this;
        }

        public Builder addCopyArtifacts(List<CopyArtifact> copyArtifacts) {
            for(CopyArtifact ca : copyArtifacts) {
                addCopyArtifact(ca);
            }
            return this;
        }

        public Builder addCopyPaths(List<CopyPath> copyPaths) {
            for(CopyPath ca : copyPaths) {
                addCopyPath(ca);
            }
            return this;
        }

        public Builder addDeletePath(DeletePath deletePath) {
            deletePaths = PmCollections.add(deletePaths, deletePath);
            return this;
        }

        public Builder addDeletePaths(List<DeletePath> deletePaths) {
            for(DeletePath dp : deletePaths) {
                addDeletePath(dp);
            }
            return this;
        }

        public Builder addFilePermissions(FilePermission filePermission) {
            filePermissions = PmCollections.add(filePermissions, filePermission);
            return this;
        }

        public Builder addFilePermissions(List<FilePermission> filePermissions) {
            for(FilePermission fp : filePermissions) {
                addFilePermissions(fp);
            }
            return this;
        }

        public Builder addMkDirs(String mkdirs) {
            mkDirs = PmCollections.add(mkDirs, mkdirs);
            return this;
        }

        public Builder addMkDirs(List<String> mkdirs) {
            for(String mkdir : mkdirs) {
                addMkDirs(mkdir);
            }
            return this;
        }

        public Builder addWindowsLineEndFilter(FileFilter filter) {
            windowsLineEndFilters = PmCollections.add(windowsLineEndFilters, filter);
            return this;
        }

        public Builder addWindowsLineEndFilters(List<FileFilter> filters) {
            for(FileFilter filter : filters) {
                addWindowsLineEndFilter(filter);
            }
            return this;
        }

        public Builder addUnixLineEndFilter(FileFilter filter) {
            unixLineEndFilters = PmCollections.add(unixLineEndFilters, filter);
            return this;
        }

        public Builder addUnixLineEndFilters(List<FileFilter> filters) {
            for(FileFilter filter : filters) {
                addUnixLineEndFilter(filter);
            }
            return this;
        }

        public WildFlyPackageTasks build() {
            return new WildFlyPackageTasks(this);
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static WildFlyPackageTasks load(Path configFile) throws ProvisioningException {
        try (InputStream configStream = Files.newInputStream(configFile)) {
            return new WildFlyPackageTasksParser().parse(configStream);
        } catch (XMLStreamException e) {
            throw new ProvisioningException(Errors.parseXml(configFile), e);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.openFile(configFile), e);
        }
    }

    private final List<CopyArtifact> copyArtifacts;
    private final List<CopyPath> copyPaths;
    private final List<DeletePath> deletePaths;
    private final List<FilePermission> filePermissions;
    private final List<String> mkDirs;
    private final List<FileFilter> windowsLineEndFilters;
    private final List<FileFilter> unixLineEndFilters;

    private WildFlyPackageTasks(Builder builder) {
        this.copyArtifacts = PmCollections.unmodifiable(builder.copyArtifacts);
        this.copyPaths = PmCollections.unmodifiable(builder.copyPaths);
        this.deletePaths = PmCollections.unmodifiable(builder.deletePaths);
        this.filePermissions = PmCollections.unmodifiable(builder.filePermissions);
        this.mkDirs = PmCollections.unmodifiable(builder.mkDirs);
        this.windowsLineEndFilters = PmCollections.unmodifiable(builder.windowsLineEndFilters);
        this.unixLineEndFilters = PmCollections.unmodifiable(builder.unixLineEndFilters);
    }

    public boolean hasCopyArtifacts() {
        return !copyArtifacts.isEmpty();
    }

    public List<CopyArtifact> getCopyArtifacts() {
        return copyArtifacts;
    }

    public boolean hasCopyPaths() {
        return !copyPaths.isEmpty();
    }

    public List<CopyPath> getCopyPaths() {
        return copyPaths;
    }

    public boolean hasDeletePaths() {
        return !deletePaths.isEmpty();
    }

    public List<DeletePath> getDeletePaths() {
        return deletePaths;
    }

    public boolean hasFilePermissions() {
        return !filePermissions.isEmpty();
    }

    public List<FilePermission> getFilePermissions() {
        return filePermissions;
    }

    public boolean hasMkDirs() {
        return !mkDirs.isEmpty();
    }

    public List<String> getMkDirs() {
        return mkDirs;
    }

    public List<FileFilter> getWindowsLineEndFilters() {
        return windowsLineEndFilters;
    }

    public List<FileFilter> getUnixLineEndFilters() {
        return unixLineEndFilters;
    }
}

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
package org.jboss.provisioning.plugin.wildfly;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Stream;

import nu.xom.Attribute;
import nu.xom.Builder;
import nu.xom.Document;
import nu.xom.Element;
import nu.xom.Elements;
import nu.xom.ParsingException;
import nu.xom.Serializer;

import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.Errors;
import org.jboss.provisioning.MessageWriter;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.plugin.ProvisioningPlugin;
import org.jboss.provisioning.plugin.wildfly.config.CopyArtifact;
import org.jboss.provisioning.plugin.wildfly.config.CopyPath;
import org.jboss.provisioning.plugin.wildfly.config.DeletePath;
import org.jboss.provisioning.plugin.wildfly.config.FilePermission;
import org.jboss.provisioning.plugin.wildfly.config.WildFlyPackageTasks;
import org.jboss.provisioning.plugin.wildfly.sandbox.EmbeddedScriptRunner;
import org.jboss.provisioning.plugin.wildfly.sandbox.WfConfig2EmbeddedScriptHandler;
import org.jboss.provisioning.runtime.FeaturePackRuntime;
import org.jboss.provisioning.runtime.PackageRuntime;
import org.jboss.provisioning.runtime.ProvisioningRuntime;
import org.jboss.provisioning.state.ProvisionedConfig;
import org.jboss.provisioning.util.IoUtils;
import org.jboss.provisioning.util.PmCollections;
import org.jboss.provisioning.util.PropertyUtils;
import org.jboss.provisioning.util.ZipUtils;

/**
 *
 * @author Alexey Loubyansky
 */
public class WfProvisioningPlugin implements ProvisioningPlugin {

    private ProvisioningRuntime runtime;
    private PropertyResolver versionResolver;

    private PropertyResolver tasksProps;

    private boolean thinServer;
    private Set<String> schemaGroups = Collections.emptySet();

    /* (non-Javadoc)
     * @see org.jboss.provisioning.util.plugin.ProvisioningPlugin#execute()
     */
    @Override
    public void postInstall(ProvisioningRuntime runtime) throws ProvisioningException {

        final MessageWriter messageWriter = runtime.getMessageWriter();
        messageWriter.verbose("WildFly provisioning plug-in");

        final String thinServerProp = System.getProperty("wfThinServer");
        if(thinServerProp != null) {
            if(thinServerProp.isEmpty()) {
                thinServer = true;
            } else {
                thinServer = Boolean.parseBoolean(thinServerProp);
            }
        }

        this.runtime = runtime;

        Properties provisioningProps = new Properties();
        final Map<String, String> artifactVersions = new HashMap<>();
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            final Path wfRes = fp.getResource(WfConstants.WILDFLY);
            if(!Files.exists(wfRes)) {
                continue;
            }

            final Path artifactProps = wfRes.resolve(WfConstants.ARTIFACT_VERSIONS_PROPS);
            if(Files.exists(artifactProps)) {
                try (Stream<String> lines = Files.lines(artifactProps)) {
                    final Iterator<String> iterator = lines.iterator();
                    while (iterator.hasNext()) {
                        final String line = iterator.next();
                        final int i = line.indexOf('=');
                        if (i < 0) {
                            throw new ProvisioningException("Failed to locate '=' character in " + line);
                        }
                        artifactVersions.put(line.substring(0, i), line.substring(i + 1));
                    }
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.readFile(artifactProps), e);
                }
            }

            final Path tasksPropsPath = wfRes.resolve(WfConstants.WILDFLY_TASKS_PROPS);
            if(Files.exists(tasksPropsPath)) {
                if(!provisioningProps.isEmpty()) {
                    provisioningProps = new Properties(provisioningProps);
                }
                try(InputStream in = Files.newInputStream(tasksPropsPath)) {
                    provisioningProps.load(in);
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.readFile(tasksPropsPath), e);
                }
            }

            if(fp.containsPackage(WfConstants.DOCS_SCHEMA)) {
                final Path schemaGroupsTxt = fp.getPackage(WfConstants.DOCS_SCHEMA).getResource(
                        WfConstants.PM, WfConstants.WILDFLY, WfConstants.SCHEMA_GROUPS_TXT);
                try(BufferedReader reader = Files.newBufferedReader(schemaGroupsTxt)) {
                    String line = reader.readLine();
                    while(line != null) {
                        schemaGroups = PmCollections.add(schemaGroups, line);
                        line = reader.readLine();
                    }
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.readFile(schemaGroupsTxt), e);
                }
            }
        }
        tasksProps = new MapPropertyResolver(provisioningProps);
        versionResolver = new MapPropertyResolver(artifactVersions);

        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            processPackages(fp);
        }

        generateConfigs(runtime, messageWriter);

        // TODO this needs to be revisited
        for(FeaturePackRuntime fp : runtime.getFeaturePacks()) {
            final Path finalizeCli = fp.getResource(WfConstants.WILDFLY, WfConstants.SCRIPTS, "finalize.cli");
            if(Files.exists(finalizeCli)) {
                CliScriptRunner.runCliScript(runtime.getStagedDir(), finalizeCli, messageWriter);
            }
        }
    }

    private void generateConfigs(ProvisioningRuntime runtime, final MessageWriter messageWriter) throws ProvisioningException {
        if(!runtime.hasConfigs()) {
            return;
        }
        final Path script = runtime.getTmpPath("wildfly/configs.cli");
        try {
            Files.createDirectories(script.getParent());
        } catch (IOException e1) {
            throw new ProvisioningException(Errors.mkdirs(script.getParent()));
        }

        final WfConfig2EmbeddedScriptHandler configHandler;
        //final WfProvisionedConfigHandler configHandler;
        try(BufferedWriter writer = Files.newBufferedWriter(script)) {
            configHandler = new WfConfig2EmbeddedScriptHandler(runtime, writer);
            //configHandler = new WfProvisionedConfigHandler(runtime, writer);
            for (ProvisionedConfig config : runtime.getConfigs()) {
                if (messageWriter.isVerboseEnabled()) {
                    final StringBuilder msg = new StringBuilder(64).append("Feature config");
                    if (config.getModel() != null) {
                        msg.append(" model=").append(config.getModel());
                    }
                    if (config.getName() != null) {
                        msg.append(" name=").append(config.getName());
                    }
                    messageWriter.verbose(msg);
                    if (config.hasProperties()) {
                        messageWriter.verbose("  properties");
                        for (Map.Entry<String, String> entry : config.getProperties().entrySet()) {
                            messageWriter.verbose("    %s=%s", entry.getKey(), entry.getValue());
                        }
                    }
                }
                config.handle(configHandler);
            }
        } catch (IOException e) {
            throw new ProvisioningException(Errors.writeFile(script), e);
        }

        //CliScriptRunner.runCliScript(runtime.getStagedDir(), script, messageWriter);
        try {
            EmbeddedScriptRunner.run(runtime.getStagedDir(), script);
        } catch (Exception e) {
            throw new ProvisioningException("Failed to generate configs", e);
        }

//        try {
//            IoUtils.copy(script, Paths.get("/home/aloubyansky/pm-scripts/all-configs.cli"));
//        } catch (IOException e) {
//            // TODO Auto-generated catch block
//            e.printStackTrace();
//        }

        configHandler.cleanup();
    }

    private void processPackages(final FeaturePackRuntime fp) throws ProvisioningException {
        for(PackageRuntime pkg : fp.getPackages()) {
            final Path pmWfDir = pkg.getResource(WfConstants.PM, WfConstants.WILDFLY);
            if(!Files.exists(pmWfDir)) {
                continue;
            }

            final Path moduleDir = pmWfDir.resolve(WfConstants.MODULE);
            if(Files.exists(moduleDir)) {
                processModules(fp.getGav(), pkg.getName(), moduleDir);
            }
            final Path tasksXml = pmWfDir.resolve(WfConstants.TASKS_XML);
            if(Files.exists(tasksXml)) {
                final WildFlyPackageTasks pkgTasks = WildFlyPackageTasks.load(tasksXml);
                if(pkgTasks.hasCopyArtifacts()) {
                    copyArtifacts(pkgTasks);
                }
                if(pkgTasks.hasCopyPaths()) {
                    copyPaths(pkgTasks, pmWfDir);
                }
                if(pkgTasks.hasMkDirs()) {
                    mkdirs(pkgTasks, this.runtime.getStagedDir());
                }
                if (pkgTasks.hasFilePermissions() && !PropertyUtils.isWindows()) {
                    processFeaturePackFilePermissions(pkgTasks, this.runtime.getStagedDir());
                }
                if(pkgTasks.hasDeletePaths()) {
                    deletePaths(pkgTasks, pmWfDir);
                }
            }
        }
    }

    private void processModules(ArtifactCoords.Gav fp, String pkgName, Path fpModuleDir) throws ProvisioningException {
        try {
            final Path installDir = runtime.getStagedDir();
            Files.walkFileTree(fpModuleDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                    throws IOException {
                    final Path targetDir = installDir.resolve(fpModuleDir.relativize(dir));
                    try {
                        Files.copy(dir, targetDir);
                    } catch (FileAlreadyExistsException e) {
                         if (!Files.isDirectory(targetDir)) {
                             throw e;
                         }
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                    if(file.getFileName().toString().equals(WfConstants.MODULE_XML)) {
                        processModuleTemplate(fpModuleDir, installDir, file);
                    } else {
                        Files.copy(file, installDir.resolve(fpModuleDir.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ProvisioningException("Failed to process modules from package " + pkgName + " from feature-pack " + fp, e);
        }
    }

    private void processModuleTemplate(Path fpModuleDir, final Path installDir, Path moduleTemplate) throws IOException {
        final Builder builder = new Builder(false);
        final Document document;
        try (BufferedReader reader = Files.newBufferedReader(moduleTemplate, StandardCharsets.UTF_8)) {
            document = builder.build(reader);
        } catch (ParsingException e) {
            throw new IOException("Failed to parse document", e);
        }
        final Path targetPath = installDir.resolve(fpModuleDir.relativize(moduleTemplate));
        final Element rootElement = document.getRootElement();
        if (! rootElement.getLocalName().equals("module")) {
            // just copy the content and leave
            Files.copy(moduleTemplate, targetPath, StandardCopyOption.REPLACE_EXISTING);
            return;
        }
        // replace version, if any
        final Attribute versionAttribute = rootElement.getAttribute("version");
        if (versionAttribute != null) {
            final String versionExpr = versionAttribute.getValue();
            if (versionExpr.startsWith("${") && versionExpr.endsWith("}")) {
                final String exprBody = versionExpr.substring(2, versionExpr.length() - 1);
                final int optionsIndex = exprBody.indexOf('?');
                final String artifactName;
                if (optionsIndex > 0) {
                    artifactName = exprBody.substring(0, optionsIndex);
                } else {
                    artifactName = exprBody;
                }
                final String resolved = versionResolver.resolveProperty(artifactName);
                if (resolved != null) {
                    final ArtifactCoords coords = fromJBossModules(resolved, "jar");
                    versionAttribute.setValue(coords.getVersion());
                }
            }
        }
        // replace all artifact declarations
        final Element resourcesElement = rootElement.getFirstChildElement("resources", rootElement.getNamespaceURI());
        if (resourcesElement != null) {
            final Elements artifacts = resourcesElement.getChildElements("artifact", rootElement.getNamespaceURI());
            final int artifactCount = artifacts.size();
            for (int i = 0; i < artifactCount; i ++) {
                final Element element = artifacts.get(i);
                assert element.getLocalName().equals("artifact");
                final Attribute attribute = element.getAttribute("name");
                final String nameExpr = attribute.getValue();
                if (nameExpr.startsWith("${") && nameExpr.endsWith("}")) {
                    final String exprBody = nameExpr.substring(2, nameExpr.length() - 1);
                    final int optionsIndex = exprBody.indexOf('?');
                    final String artifactName;
                    final boolean jandex;
                    if (optionsIndex >= 0) {
                        artifactName = exprBody.substring(0, optionsIndex);
                        jandex = nameExpr.indexOf("jandex", optionsIndex) >= 0;
                    } else {
                        artifactName = exprBody;
                        jandex = false;
                    }
                    final String resolved = versionResolver.resolveProperty(artifactName);
                    if (resolved != null) {
                        final ArtifactCoords coords = fromJBossModules(resolved, "jar");
                        final Path moduleArtifact;

                        try {
                            moduleArtifact = runtime.resolveArtifact(coords);
                        } catch (ProvisioningException e) {
                            throw new IOException(e);
                        }
                        if (thinServer) {
                            // ignore jandex variable, just resolve coordinates to a string
                            attribute.setValue(resolved);
                        } else {
                            final Path targetDir = installDir.resolve(fpModuleDir.relativize(moduleTemplate.getParent()));
                            final String artifactFileName = moduleArtifact.getFileName().toString();
                            final String finalFileName;

                            if (jandex) {
                                final int lastDot = artifactFileName.lastIndexOf(".");
                                final File target = new File(targetDir.toFile(), new StringBuilder()
                                    .append(artifactFileName.substring(0, lastDot))
                                    .append("-jandex")
                                    .append(artifactFileName.substring(lastDot)).toString()
                                );
                                JandexIndexer.createIndex(moduleArtifact.toFile(), new FileOutputStream(target));
                                finalFileName = target.getName();
                            } else {
                                Files.copy(moduleArtifact, targetDir.resolve(artifactFileName), StandardCopyOption.REPLACE_EXISTING);
                                finalFileName = artifactFileName;
                            }
                            element.setLocalName("resource-root");
                            attribute.setLocalName("path");
                            attribute.setValue(finalFileName);
                        }
                        if (schemaGroups.contains(coords.getGroupId())) {
                            extractSchemas(moduleArtifact);
                        }
                    }
                }
                // if any step fails, don't change anything at all for that artifact
            }
        }
        // now serialize the result
        try (OutputStream outputStream = Files.newOutputStream(targetPath)) {
            new Serializer(outputStream).write(document);
        } catch (Throwable t) {
            try {
                Files.deleteIfExists(targetPath);
            } catch (Throwable t2) {
                t2.addSuppressed(t);
                throw t2;
            }
            throw t;
        }
    }

    private void extractSchemas(Path moduleArtifact) throws IOException {
        final Path targetSchemasDir = this.runtime.getStagedDir().resolve(WfConstants.DOCS).resolve(WfConstants.SCHEMA);
        Files.createDirectories(targetSchemasDir);
        try (FileSystem jarFS = FileSystems.newFileSystem(moduleArtifact, null)) {
            final Path schemaSrc = jarFS.getPath(WfConstants.SCHEMA);
            if (Files.exists(schemaSrc)) {
                ZipUtils.copyFromZip(schemaSrc.toAbsolutePath(), targetSchemasDir);
            }
        }
    }

    private void copyArtifacts(final WildFlyPackageTasks tasks) throws ProvisioningException {
        for(CopyArtifact copyArtifact : tasks.getCopyArtifacts()) {
            final String gavString = versionResolver.resolveProperty(copyArtifact.getArtifact());
            try {
                final ArtifactCoords coords = fromJBossModules(gavString, "jar");
                final Path jarSrc = runtime.resolveArtifact(coords);
                String location = copyArtifact.getToLocation();
                if (!location.isEmpty() && location.charAt(location.length() - 1) == '/') {
                    // if the to location ends with a / then it is a directory
                    // so we need to append the artifact name
                    location += jarSrc.getFileName();
                }

                final Path jarTarget = runtime.getStagedDir().resolve(location);

                Files.createDirectories(jarTarget.getParent());
                if (copyArtifact.isExtract()) {
                    extractArtifact(jarSrc, jarTarget, copyArtifact);
                } else {
                    IoUtils.copy(jarSrc, jarTarget);
                }
                runtime.getMessageWriter().verbose("    Copying artifact %s to %s", jarSrc, jarTarget);
                if(schemaGroups.contains(coords.getGroupId())) {
                    extractSchemas(jarSrc);
                }
            } catch (IOException e) {
                throw new ProvisioningException("Failed to copy artifact " + gavString, e);
            }
        }
    }

    private void copyPaths(final WildFlyPackageTasks tasks, final Path pmWfDir) throws ProvisioningException {
        for(CopyPath copyPath : tasks.getCopyPaths()) {
            final Path src = pmWfDir.resolve(copyPath.getSrc());
            if (!Files.exists(src)) {
                throw new ProvisioningException(Errors.pathDoesNotExist(src));
            }
            final Path target = copyPath.getTarget() == null ? runtime.getStagedDir() : runtime.getStagedDir().resolve(copyPath.getTarget());
            if (copyPath.isReplaceProperties()) {
                if (!Files.exists(target.getParent())) {
                    try {
                        Files.createDirectories(target.getParent());
                    } catch (IOException e) {
                        throw new ProvisioningException(Errors.mkdirs(target.getParent()), e);
                    }
                }
                try {
                    Files.walkFileTree(src, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                            new SimpleFileVisitor<Path>() {
                                @Override
                                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                                    final Path targetDir = target.resolve(src.relativize(dir));
                                    try {
                                        Files.copy(dir, targetDir);
                                    } catch (FileAlreadyExistsException e) {
                                        if (!Files.isDirectory(targetDir)) {
                                            throw e;
                                        }
                                    }
                                    return FileVisitResult.CONTINUE;
                                }

                                @Override
                                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                                    PropertyReplacer.copy(file, target.resolve(src.relativize(file)), tasksProps);
                                    return FileVisitResult.CONTINUE;
                                }
                            });
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.copyFile(src, target), e);
                }
            } else {
                try {
                    IoUtils.copy(src, target);
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.copyFile(src, target));
                }
            }
        }
    }

    private void deletePaths(final WildFlyPackageTasks tasks, final Path pmWfDir) throws ProvisioningException {
        for(DeletePath deletePath : tasks.getDeletePaths()) {
            final Path path = runtime.getStagedDir().resolve(deletePath.getPath());
            if (!Files.exists(path)) {
//                throw new ProvisioningException(Errors.pathDoesNotExist(path));
                continue;
            }
            if(deletePath.isRecursive()) {
                IoUtils.recursiveDelete(path);
            } else {
                try {
                    Files.delete(path);
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.deletePath(path), e);
                }
            }
        }
    }

    private static void extractArtifact(Path artifact, Path target, CopyArtifact copy) throws IOException {
        if(!Files.exists(target)) {
            Files.createDirectories(target);
        }
        try (FileSystem zipFS = FileSystems.newFileSystem(artifact, null)) {
            for(Path zipRoot : zipFS.getRootDirectories()) {
                Files.walkFileTree(zipRoot, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                        new SimpleFileVisitor<Path>() {
                            @Override
                            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                                throws IOException {
                                String entry = dir.toString().substring(1);
                                if(entry.isEmpty()) {
                                    return FileVisitResult.CONTINUE;
                                }
                                if(!entry.endsWith("/")) {
                                    entry += '/';
                                }
                                if(!copy.includeFile(entry)) {
                                    return FileVisitResult.SKIP_SUBTREE;
                                }
                                final Path targetDir = target.resolve(zipRoot.relativize(dir).toString());
                                try {
                                    Files.copy(dir, targetDir);
                                } catch (FileAlreadyExistsException e) {
                                     if (!Files.isDirectory(targetDir))
                                         throw e;
                                }
                                return FileVisitResult.CONTINUE;
                            }
                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                                throws IOException {
                                if(copy.includeFile(file.toString().substring(1))) {
                                    Files.copy(file, target.resolve(zipRoot.relativize(file).toString()));
                                }
                                return FileVisitResult.CONTINUE;
                            }
                        });
            }
        }
    }

    private static void mkdirs(final WildFlyPackageTasks tasks, Path installDir) throws ProvisioningException {
        // make dirs
        for (String dirName : tasks.getMkDirs()) {
            final Path dir = installDir.resolve(dirName);
            if(!Files.exists(dir)) {
                try {
                    Files.createDirectories(dir);
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.mkdirs(dir));
                }
            }
        }
    }

    private static void processFeaturePackFilePermissions(WildFlyPackageTasks tasks, Path installDir) throws ProvisioningException {
        final List<FilePermission> filePermissions = tasks.getFilePermissions();
        try {
            Files.walkFileTree(installDir, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    final String relative = installDir.relativize(dir).toString();
                    for (FilePermission perm : filePermissions) {
                        if (perm.includeFile(relative)) {
                            Files.setPosixFilePermissions(dir, perm.getPermission());
                            continue;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    final String relative = installDir.relativize(file).toString();
                    for (FilePermission perm : filePermissions) {
                        if (perm.includeFile(relative)) {
                            Files.setPosixFilePermissions(file, perm.getPermission());
                            continue;
                        }
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new ProvisioningException("Failed to set file permissions", e);
        }
    }

    private static ArtifactCoords fromJBossModules(String str, String extension) {
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
}

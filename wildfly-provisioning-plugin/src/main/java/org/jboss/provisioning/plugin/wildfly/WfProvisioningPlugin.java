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
package org.jboss.provisioning.plugin.wildfly;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.DirectoryStream;
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
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.Constants;
import org.jboss.provisioning.Errors;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.plugin.ProvisioningContext;
import org.jboss.provisioning.plugin.ProvisioningPlugin;
import org.jboss.provisioning.plugin.wildfly.config.CopyArtifact;
import org.jboss.provisioning.plugin.wildfly.config.FilePermission;
import org.jboss.provisioning.plugin.wildfly.config.GeneratorConfig;
import org.jboss.provisioning.plugin.wildfly.config.WildFlyPackageTasks;
import org.jboss.provisioning.state.ProvisionedFeaturePack;
import org.jboss.provisioning.util.IoUtils;
import org.jboss.provisioning.util.LayoutUtils;
import org.jboss.provisioning.util.PropertyUtils;
import org.jboss.provisioning.util.ZipUtils;

/**
 *
 * @author Alexey Loubyansky
 */
public class WfProvisioningPlugin implements ProvisioningPlugin {


    private ProvisioningContext ctx;
    private PropertyResolver versionResolver;
    private BuildPropertyHandler propertyHandler;

    private Properties tasksProps;

    private boolean thinServer;
    private Set<String> schemaGroups = Collections.emptySet();

    private StandaloneConfigGenerator standaloneGenerator;
    private DomainConfigGenerator domainScriptCollector;


    /* (non-Javadoc)
     * @see org.jboss.provisioning.util.plugin.ProvisioningPlugin#execute()
     */
    @Override
    public void postInstall(ProvisioningContext ctx) throws ProvisioningException {

        System.out.println("WildFly provisioning plug-in");

        final String thinServerProp = System.getProperty("wfThinServer");
        if(thinServerProp != null) {
            if(thinServerProp.isEmpty()) {
                thinServer = true;
            } else {
                thinServer = Boolean.parseBoolean(thinServerProp);
            }
        }

        this.ctx = ctx;

        final Map<String, String> artifactVersions = new HashMap<>();
        for(ArtifactCoords.Gav fpGav : ctx.getProvisionedState().getFeaturePackGavs()) {
            final Path wfRes = LayoutUtils.getFeaturePackDir(ctx.getLayoutDir(), fpGav).resolve(Constants.RESOURCES).resolve(WfConstants.WILDFLY);
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
                tasksProps = tasksProps == null ? new Properties() : new Properties(tasksProps);
                try(InputStream in = Files.newInputStream(tasksPropsPath)) {
                    tasksProps.load(in);
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.readFile(tasksPropsPath), e);
                }
            } else {
                tasksProps = new Properties();
            }
        }
        versionResolver = new MapPropertyResolver(artifactVersions);
        propertyHandler = new BuildPropertyHandler(versionResolver);

        processPackages();

        if(domainScriptCollector != null) {
            domainScriptCollector.run();
        }
    }

    private void processPackages() throws ProvisioningException {
        try(DirectoryStream<Path> groupDtream = Files.newDirectoryStream(ctx.getLayoutDir())) {
            for(Path groupId : groupDtream) {
                try(DirectoryStream<Path> artifactStream = Files.newDirectoryStream(groupId)) {
                    for(Path artifactId : artifactStream) {
                        try(DirectoryStream<Path> versionStream = Files.newDirectoryStream(artifactId)) {
                            int count = 0;
                            for(Path version : versionStream) {
                                final ArtifactCoords.Gav fpGav = ArtifactCoords.newGav(groupId.getFileName().toString(), artifactId.getFileName().toString(), version.getFileName().toString());
                                if(++count > 1) {
                                    throw new ProvisioningException("There is more than one version of feature-pack " + fpGav.toGa());
                                }
                                processPackages(ctx.getProvisionedState().getFeaturePack(fpGav), version);
                            }
                        } catch (IOException e) {
                            throw new ProvisioningException(Errors.readDirectory(artifactId), e);
                        }
                    }
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.readDirectory(groupId), e);
                }
            }
        } catch (IOException e) {
            throw new ProvisioningException(Errors.readDirectory(ctx.getLayoutDir()), e);
        }
    }

    private void processPackages(final ProvisionedFeaturePack provisionedFp, Path fpDir) throws ProvisioningException {
        final Path packagesDir = fpDir.resolve(Constants.PACKAGES);
        if(!Files.exists(packagesDir)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(packagesDir));
        }
        if(provisionedFp.containsPackage("docs.schemas")) {
            final Path schemaGroupsTxt = LayoutUtils.getPackageDir(fpDir, "docs.schemas", true)
                    .resolve(WfConstants.PM).resolve(WfConstants.WILDFLY).resolve("schema-groups.txt");
            if(Files.exists(schemaGroupsTxt)) {
                try(BufferedReader reader = Files.newBufferedReader(schemaGroupsTxt)) {
                    final String line = reader.readLine();
                    switch(schemaGroups.size()) {
                        case 0:
                            schemaGroups = Collections.singleton(line);
                            break;
                        case 1:
                            schemaGroups = new HashSet<>(schemaGroups);
                        default:
                            schemaGroups.add(line);
                    }
                } catch (IOException e) {
                    throw new ProvisioningException(Errors.readFile(schemaGroupsTxt), e);
                }
            }
        }

        for(String pkgName : provisionedFp.getPackageNames()) {
            final Path pmWfDir = packagesDir.resolve(pkgName).resolve(WfConstants.PM).resolve(WfConstants.WILDFLY);
            if(!Files.exists(pmWfDir)) {
                continue;
            }
            final Path moduleDir = pmWfDir.resolve(WfConstants.MODULE);
            if(Files.exists(moduleDir)) {
                processModules(provisionedFp.getGav(), pkgName, moduleDir);
            }
            final Path tasksXml = pmWfDir.resolve(WfConstants.TASKS_XML);
            if(Files.exists(tasksXml)) {
                final WildFlyPackageTasks pkgTasks = WildFlyPackageTasks.load(tasksXml, tasksProps);
                if(pkgTasks.hasCopyArtifacts()) {
                    copyArtifacts(pkgTasks);
                }
                if(pkgTasks.hasMkDirs()) {
                    mkdirs(pkgTasks, ctx.getInstallDir());
                }
                if (pkgTasks.hasFilePermissions() && !PropertyUtils.isWindows()) {
                    processFeaturePackFilePermissions(pkgTasks, ctx.getInstallDir());
                }
                final GeneratorConfig genConfig = pkgTasks.getGeneratorConfig();
                if(genConfig != null) {
                    if(genConfig.hasStandaloneConfig()) {
                        if(standaloneGenerator == null) {
                            standaloneGenerator = new StandaloneConfigGenerator(ctx);
                        }
                        standaloneGenerator.init(genConfig.getStandaloneConfig().getServerConfig());
                        standaloneGenerator.collectScripts(provisionedFp, pkgName, null);
                        standaloneGenerator.run();
                    }
                    if(genConfig.hasDomainProfile()) {
                        if(domainScriptCollector == null) {
                            domainScriptCollector = new DomainConfigGenerator(ctx);
                        }
                        domainScriptCollector.collectScripts(provisionedFp, pkgName, genConfig.getDomainProfileConfig().getProfile());
                    }
                }
            }
        }
    }

    private void processModules(ArtifactCoords.Gav fp, String pkgName, Path fpModuleDir) throws ProvisioningException {
        try {
            final Path installDir = ctx.getInstallDir();
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
                        IoUtils.writeFile(installDir.resolve(fpModuleDir.relativize(file)), processModule(fpModuleDir, installDir, file));
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

    private String processModule(Path fpModuleDir, final Path installDir, Path file) throws IOException {
        final String originalContent = IoUtils.readFile(file);
        final String resolvedContent;
        if(thinServer) {
            final PropertyResolver propertyResolver;
            if(schemaGroups.isEmpty()) {
                propertyResolver = propertyHandler.getPropertyResolver();
            } else {
                final PropertyResolver targetResolver = propertyHandler.getPropertyResolver();
                propertyResolver = new PropertyResolver() {
                    @Override
                    public String resolveProperty(String property) {
                        final String resolved = targetResolver.resolveProperty(property);
                        if(resolved == null) {
                            return null;
                        }
                        final ArtifactCoords coords = fromJBossModules(resolved, "jar");
                        if(schemaGroups.contains(coords.getGroupId())) {
                            try {
                                extractSchemas(ctx.resolveArtifact(coords));
                            } catch (ProvisioningException e) {
                                throw new IllegalStateException(e);
                            }
                        }
                        return resolved;
                    }
                };
            }
            try {
                resolvedContent = propertyHandler.replaceProperties(originalContent, propertyResolver);
            } catch (Throwable t) {
                throw new IOException("Failed to replace properties for " + file, t);
            }
        } else {
            final String[] finalContent = new String[]{originalContent};
            propertyHandler.handlePoperties(originalContent, new PropertyResolver() {
                @Override
                public String resolveProperty(final String property) {
                    final int optionsIndex = property.indexOf('?');
                    final String artifactName;
                    final String expr;
                    if(optionsIndex > 0) {
                        // TODO handle options
                        artifactName = property.substring(0, optionsIndex);
                        expr = artifactName + '\\' + property.substring(optionsIndex);
                    } else {
                        artifactName = property;
                        expr = property;
                    }
                    final String resolved = versionResolver.resolveProperty(artifactName);
                    if(resolved == null) {
                        return null;
                    }
                    final ArtifactCoords coords = fromJBossModules(resolved, "jar");
                    final Path moduleArtifact;
                    try {
                        moduleArtifact = ctx.resolveArtifact(coords);
                        if(schemaGroups.contains(coords.getGroupId())) {
                            extractSchemas(moduleArtifact);
                        }
                    } catch (ProvisioningException e) {
                        throw new IllegalStateException(e);
                    }
                    final String artifactFileName = moduleArtifact.getFileName().toString();
                    try {
                        IoUtils.copy(moduleArtifact, installDir.resolve(fpModuleDir.relativize(file.getParent())).resolve(artifactFileName));
                    } catch (IOException e) {
                        throw new IllegalStateException(e);
                    }
                    // update module xml content
                    finalContent[0] = finalContent[0].replaceFirst("<artifact\\s+name=\"\\$\\{" + expr
                          + "\\}\"\\s*/>", "<resource-root path=\"" + artifactFileName + "\"/>");
                    // it's also possible that this is an element with nested content
                    // this regex involves a good deal of backtracking but it seems to work
                    finalContent[0] = Pattern.compile(
                            "<artifact\\s+name=\"\\$\\{" + expr + "\\}\"\\s*>(.*)</artifact>",
                            Pattern.DOTALL)
                            .matcher(finalContent[0])
                            .replaceFirst("<resource-root path=\"" + artifactFileName + "\">$1</resource-root>");

                    return resolved;
                }
            });
            resolvedContent = finalContent[0];
        }
        return resolvedContent;
    }

    private void extractSchemas(Path moduleArtifact) throws ProvisioningException {
        final Path targetSchemasDir = this.ctx.getInstallDir().resolve(WfConstants.DOCS).resolve("schemas");
        try {
            Files.createDirectories(targetSchemasDir);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.mkdirs(targetSchemasDir), e);
        }
        try (final FileSystem jarFS = FileSystems.newFileSystem(moduleArtifact, null)) {
            final Path schemaSrc = jarFS.getPath(WfConstants.SCHEMA);
            if (Files.exists(schemaSrc)) {
                ZipUtils.copyFromZip(schemaSrc.toAbsolutePath(), targetSchemasDir);
            }
        } catch (IOException e) {
            throw new ProvisioningException(Errors.readFile(moduleArtifact), e);
        }
    }

    private void copyArtifacts(final WildFlyPackageTasks tasks) throws ProvisioningException {
        for(CopyArtifact copyArtifact : tasks.getCopyArtifacts()) {
            final String gavString = versionResolver.resolveProperty(copyArtifact.getArtifact());
            try {
                final Path jarSrc = ctx.resolveArtifact(fromJBossModules(gavString, "jar"));
                String location = copyArtifact.getToLocation();
                if (!location.isEmpty() && location.charAt(location.length() - 1) == '/') {
                    // if the to location ends with a / then it is a directory
                    // so we need to append the artifact name
                    location += jarSrc.getFileName();
                }

                final Path jarTarget = ctx.getInstallDir().resolve(location);

                Files.createDirectories(jarTarget.getParent());
                if (copyArtifact.isExtract()) {
                    extractArtifact(jarSrc, jarTarget, copyArtifact);
                } else {
                    IoUtils.copy(jarSrc, jarTarget);
                }
            } catch (IOException e) {
                throw new ProvisioningException("Failed to copy artifact " + gavString, e);
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
                                final String entry = dir.toString().substring(1);
                                if(entry.isEmpty()) {
                                    return FileVisitResult.CONTINUE;
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

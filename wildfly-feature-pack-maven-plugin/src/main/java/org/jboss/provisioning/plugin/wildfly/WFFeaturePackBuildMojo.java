/*
 * JBoss, Home of Professional Open Source.
 * Copyright 2016, Red Hat, Inc., and individual contributors
 * as indicated by the @author tags. See the copyright.txt file in the
 * distribution for a full listing of individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

package org.jboss.provisioning.plugin.wildfly;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.List;
import java.util.Properties;

import javax.xml.stream.XMLStreamException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.plugins.annotations.ResolutionScope;
import org.apache.maven.project.MavenProject;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.RepositorySystemSession;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.installation.InstallationException;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResolutionException;
import org.eclipse.aether.resolution.ArtifactResult;
import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.Constants;
import org.jboss.provisioning.Errors;
import org.jboss.provisioning.GAV;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.descr.FeaturePackDependencyDescription;
import org.jboss.provisioning.descr.FeaturePackDescription;
import org.jboss.provisioning.descr.FeaturePackDescription.Builder;
import org.jboss.provisioning.descr.PackageDescription;
import org.jboss.provisioning.plugin.FPMavenErrors;
import org.jboss.provisioning.plugin.util.MavenPluginUtil;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.WildFlyPostFeaturePackTasks;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.WildFlyPostFeaturePackTasksWriter20;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.build.CopyArtifact;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.build.WildFlyFeaturePackBuild;
import org.jboss.provisioning.util.IoUtils;
import org.jboss.provisioning.util.PropertyUtils;
import org.jboss.provisioning.util.ZipUtils;
import org.jboss.provisioning.xml.FeaturePackXMLWriter;
import org.jboss.provisioning.xml.PackageXMLWriter;

/**
 * This plugin builds a WildFly feature-pack by organizing the content into packages.
 * The artifact versions are resolved here. The configuration pieces are copied into
 * the feature-pack resources directory and will be assembled at the provisioning time.
 *
 * @author Alexey Loubyansky
 */
@Mojo(name = "wf-build", requiresDependencyResolution = ResolutionScope.RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class WFFeaturePackBuildMojo extends AbstractMojo {

    private static final boolean OS_WINDOWS = PropertyUtils.isWindows();

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    protected MavenProject project;

    @Component
    private RepositorySystem repoSystem;

    @Parameter(defaultValue = "${repositorySystemSession}", readonly = true)
    private RepositorySystemSession repoSession;

    @Parameter(defaultValue = "${project.remoteProjectRepositories}", readonly = true)
    private List<RemoteRepository> remoteRepos;

    /**
     * The configuration file used for feature pack.
     */
    @Parameter(alias = "config-file", defaultValue = "wildfly-feature-pack-build.xml", property = "wildfly.feature.pack.configFile")
    private String configFile;

    /**
     * The directory the configuration file is located in.
     */
    @Parameter(alias = "config-dir", defaultValue = "${basedir}", property = "wildfly.feature.pack.configDir")
    private File configDir;

    /**
     * A path relative to {@link #configDir} that represents the directory under which of resources such as
     * {@code configuration/standalone/subsystems.xml}, {modules}, {subsystem-templates}, etc.
     */
    @Parameter(alias = "resources-dir", defaultValue = "src/main/resources", property = "wildfly.feature.pack.resourcesDir", required = true)
    private String resourcesDir;

    /**
     * The name of the server.
     */
    @Parameter(alias = "server-name", defaultValue = "${project.build.finalName}", property = "wildfly.feature.pack.serverName")
    private String serverName;

    /**
     * The directory for the built artifact.
     */
    @Parameter(defaultValue = "${project.build.directory}", property = "wildfly.feature.pack.buildName")
    private String buildName;

    private MavenProjectArtifactVersions artifactVersions;

    //private final ZipFileSubsystemInputStreamSources subsystemsInput = new ZipFileSubsystemInputStreamSources();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {

        artifactVersions = MavenProjectArtifactVersions.getInstance(project);

        /* normalize resourcesDir */
        if (!resourcesDir.isEmpty()) {
            switch (resourcesDir.charAt(0)) {
            case '/':
            case '\\':
                break;
            default:
                resourcesDir = "/" + resourcesDir;
                break;
            }
        }

        final Path targetResources = Paths.get(buildName, "resources");
        try {
            IoUtils.copy(Paths.get(configDir.getAbsolutePath() + resourcesDir), targetResources);
        } catch (IOException e1) {
            throw new MojoExecutionException(Errors.copyFile(Paths.get(configDir.getAbsolutePath() + resourcesDir), targetResources));
        }

        final Path workDir = Paths.get(buildName, "layout");
        System.out.println("WFFeaturePackBuildMojo.execute " + workDir);
        IoUtils.recursiveDelete(workDir);
        final String fpArtifactId = project.getArtifactId() + "-new";
        final Path fpDir = workDir.resolve(project.getGroupId()).resolve(fpArtifactId).resolve(project.getVersion());
        final Path fpPackagesDir = fpDir.resolve(Constants.PACKAGES);

        // feature-pack builder
        final Builder fpBuilder = FeaturePackDescription.builder(new GAV(project.getGroupId(), fpArtifactId, project.getVersion()));

        // feature-pack build config
        WildFlyFeaturePackBuild build;
        try {
            build = Util.loadFeaturePackBuildConfig(getFPConfigFile(), getFPConfigProperties());
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to load feature-pack config file", e);
        }
        processFeaturePackDependencies(fpBuilder, build);
        copyArtifacts(targetResources, build);

        final Path srcModulesDir = targetResources.resolve("modules").resolve("system").resolve("layers").resolve("base");
        if(!Files.exists(srcModulesDir)) {
            throw new MojoExecutionException(Errors.pathDoesNotExist(srcModulesDir));
        }
        final PackageDescription.Builder modulesBuilder = PackageDescription.builder("modules");
        try {
            packageModules(fpBuilder, modulesBuilder, build, targetResources, srcModulesDir, fpPackagesDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process modules content", e);
        }

        final PackageDescription modulesPkg = modulesBuilder.build();
        writeXml(modulesPkg, fpDir.resolve(Constants.PACKAGES).resolve(modulesPkg.getName()));

        try {
            packageContent(fpBuilder, targetResources.resolve(Constants.CONTENT), fpPackagesDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process content", e);
        }

        fpBuilder.addProvisioningPlugin(new GAV("org.jboss.pm", "wildfly-feature-pack-maven-plugin", "1.0.0.Alpha-SNAPSHOT"));

        final FeaturePackDescription fpDescr = fpBuilder.addTopPackage(modulesPkg).build();
        try {
            FeaturePackXMLWriter.INSTANCE.write(fpDescr, fpDir.resolve(Constants.FEATURE_PACK_XML));
        } catch (XMLStreamException | IOException e) {
            throw new MojoExecutionException(Errors.writeXml(fpDir.resolve(Constants.FEATURE_PACK_XML)));
        }

        // collect feature-pack resources
        final Path resourcesWildFly = fpDir.resolve("resources").resolve("wildfly");
        try {
            IoUtils.copy(targetResources.resolve("configuration"), resourcesWildFly.resolve("configuration"));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to copy configuration files to feature-pack resources", e);
        }

        // properties
        try(OutputStream out = Files.newOutputStream(resourcesWildFly.resolve("wildfly-tasks.properties"))) {
                getFPConfigProperties().store(out, "WildFly feature-pack properties");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to store feature-pack properties", e);
        }

        // post feature-pack tasks config
        final WildFlyPostFeaturePackTasks tasks = WildFlyPostFeaturePackTasks.builder()
                .setConfig(build.getConfig())
                .addFilePermissions(build.getFilePermissions())
                .addMkDirs(build.getMkDirs())
                .addUnixLineEndFilters(build.getUnixLineEndFilters())
                .addWindowsLineEndFilters(build.getWindowsLineEndFilters())
                .build();
        try {
            WildFlyPostFeaturePackTasksWriter20.INSTANCE.write(tasks, resourcesWildFly.resolve("wildfly-tasks.xml"));
        } catch (XMLStreamException | IOException e) {
            throw new MojoExecutionException(Errors.writeXml(resourcesWildFly.resolve("wildfly-tasks.xml")), e);
        }

        try {
            repoSystem.install(repoSession, MavenPluginUtil.getInstallLayoutRequest(workDir));
        } catch (InstallationException e) {
            throw new MojoExecutionException(FPMavenErrors.featurePackInstallation(), e);
        }
    }

    private void copyArtifacts(final Path targetResources, final WildFlyFeaturePackBuild build) throws MojoExecutionException {
        for(CopyArtifact copyArtifact : build.getCopyArtifacts()) {
            final String gavString = artifactVersions.getVersion(copyArtifact.getArtifact());
            try {
                final ArtifactCoords coords = ArtifactCoordsUtil.fromJBossModules(gavString, "jar");
                final Path jarSrc = resolveArtifact(coords);
                String location = copyArtifact.getToLocation();
                if (location.endsWith("/")) {
                    // if the to location ends with a / then it is a directory
                    // so we need to append the artifact name
                    location += jarSrc.getFileName();
                }

                Path jarTarget = targetResources;
                if(!location.startsWith("modules/")) {
                    jarTarget = jarTarget.resolve("content");
                }
                jarTarget = jarTarget.resolve(location);

                Files.createDirectories(jarTarget.getParent());
                if (copyArtifact.isExtract()) {
                    extractArtifact(jarSrc, jarTarget, copyArtifact);
                } else {
                    IoUtils.copy(jarSrc, jarTarget);
                }

                extractSchemas(build, coords.getGroupId(), jarSrc);
            } catch (ProvisioningException | IOException e) {
                throw new MojoExecutionException("Failed to copy artifact " + gavString, e);
            }
        }
    }

    private void extractSchemas(WildFlyFeaturePackBuild build, String groupId, Path jar) {
        if(build.isPackageSchemas() && build.isSchemaGroup(groupId)) {
        // TODO
        }
    }

    private void extractArtifact(Path artifact, Path target, CopyArtifact copy) throws IOException {
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

    private void processFeaturePackDependencies(final Builder fpBuilder, final WildFlyFeaturePackBuild build)
            throws MojoExecutionException {
        if (!build.getDependencies().isEmpty()) {
            for (FeaturePackDependencyDescription dep : build.getDependencies()) {
                final String depStr = dep.getGAV().toString();
                String gavStr = artifactVersions.getVersion(depStr);
                gavStr = gavStr.replace(depStr, depStr + "-new");
                final GAV gav = GAV.fromString(gavStr);
                fpBuilder.addDependency(FeaturePackDependencyDescription.builder(gav).build());
            }
        }
    }

    private void packageContent(FeaturePackDescription.Builder fpBuilder, Path contentDir, Path packagesDir) throws IOException, MojoExecutionException {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(contentDir)) {
            for(Path p : stream) {
                final String pkgName = p.getFileName().toString();
                final Path pkgDir = packagesDir.resolve(pkgName);
                IoUtils.copy(p, pkgDir.resolve(Constants.CONTENT).resolve(p.getFileName()));
                final PackageDescription pkgDescr = PackageDescription.builder(pkgName).build();
                writeXml(pkgDescr, pkgDir);
                fpBuilder.addTopPackage(pkgDescr);
            }
        }
    }

    private void packageModules(FeaturePackDescription.Builder fpBuilder, PackageDescription.Builder modulesBuilder,
            final WildFlyFeaturePackBuild wfFpConfig, Path resourcesDir, Path modulesDir, Path packagesDir) throws IOException {
        final BuildPropertyReplacer buildPropertyReplacer = new BuildPropertyReplacer(new ModuleArtifactPropertyResolver(artifactVersions));
        Files.walkFileTree(modulesDir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                    return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                if(!file.getFileName().toString().equals("module.xml")) {
                    return FileVisitResult.CONTINUE;
                }
                final String packageName = modulesDir.relativize(file.getParent()).toString().replace(File.separatorChar, '.');
                final Path packageDir = packagesDir.resolve(packageName);
                final Path targetXml = packageDir.resolve(Constants.CONTENT).resolve(resourcesDir.relativize(file));
                Files.createDirectories(targetXml.getParent());

                IoUtils.copy(file.getParent(), targetXml.getParent());

                final PackageDescription pkgDescr = PackageDescription.builder(packageName).build();
                try {
                    PackageXMLWriter.INSTANCE.write(pkgDescr, packageDir.resolve(Constants.PACKAGE_XML));
                } catch (XMLStreamException e) {
                    throw new IOException(Errors.writeXml(packageDir.resolve(Constants.PACKAGE_XML)), e);
                }
                modulesBuilder.addDependency(packageName);
                fpBuilder.addPackage(pkgDescr);

                final String moduleXmlContents = IoUtils.readFile(file);
                String targetContent;
                try {
                    targetContent = buildPropertyReplacer.replaceProperties(moduleXmlContents);
                } catch(Throwable t) {
                    throw new IOException("Failed to replace properties for " + file, t);
                }
                IoUtils.writeFile(targetXml, targetContent);

                // extract schemas
                if(wfFpConfig.isPackageSchemas()) {
                    Util.processModuleArtifacts(targetXml, coords -> {
                        if (wfFpConfig.isSchemaGroup(coords.getGroupId())) {
                            final Path artifactFile;
                            try {
                                artifactFile = resolveArtifact(coords);
                            } catch(ProvisioningException e) {
                                throw new IOException(FPMavenErrors.artifactResolution(coords), e);
                            }
                            final FileSystem jarFS = FileSystems.newFileSystem(artifactFile, null);
                            final Path schemaSrc = jarFS.getPath("schema");
                            if (Files.exists(schemaSrc)) {
                                ZipUtils.copyFromZip(schemaSrc.toAbsolutePath(), resourcesDir.resolve("content").resolve("docs").resolve("schema"));
                            }
                        }
                    });
                }

                if (!OS_WINDOWS) {
                    Files.setPosixFilePermissions(targetXml, Files.getPosixFilePermissions(file));
                }

                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                return FileVisitResult.TERMINATE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Properties getFPConfigProperties() {
        final Properties properties = new Properties();
        properties.putAll(project.getProperties());
        properties.put("project.version", project.getVersion()); //TODO: figure out the correct way to do this
        return properties;
    }

    private Path getFPConfigFile() throws ProvisioningException {
        final Path path = Paths.get(configDir.getAbsolutePath(), configFile);
        if(!Files.exists(path)) {
            throw new ProvisioningException(Errors.pathDoesNotExist(path));
        }
        return path;
    }

    private static void writeXml(PackageDescription pkgDescr, Path dir) throws MojoExecutionException {
        try {
            Files.createDirectories(dir);
            PackageXMLWriter.INSTANCE.write(pkgDescr, dir.resolve(Constants.PACKAGE_XML));
        } catch (XMLStreamException | IOException e) {
            throw new MojoExecutionException(Errors.writeXml(dir.resolve(Constants.PACKAGE_XML)), e);
        }
    }

    private Path resolveArtifact(ArtifactCoords coords) throws ProvisioningException {
        final ArtifactResult result;
        try {
            result = repoSystem.resolveArtifact(repoSession, getArtifactRequest(coords));
        } catch (ArtifactResolutionException e) {
            throw new ProvisioningException(FPMavenErrors.artifactResolution(coords), e);
        }
        if(!result.isResolved()) {
            throw new ProvisioningException(FPMavenErrors.artifactResolution(coords));
        }
        if(result.isMissing()) {
            throw new ProvisioningException(FPMavenErrors.artifactMissing(coords));
        }
        return Paths.get(result.getArtifact().getFile().toURI());
    }

    private ArtifactRequest getArtifactRequest(ArtifactCoords coords) {
        final ArtifactRequest req = new ArtifactRequest();
        req.setArtifact(new DefaultArtifact(coords.getGroupId(), coords.getArtifactId(), coords.getClassifier(), coords.getExtension(), coords.getVersion()));
        req.setRepositories(remoteRepos);
        return req;
    }
}

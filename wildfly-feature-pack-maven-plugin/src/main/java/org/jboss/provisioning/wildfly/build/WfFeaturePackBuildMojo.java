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
package org.jboss.provisioning.wildfly.build;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.jboss.provisioning.ProvisioningDescriptionException;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.config.FeaturePackConfig;
import org.jboss.provisioning.plugin.FpMavenErrors;
import org.jboss.provisioning.plugin.util.MavenPluginUtil;
import org.jboss.provisioning.plugin.wildfly.WfConstants;
import org.jboss.provisioning.spec.FeaturePackDependencySpec;
import org.jboss.provisioning.spec.FeaturePackSpec;
import org.jboss.provisioning.spec.PackageSpec;
import org.jboss.provisioning.spec.FeaturePackSpec.Builder;
import org.jboss.provisioning.util.FeaturePackLayoutDescriber;
import org.jboss.provisioning.util.IoUtils;
import org.jboss.provisioning.util.PropertyUtils;
import org.jboss.provisioning.wildfly.build.ModuleParseResult.ModuleDependency;
import org.jboss.provisioning.xml.FeaturePackXmlWriter;
import org.jboss.provisioning.xml.PackageXmlParser;
import org.jboss.provisioning.xml.PackageXmlWriter;

/**
 * This plug-in builds a WildFly feature-pack arranging the content by packages.
 * The artifact versions are resolved here. The configuration pieces are copied into
 * the feature-pack resources directory and will be assembled at the provisioning time.
 *
 * @author Alexey Loubyansky
 */
@Mojo(name = "wf-build", requiresDependencyResolution = ResolutionScope.RUNTIME, defaultPhase = LifecyclePhase.COMPILE)
public class WfFeaturePackBuildMojo extends AbstractMojo {

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

    private WildFlyFeaturePackBuild wfFpConfig;
    private Map<String, FeaturePackSpec> fpDependencies = Collections.emptyMap();

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        try {
            doExecute();
        } catch(RuntimeException | Error | MojoExecutionException | MojoFailureException e) {
            e.printStackTrace();
            throw e;
        }
    }

    private void doExecute() throws MojoExecutionException, MojoFailureException {
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
        final Path targetResources = Paths.get(buildName, Constants.RESOURCES);
        try {
            IoUtils.copy(Paths.get(configDir.getAbsolutePath() + resourcesDir), targetResources);
        } catch (IOException e1) {
            throw new MojoExecutionException(Errors.copyFile(Paths.get(configDir.getAbsolutePath()).resolve(resourcesDir), targetResources), e1);
        }

        final Path workDir = Paths.get(buildName, WfConstants.LAYOUT);
        //System.out.println("WfFeaturePackBuildMojo.execute " + workDir);
        IoUtils.recursiveDelete(workDir);
        final String fpArtifactId = project.getArtifactId() + "-new";
        final Path fpDir = workDir.resolve(project.getGroupId()).resolve(fpArtifactId).resolve(project.getVersion());
        final Path fpPackagesDir = fpDir.resolve(Constants.PACKAGES);

        // feature-pack builder
        final FeaturePackSpec.Builder fpBuilder = FeaturePackSpec.builder(ArtifactCoords.newGav(project.getGroupId(), fpArtifactId, project.getVersion()));

        // feature-pack build config
        try {
            wfFpConfig = Util.loadFeaturePackBuildConfig(getFPConfigFile(), getFPConfigProperties());
        } catch (ProvisioningException e) {
            throw new MojoExecutionException("Failed to load feature-pack config file", e);
        }

        try {
            processFeaturePackDependencies(fpBuilder);
        } catch (MojoExecutionException e) {
            throw e;
        } catch (Exception e) {
            throw new MojoExecutionException("Failed to process dependencies", e);
        }

        final Path srcModulesDir = targetResources.resolve(WfConstants.MODULES).resolve(WfConstants.SYSTEM).resolve(WfConstants.LAYERS).resolve(WfConstants.BASE);
        if(!Files.exists(srcModulesDir)) {
            throw new MojoExecutionException(Errors.pathDoesNotExist(srcModulesDir));
        }

        final PackageSpec.Builder modulesAll = PackageSpec.builder(WfConstants.MODULES_ALL);
        try {
            final Map<String, Path> moduleXmlByPkgName = findModules(srcModulesDir);
            if(moduleXmlByPkgName.isEmpty()) {
                throw new MojoExecutionException("Modules not found in " + srcModulesDir);
            }
            packageModules(fpBuilder, targetResources, moduleXmlByPkgName, fpPackagesDir, modulesAll);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process modules content", e);
        }
        try {
            final PackageSpec modulesAllPkg = modulesAll.build();
            PackageXmlWriter.getInstance().write(modulesAllPkg, fpPackagesDir.resolve(modulesAllPkg.getName()).resolve(Constants.PACKAGE_XML));
            fpBuilder.addPackage(modulesAllPkg);
        } catch (XMLStreamException | IOException | ProvisioningDescriptionException e) {
            throw new MojoExecutionException("Failed to add package", e);
        }

        try {
            packageContent(fpBuilder, targetResources.resolve(Constants.CONTENT), fpPackagesDir);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process content", e);
        }

        fpBuilder.addProvisioningPlugin(ArtifactCoords.newGav("org.jboss.pm", "wildfly-provisioning-plugin", "1.0.0.Alpha-SNAPSHOT").toArtifactCoords());

        addConfigPackages(targetResources.resolve(WfConstants.CONFIG).resolve(Constants.PACKAGES), fpDir.resolve(Constants.PACKAGES), fpBuilder);

        for(String defaultPackage : wfFpConfig.getDefaultPackages()) {
            if(!fpBuilder.hasPackage(defaultPackage)) {
                throw new MojoExecutionException(Errors.unknownPackage(defaultPackage));
            }
            fpBuilder.markAsDefaultPackage(defaultPackage);
        }

        final FeaturePackSpec fpSpec;
        try {
            fpSpec = fpBuilder.build();
            FeaturePackXmlWriter.getInstance().write(fpSpec, fpDir.resolve(Constants.FEATURE_PACK_XML));
        } catch (XMLStreamException | IOException | ProvisioningDescriptionException e) {
            throw new MojoExecutionException(Errors.writeFile(fpDir.resolve(Constants.FEATURE_PACK_XML)), e);
        }

        // collect feature-pack resources
        final Path resourcesWildFly = fpDir.resolve(Constants.RESOURCES).resolve(WfConstants.WILDFLY);
        try {
            Files.createDirectories(resourcesWildFly);
        } catch (IOException e) {
            throw new MojoExecutionException(Errors.mkdirs(resourcesWildFly), e);
        }

        // properties
        try(OutputStream out = Files.newOutputStream(resourcesWildFly.resolve(WfConstants.WILDFLY_TASKS_PROPS))) {
                getFPConfigProperties().store(out, "WildFly feature-pack properties");
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to store feature-pack properties", e);
        }

        // artifact versions
        try {
            this.artifactVersions.store(resourcesWildFly.resolve(WfConstants.ARTIFACT_VERSIONS_PROPS));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to store artifact versions", e);
        }

        try {
            repoSystem.install(repoSession, MavenPluginUtil.getInstallLayoutRequest(workDir));
        } catch (InstallationException | IOException e) {
            throw new MojoExecutionException(FpMavenErrors.featurePackInstallation(), e);
        }
    }

    private void addConfigPackages(final Path configDir, final Path packagesDir, final Builder fpBuilder) throws MojoExecutionException {
        if(!Files.exists(configDir)) {
            return;
        }
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(configDir)) {
            for(Path configPackage : stream) {
                final Path packageDir = packagesDir.resolve(configPackage.getFileName());
                if (!Files.exists(packageDir)) {
                    Files.createDirectories(packageDir);
                }
                IoUtils.copy(configPackage, packageDir);

                final Path packageXml = configPackage.resolve(Constants.PACKAGE_XML);
                if (Files.exists(packageXml)) {
                    final PackageSpec pkgSpec;
                    try (BufferedReader reader = Files.newBufferedReader(packageXml)) {
                        try {
                            pkgSpec = new PackageXmlParser().parse(reader);
                        } catch (XMLStreamException e) {
                            throw new MojoExecutionException("Failed to parse " + packageXml, e);
                        }
                    }
                    IoUtils.copy(packageXml, packageDir.resolve(Constants.PACKAGE_XML));
                    fpBuilder.addPackage(pkgSpec);
                }
            }
        } catch (ProvisioningDescriptionException e) {
            throw new MojoExecutionException("Failed to add package", e);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to process config packages", e);
        }
    }

    private void processFeaturePackDependencies(final Builder fpBuilder)
            throws Exception {
        if (!wfFpConfig.getDependencies().isEmpty()) {
            fpDependencies = new HashMap<>(wfFpConfig.getDependencies().size());
            for (FeaturePackDependencySpec depSpec : wfFpConfig.getDependencies()) {
                final FeaturePackConfig depConfig = depSpec.getTarget();
                final String depStr = depConfig.getGav().toString();
                String gavStr = artifactVersions.getVersion(depStr);
                if(gavStr == null) {
                    throw new MojoExecutionException("Failed resolve artifact version for " + depStr);
                }
                gavStr = gavStr.replace(depStr, depStr + "-new");
                final ArtifactCoords.Gav depGav = ArtifactCoords.newGav(gavStr);
                final FeaturePackConfig.Builder depBuilder = FeaturePackConfig.builder(depGav);
                depBuilder.setInheritPackages(depConfig.isInheritPackages());
                if (depConfig.hasExcludedPackages()) {
                    try {
                        depBuilder.excludeAllPackages(depConfig.getExcludedPackages()).build();
                    } catch (ProvisioningException e) {
                        throw new MojoExecutionException("Failed to process dependencies", e);
                    }
                }
                if (depConfig.hasIncludedPackages()) {
                    try {
                        depBuilder.includeAllPackages(depConfig.getIncludedPackages()).build();
                    } catch (ProvisioningException e) {
                        throw new MojoExecutionException("Failed to process dependencies", e);
                    }
                }
                fpBuilder.addDependency(depSpec.getName(), depBuilder.build());
                final Path depZip = resolveArtifact(depGav.toArtifactCoords());
                fpDependencies.put(depSpec.getName(), FeaturePackLayoutDescriber.describeFeaturePackZip(depZip));
            }
        }
    }

    private void packageContent(FeaturePackSpec.Builder fpBuilder, Path contentDir, Path packagesDir) throws IOException, MojoExecutionException {
        try(DirectoryStream<Path> stream = Files.newDirectoryStream(contentDir)) {
            for(Path p : stream) {
                final String pkgName = p.getFileName().toString();
                if(pkgName.equals(WfConstants.DOCS)) {
                    final PackageSpec.Builder docsBuilder = PackageSpec.builder(pkgName);
                    try(DirectoryStream<Path> docsStream = Files.newDirectoryStream(p)) {
                        for(Path docPath : docsStream) {
                            final String docName = docPath.getFileName().toString();
                            final Path docDir = packagesDir.resolve(docName);
                            IoUtils.copy(docPath, docDir.resolve(Constants.CONTENT).resolve(WfConstants.DOCS).resolve(docName));
                            final PackageSpec docSpec = PackageSpec.builder(docName).build();
                            fpBuilder.addPackage(docSpec);
                            writeXml(docSpec, docDir);
                            docsBuilder.addDependency(docName, true);
                        }
                    }

                    if(wfFpConfig.hasSchemaGroups()) {
                        docsBuilder.addDependency("docs.schemas", true);
                        final Path schemasPackageDir = packagesDir.resolve("docs.schemas");
                        final Path schemaGroupsTxt = schemasPackageDir.resolve(WfConstants.PM).resolve(WfConstants.WILDFLY).resolve("schema-groups.txt");
                        BufferedWriter writer = null;
                        try {
                            Files.createDirectories(schemasPackageDir);
                            final PackageSpec docsSchemasSpec = PackageSpec.forName("docs.schemas");
                            fpBuilder.addPackage(docsSchemasSpec);
                            PackageXmlWriter.getInstance().write(docsSchemasSpec, schemasPackageDir.resolve(Constants.PACKAGE_XML));
                            Files.createDirectories(schemaGroupsTxt.getParent());
                            writer = Files.newBufferedWriter(schemaGroupsTxt);
                            for (String group : wfFpConfig.getSchemaGroups()) {
                                writer.write(group);
                                writer.newLine();
                            }
                        } catch (IOException e) {
                            throw new MojoExecutionException(Errors.mkdirs(schemaGroupsTxt.getParent()), e);
                        } catch (XMLStreamException e) {
                            throw new MojoExecutionException(Errors.writeFile(schemaGroupsTxt), e);
                        } finally {
                            if(writer != null) {
                                try {
                                    writer.close();
                                } catch (IOException e) {
                                }
                            }
                        }
                    }

                    PackageSpec docsSpec = docsBuilder.build();
                    writeXml(docsSpec, packagesDir.resolve(pkgName));
                    fpBuilder.addPackage(docsSpec);
                } else if(pkgName.equals("bin")) {
                    final PackageSpec.Builder binBuilder = PackageSpec.builder(pkgName);
                    final Path binPkgDir = packagesDir.resolve(pkgName).resolve(Constants.CONTENT).resolve(pkgName);
                    final PackageSpec.Builder standaloneBinBuilder = PackageSpec.builder("bin.standalone");
                    final Path binStandalonePkgDir = packagesDir.resolve("bin.standalone").resolve(Constants.CONTENT).resolve(pkgName);
                    final PackageSpec.Builder domainBinBuilder = PackageSpec.builder("bin.domain");
                    final Path binDomainPkgDir = packagesDir.resolve("bin.domain").resolve(Constants.CONTENT).resolve(pkgName);
                    try (DirectoryStream<Path> binStream = Files.newDirectoryStream(p)) {
                        for (Path binPath : binStream) {
                            final String fileName = binPath.getFileName().toString();
                            if(fileName.startsWith(WfConstants.STANDALONE)) {
                                IoUtils.copy(binPath, binStandalonePkgDir.resolve(fileName));
                            } else if(fileName.startsWith(WfConstants.DOMAIN)) {
                                IoUtils.copy(binPath, binDomainPkgDir.resolve(fileName));
                            } else {
                                IoUtils.copy(binPath, binPkgDir.resolve(fileName));
                            }
                        }
                    }

                    PackageSpec binSpec = binBuilder.build();
                    fpBuilder.addPackage(binSpec);
                    writeXml(binSpec, packagesDir.resolve(pkgName));

                    binSpec = standaloneBinBuilder.addDependency(pkgName).build();
                    fpBuilder.addPackage(binSpec);
                    writeXml(binSpec, packagesDir.resolve(binSpec.getName()));

                    binSpec = domainBinBuilder.addDependency(pkgName).build();
                    fpBuilder.addPackage(binSpec);
                    writeXml(binSpec, packagesDir.resolve(binSpec.getName()));
                } else {
                    final Path pkgDir = packagesDir.resolve(pkgName);
                    IoUtils.copy(p, pkgDir.resolve(Constants.CONTENT).resolve(pkgName));
                    final PackageSpec pkgSpec = PackageSpec.builder(pkgName).build();
                    writeXml(pkgSpec, pkgDir);
                    fpBuilder.addPackage(pkgSpec);
                }
            }
        } catch (ProvisioningDescriptionException e) {
            throw new MojoExecutionException("Failed to add package", e);
        }
    }

    private Map<String, Path> findModules(Path modulesDir) throws IOException {
        final Map<String, Path> moduleXmlByPkgName = new HashMap<>();
        Files.walkFileTree(modulesDir, new FileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                final Path moduleXml = dir.resolve(WfConstants.MODULE_XML);
                if(Files.exists(moduleXml)) {
                    final String packageName = modulesDir.relativize(moduleXml.getParent()).toString().replace(File.separatorChar, '.');
                    moduleXmlByPkgName.put(packageName, moduleXml);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
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
        return moduleXmlByPkgName;
    }

    private void packageModules(FeaturePackSpec.Builder fpBuilder,
            Path resourcesDir, Map<String, Path> moduleXmlByPkgName, Path packagesDir, PackageSpec.Builder modulesAll)
            throws IOException, MojoExecutionException {

        for (Map.Entry<String, Path> module : moduleXmlByPkgName.entrySet()) {
            final String packageName = module.getKey();
            final Path moduleXml = module.getValue();

            final Path packageDir = packagesDir.resolve(packageName);
            final Path targetXml = packageDir.resolve("pm/wildfly").resolve(WfConstants.MODULE).resolve(resourcesDir.relativize(moduleXml));
            Files.createDirectories(targetXml.getParent());
            IoUtils.copy(moduleXml.getParent(), targetXml.getParent());

            final PackageSpec.Builder pkgSpecBuilder = PackageSpec.builder(packageName);
            final ModuleParseResult parsedModule;
            try {
                parsedModule = ModuleXmlParser.parse(targetXml, WfConstants.UTF8);
                if (!parsedModule.dependencies.isEmpty()) {
                    for (ModuleDependency moduleDep : parsedModule.dependencies) {
                        final StringBuilder buf = new StringBuilder();
                        buf.append(moduleDep.getModuleId().getName()).append('.').append(moduleDep.getModuleId().getSlot());
                        final String depName = buf.toString();
                        if (moduleXmlByPkgName.containsKey(depName)) {
                            pkgSpecBuilder.addDependency(depName, moduleDep.isOptional());
                        } else {
                            Map.Entry<String, FeaturePackSpec> depSrc = null;
                            if (!fpDependencies.isEmpty()) {
                                for (Map.Entry<String, FeaturePackSpec> depEntry : fpDependencies.entrySet()) {
                                    if (depEntry.getValue().getPackageNames().contains(depName)) {
                                        if (depSrc != null) {
                                            throw new MojoExecutionException("Package " + depName
                                                    + " found in more than one feature-pack dependency: " + depSrc.getKey()
                                                    + " and " + depEntry.getKey());
                                        }
                                        depSrc = depEntry;
                                    }
                                }
                            }
                            if(depSrc != null) {
                                pkgSpecBuilder.addDependency(depSrc.getKey(), depName, moduleDep.isOptional());
                            } else if(moduleDep.isOptional()){
                                //System.out.println("UNSATISFIED EXTERNAL OPTIONAL DEPENDENCY " + packageName + " -> " + depName);
                            } else {
                                throw new MojoExecutionException("Package " + packageName + " has unsatisifed external dependency on package " + depName);
                            }
                        }
                    }
                }
            } catch (XMLStreamException e) {
                throw new IOException(Errors.parseXml(targetXml), e);
            }

            final PackageSpec pkgSpec = pkgSpecBuilder.build();
            try {
                PackageXmlWriter.getInstance().write(pkgSpec, packageDir.resolve(Constants.PACKAGE_XML));
            } catch (XMLStreamException e) {
                throw new IOException(Errors.writeFile(packageDir.resolve(Constants.PACKAGE_XML)), e);
            }
            modulesAll.addDependency(packageName, true);
            try {
                fpBuilder.addPackage(pkgSpec);
            } catch (ProvisioningDescriptionException e) {
                throw new MojoExecutionException("Failed to add package", e);
            }

            if (!OS_WINDOWS) {
                Files.setPosixFilePermissions(targetXml, Files.getPosixFilePermissions(moduleXml));
            }
        }
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

    private static void writeXml(PackageSpec pkgSpec, Path dir) throws MojoExecutionException {
        try {
            Files.createDirectories(dir);
            PackageXmlWriter.getInstance().write(pkgSpec, dir.resolve(Constants.PACKAGE_XML));
        } catch (XMLStreamException | IOException e) {
            throw new MojoExecutionException(Errors.writeFile(dir.resolve(Constants.PACKAGE_XML)), e);
        }
    }

    private Path resolveArtifact(ArtifactCoords coords) throws ProvisioningException {
        final ArtifactResult result;
        try {
            result = repoSystem.resolveArtifact(repoSession, getArtifactRequest(coords));
        } catch (ArtifactResolutionException e) {
            throw new ProvisioningException(FpMavenErrors.artifactResolution(coords), e);
        }
        if(!result.isResolved()) {
            throw new ProvisioningException(FpMavenErrors.artifactResolution(coords));
        }
        if(result.isMissing()) {
            throw new ProvisioningException(FpMavenErrors.artifactMissing(coords));
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

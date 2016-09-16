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

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import javax.xml.stream.XMLStreamException;

import org.jboss.provisioning.ArtifactCoords;
import org.jboss.provisioning.Errors;
import org.jboss.provisioning.ProvisioningException;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.WildFlyPostFeaturePackTasks;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.WildFlyPostFeaturePackTasksParser;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.build.FeaturePackBuildModelParser;
import org.jboss.provisioning.plugin.wildfly.featurepack.model.build.WildFlyFeaturePackBuild;

/**
 *
 * @author Alexey Loubyansky
 */
class Util {

    interface ArtifactProcessor {
        void process(ArtifactCoords coords) throws IOException;
    }

    static WildFlyFeaturePackBuild loadFeaturePackBuildConfig(Path configFile, Properties props) throws ProvisioningException {
        try (InputStream configStream = Files.newInputStream(configFile)) {
            props.putAll(System.getProperties());
            return new FeaturePackBuildModelParser(new MapPropertyResolver(props)).parse(configStream);
        } catch (XMLStreamException e) {
            throw new ProvisioningException(Errors.parseXml(configFile), e);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.openFile(configFile), e);
        }
    }

    static WildFlyPostFeaturePackTasks loadWildFlyTasks(Path configFile, Properties props) throws ProvisioningException {
        try (InputStream configStream = Files.newInputStream(configFile)) {
            props.putAll(System.getProperties());
            return new WildFlyPostFeaturePackTasksParser(new MapPropertyResolver(props)).parse(configStream);
        } catch (XMLStreamException e) {
            throw new ProvisioningException(Errors.parseXml(configFile), e);
        } catch (IOException e) {
            throw new ProvisioningException(Errors.openFile(configFile), e);
        }
    }

    static void processModuleArtifacts(final Path moduleXml, ArtifactProcessor ap) throws IOException {
        try {
            final ModuleParseResult parsedModule = ModuleXmlParser.parse(moduleXml, "UTF-8");
            for(ModuleParseResult.ArtifactName artName : parsedModule.artifacts) {
                ap.process(ArtifactCoordsUtil.fromJBossModules(artName.getArtifactCoords(), "jar"));
            }
        } catch (XMLStreamException e) {
            throw new IOException(Errors.parseXml(moduleXml), e);
        }
    }

}

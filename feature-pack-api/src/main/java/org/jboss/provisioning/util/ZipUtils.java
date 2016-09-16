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

package org.jboss.provisioning.util;

import java.io.IOException;
import java.net.URI;
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
import java.util.Map;

/**
 *
 * @author Alexey Loubyansky
 */
public class ZipUtils {

    private static final String JAR_FILE_PREFIX = "jar:file:";
    private static final Map<String, String> CREATE_ENV = Collections.singletonMap("create", "true");

    public static void unzip(Path zipFile, Path targetDir) throws IOException {
        if(!Files.exists(targetDir)) {
            Files.createDirectories(targetDir);
        }
        try (FileSystem zipfs = FileSystems.newFileSystem(zipFile, null)) {
            for(Path zipRoot : zipfs.getRootDirectories()) {
                copyFromZip(zipRoot, targetDir);
            }
        }
    }

    public static void copyFromZip(Path source, Path target) throws IOException {
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                        final Path targetDir = target.resolve(source.relativize(dir).toString());
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
                        Files.copy(file, target.resolve(source.relativize(file).toString()));
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    public static void zip(Path src, Path zipFile) throws IOException {
        final URI uri = URI.create(JAR_FILE_PREFIX + zipFile.toAbsolutePath().toString());
        try (FileSystem zipfs = FileSystems.newFileSystem(uri, CREATE_ENV)) {
            if(Files.isDirectory(src)) {
                try (DirectoryStream<Path> stream = Files.newDirectoryStream(src)) {
                    for(Path srcPath : stream) {
                        copyToZip(src, srcPath, zipfs);
                    }
                }
            } else {
                Files.copy(src, zipfs.getPath(src.getFileName().toString()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    private static void copyToZip(Path srcRoot, Path srcPath, FileSystem zipfs) throws IOException {
        Files.walkFileTree(srcPath, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                        final Path targetDir = zipfs.getPath(srcRoot.relativize(dir).toString());
                        try {
                            Files.copy(dir, targetDir, StandardCopyOption.REPLACE_EXISTING);
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
                        Files.copy(file, zipfs.getPath(srcRoot.relativize(file).toString()), StandardCopyOption.REPLACE_EXISTING);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }
}

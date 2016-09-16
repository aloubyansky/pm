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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.Charset;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.EnumSet;
import java.util.UUID;

/**
 *
 * @author Alexey Loubyansky
 */
public class IoUtils {

    private static final Path TMP_DIR = Paths.get(PropertyUtils.getSystemProperty("java.io.tmpdir"));

    public static Path createTmpDir(String name) {
        final Path dir = TMP_DIR.resolve(name);
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create " + dir.toAbsolutePath());
        }
        return dir;
    }

    public static Path createRandomTmpDir() {
        return createTmpDir(UUID.randomUUID().toString());
    }

    public static void recursiveDelete(Path root) {
        if (root == null) {
            return;
        }
        try {
            Files.walkFileTree(root, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }
                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException e)
                    throws IOException {
                    if (e == null) {
                        Files.delete(dir);
                        return FileVisitResult.CONTINUE;
                    } else {
                        // directory iteration failed
                        throw e;
                    }
                }
            });
        } catch (IOException e) {
        }
    }

    public static void copy(Path source, Path target) throws IOException {
        if(Files.isDirectory(source)) {
            Files.createDirectories(target);
        } else {
            Files.createDirectories(target.getParent());
        }
        Files.walkFileTree(source, EnumSet.of(FileVisitOption.FOLLOW_LINKS), Integer.MAX_VALUE,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs)
                        throws IOException {
                        final Path targetDir = target.resolve(source.relativize(dir));
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
                        Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.REPLACE_EXISTING);
                        return FileVisitResult.CONTINUE;
                    }
                });
    }

    public static String readFile(Path file) throws IOException {
        try(BufferedReader reader = Files.newBufferedReader(file)) {
            final StringWriter buf = new StringWriter();
            String line = reader.readLine();
            if(line != null) {
                try (final BufferedWriter bw = new BufferedWriter(buf)) {
                    bw.append(line);
                    line = reader.readLine();
                    while (line != null) {
                        bw.newLine();
                        bw.append(line);
                        line = reader.readLine();
                    }
                }
            }
            return buf.toString();
        }
    }

    public static void writeFile(Path file, String content) throws IOException {
        try(final FileOutputStream fos = new FileOutputStream(file.toFile())) {
            fos.write(content.getBytes(Charset.forName("UTF-8")));
        }
    }
}

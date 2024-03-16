/*
 * Copyright (c) 2024, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package org.openjdk.jmh.profile;

import org.openjdk.jmh.util.Utils;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.xpath.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

final class XCTraceSupport {
    private XCTraceSupport() {
    }

    static void exportTable(String runFile, String outputFile, XCTraceTableHandler.ProfilingTableType table) {
        Collection<String> out = Utils.tryWith(
                "xctrace", "export",
                "--input", runFile,
                "--output", outputFile,
                "--xpath",
                "/trace-toc/run/data/table[@schema=\"" + table.tableName + "\"]"
        );
        if (!out.isEmpty()) {
            throw new IllegalStateException(out.toString());
        }
    }

    static void exportTableOfContents(String runFile, String outputFile) {
        Collection<String> out = Utils.tryWith(
                "xctrace", "export",
                "--input", runFile,
                "--output", outputFile,
                "--toc"
        );
        if (!out.isEmpty()) {
            throw new IllegalStateException(out.toString());
        }
    }

    static Collection<String> recordCommandPrefix(String runFile, String instrument, String template) {
        if ((instrument == null) == (template == null)) {
            throw new IllegalArgumentException("Either template, or instrument expected.");
        }
        List<String> args = new ArrayList<>(10);
        Collections.addAll(args, "xctrace", "record");
        if (instrument != null) {
            Collections.addAll(args, "--instrument", instrument);
        } else {
            Collections.addAll(args, "--template", template);
        }
        Collections.addAll(args, "--output", runFile, "--target-stdout", "-", "--launch", "--");
        return args;
    }

    static void checkXCTraceWorks() throws ProfilerException {
        Collection<String> out = Utils.tryWith("xctrace", "version");
        if (!out.isEmpty()) {
            throw new ProfilerException(out.toString());
        }
    }

    static Path findTraceFile(Path parent) {
        try (Stream<Path> files = Files.list(parent)) {
            List<Path> launchFiles = files
                    .filter(path -> path.getFileName().toString().startsWith("Launch"))
                    .collect(Collectors.toList());
            if (launchFiles.size() != 1) {
                throw new IllegalStateException("Expected only one launch file, found " +
                        +launchFiles.size() + ": " + launchFiles);
            }
            return launchFiles.get(0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static void removeDirectory(Path path) {
        if (!path.toFile().exists()) {
            return;
        }
        try {
            Files.walkFileTree(path, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    Files.delete(file);
                    return FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                    Files.delete(dir);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static Path createTemporaryDirectoryName() {
        // In general, it's unsafe to create a random file name and then create a file/dir itself.
        // But it should be fine for profiling purposes.
        String tempDir = System.getProperty("java.io.tmpdir");
        if (tempDir == null) {
            throw new IllegalStateException("System temporary folder is unknown.");
        }
        for (int i = 0; i < 5; i++) {
            String dirname = "jmh-xctrace-results-" + System.nanoTime();
            Path path = Paths.get(tempDir, dirname);
            if (!path.toFile().exists()) {
                return path;
            }
        }
        throw new IllegalStateException("Can't create a temporary folder for a run.");
    }

    static void copyDirectory(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path destPath = destination.resolve(source.relativize(dir));
                Files.copy(dir, destPath);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path destFilePath = destination.resolve(source.relativize(file));
                Files.copy(file, destFilePath, StandardCopyOption.COPY_ATTRIBUTES);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static final String hwCpuTypePrefix = "hw.cputype: ";
    private static final String hwCpuSubtypePrefix = "hw.cpusubtype: ";
    private static final String hwCpuFamily = "hw.cpufamily: ";


    static List<String> availablePerformanceMonitoringEvents() {
        Integer cpuType = null;
        Integer cpuSubtype = null;
        Integer cpuFamily = null;
        List<String> sysctlProps = Utils.runWith("sysctl", "hw")
                .stream().
                flatMap(line -> Stream.of(line.split("\n")))
                .collect(Collectors.toList());
        for (String prop : sysctlProps) {
            if (prop.startsWith(hwCpuTypePrefix)) {
                cpuType = Integer.parseInt(prop.substring(hwCpuTypePrefix.length()));
            } else if (prop.startsWith(hwCpuSubtypePrefix)) {
                cpuSubtype = Integer.parseInt(prop.substring(hwCpuSubtypePrefix.length()));
            } else if (prop.startsWith(hwCpuFamily)) {
                cpuFamily = Integer.parseInt(prop.substring(hwCpuFamily.length()));
            }
        }
        if (cpuType == null || cpuFamily == null || cpuSubtype == null) {
            return Collections.emptyList();
        }

        String kpepFile = String.format("/usr/share/kpep/cpu_%s_%s_%s.plist",
                Integer.toUnsignedString(cpuType, 16),
                Integer.toUnsignedString(cpuSubtype, 16),
                Integer.toUnsignedString(cpuFamily, 16));

        File kpepDb = new File(kpepFile);
        if (!kpepDb.exists()) {
            return Collections.emptyList();
        }

        String dict = String.join("\n", Utils.runWith("plutil", "-extract", "system.cpu.events", "xml1",
                "-o", "-", kpepFile));
        DocumentBuilderFactory builderFactory = DocumentBuilderFactory.newInstance();
        List<String> events = new ArrayList<>();
        try {
            Document xmlDict = builderFactory.newDocumentBuilder().parse(
                    new ByteArrayInputStream(dict.getBytes(StandardCharsets.UTF_8)));
            NodeList keys = (NodeList) XPathFactory.newInstance().newXPath().compile("/plist/dict/key")
                    .evaluate(xmlDict, XPathConstants.NODESET);
            for (int idx = 0; idx < keys.getLength(); idx++) {
                Node node = keys.item(idx);
                events.add(node.getTextContent());
            }
        } catch (IOException | SAXException | XPathException | ParserConfigurationException e) {
            throw new IllegalStateException("Unable to parse kpep file: " + kpepFile, e);
        }
        return events;
    }

    static void buildSamplerPackage(long samplingRateMillis, List<String> pmCounters, Path outputPath) {
        if (pmCounters.isEmpty()) throw new IllegalArgumentException("PMC list is empty");
        if (samplingRateMillis <= 0) throw new IllegalStateException("Sampling rate should be positive");
        Set<String> passedCounters = new HashSet<>(pmCounters);
        passedCounters.removeAll(availablePerformanceMonitoringEvents());
        if (!passedCounters.isEmpty()) throw new IllegalArgumentException("Unsupported PMC events: " + passedCounters);

        InputStream templateStream = XCTraceSupport.class.getResourceAsStream(
                "/org.openjdk.jmh.profile.xctrace/stat-instr.instrpkg.template");
        String template;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(templateStream))) {
            template = reader.lines().collect(Collectors.joining("\n"));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        template = template
                .replace("SAMPLING_RATE_MICROS", Long.toString(TimeUnit.MILLISECONDS.toMillis(samplingRateMillis)))
                .replace("PMC_EVENTS_LIST",
                        pmCounters.stream().map(evt -> "<string>" + evt + "</string>").collect(Collectors.joining()));
        Path pkgFile;
        try {
            pkgFile = Files.createTempFile("", ".instrpkg");
            Files.write(pkgFile, template.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        String xcodePath = "/Applications/Xcode.app/Contents/Developer";//Utils.tryWith("xcode-select", "-p").iterator().next();
        String instrBuilder = Paths.get(xcodePath, "usr", "bin", "instrumentbuilder").toString();
        Utils.runWith(instrBuilder, "-o", outputPath.toString(), "-i", pkgFile.toString(), "-l", "CPU Counters");
    }
}

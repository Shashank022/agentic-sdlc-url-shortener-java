package io.agentic.sdlc.shortener.workflow;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Creates a disposable source tree and applies only policy-validated complete-file changes. */
public final class CandidateWorkspace {

    private CandidateWorkspace() { }

    public static Path create(Path baseline, Path candidate, boolean greenfield) throws IOException {
        deleteTree(candidate);
        Files.createDirectories(candidate);
        if (greenfield) {
            Path pom = baseline.resolve("pom.xml");
            if (!Files.isRegularFile(pom)) throw new IOException("Greenfield generation needs a Maven starter pom.xml.");
            Files.copy(pom, candidate.resolve("pom.xml"), StandardCopyOption.REPLACE_EXISTING);
            return candidate;
        }
        for (String input : List.of("pom.xml", "src", ".mvn", "mvnw", "mvnw.cmd")) {
            Path sourceRoot = baseline.resolve(input);
            if (!Files.exists(sourceRoot) || Files.isSymbolicLink(sourceRoot)) continue;
            if (Files.isDirectory(sourceRoot)) {
                try (var paths = Files.walk(sourceRoot)) {
                    for (Path source : paths.sorted().toList()) copyInput(baseline, candidate, source);
                }
            } else if (Files.isRegularFile(sourceRoot)) {
                copyInput(baseline, candidate, sourceRoot);
            }
        }
        return candidate;
    }

    private static void copyInput(Path baseline, Path candidate, Path source) throws IOException {
        if (Files.isSymbolicLink(source)) return;
        Path relative = baseline.relativize(source);
        Path target = candidate.resolve(relative).normalize();
        if (!target.startsWith(candidate)) throw new IOException("Source path escaped the candidate workspace.");
        if (Files.isDirectory(source)) Files.createDirectories(target);
        else if (Files.isRegularFile(source)) {
            Files.createDirectories(target.getParent());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public static void apply(Path root, List<ProposedChange> changes) throws IOException {
        Path safeRoot = root.toAbsolutePath().normalize();
        for (ProposedChange change : changes) {
            ChangeSet.validatePath(change.path());
            Path target = safeRoot.resolve(change.path()).normalize();
            if (!target.startsWith(safeRoot)) throw new IOException("Change path escaped the candidate workspace.");
            ensureNoSymlinkParents(safeRoot, target);
            switch (change.operation()) {
                case "create" -> {
                    if (Files.exists(target)) throw new IOException("Create target already exists: " + change.path());
                    Files.createDirectories(target.getParent());
                    Files.writeString(target, change.content(), StandardCharsets.UTF_8);
                }
                case "update" -> {
                    if (!Files.isRegularFile(target)) throw new IOException("Update target does not exist: " + change.path());
                    atomicWrite(target, change.content());
                }
                case "delete" -> {
                    if (!Files.isRegularFile(target)) throw new IOException("Delete target does not exist: " + change.path());
                    Files.delete(target);
                }
                default -> throw new IOException("Unsupported patch operation: " + change.operation());
            }
        }
    }

    public static String diff(Path baseline, List<ProposedChange> changes) throws IOException {
        return diff(baseline, changes, false);
    }

    public static String diff(Path baseline, List<ProposedChange> changes, boolean emptyBaseline) throws IOException {
        StringBuilder result = new StringBuilder();
        for (ProposedChange change : changes) {
            Path oldFile = baseline.toAbsolutePath().normalize().resolve(change.path()).normalize();
            ensureNoSymlinkParents(baseline.toAbsolutePath().normalize(), oldFile);
            List<String> oldLines = !emptyBaseline && Files.isRegularFile(oldFile, java.nio.file.LinkOption.NOFOLLOW_LINKS)
                    ? Files.readAllLines(oldFile) : List.of();
            List<String> newLines = change.operation().equals("delete") ? List.of() : change.content().lines().toList();
            result.append("--- ").append(change.operation().equals("create") ? "/dev/null" : "a/" + change.path()).append('\n');
            result.append("+++ ").append(change.operation().equals("delete") ? "/dev/null" : "b/" + change.path()).append('\n');
            result.append("@@ complete-file diff @@\n");
            oldLines.forEach(line -> result.append('-').append(line).append('\n'));
            newLines.forEach(line -> result.append('+').append(line).append('\n'));
        }
        return result.toString();
    }

    /** Renders the final candidate state against its original source baseline for release review. */
    public static String diffSnapshot(Path baseline, Path candidate, List<String> changedPaths,
                                      boolean emptyBaseline) throws IOException {
        Path safeBaseline = baseline.toAbsolutePath().normalize();
        Path safeCandidate = candidate.toAbsolutePath().normalize();
        StringBuilder result = new StringBuilder();
        for (String changedPath : changedPaths.stream().distinct().toList()) {
            ChangeSet.validatePath(changedPath);
            Path oldFile = safeBaseline.resolve(changedPath).normalize();
            Path newFile = safeCandidate.resolve(changedPath).normalize();
            if (!oldFile.startsWith(safeBaseline) || !newFile.startsWith(safeCandidate)) {
                throw new IOException("Review diff path escaped its workspace.");
            }
            ensureNoSymlinkParents(safeBaseline, oldFile);
            ensureNoSymlinkParents(safeCandidate, newFile);
            boolean hasOld = !emptyBaseline && Files.isRegularFile(oldFile, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            boolean hasNew = Files.isRegularFile(newFile, java.nio.file.LinkOption.NOFOLLOW_LINKS);
            List<String> oldLines = hasOld ? Files.readAllLines(oldFile) : List.of();
            List<String> newLines = hasNew ? Files.readAllLines(newFile) : List.of();
            result.append("--- ").append(hasOld ? "a/" + changedPath : "/dev/null").append('\n');
            result.append("+++ ").append(hasNew ? "b/" + changedPath : "/dev/null").append('\n');
            result.append("@@ complete-file diff @@\n");
            oldLines.forEach(line -> result.append('-').append(line).append('\n'));
            newLines.forEach(line -> result.append('+').append(line).append('\n'));
        }
        return result.toString();
    }

    public static List<String> changedPaths(List<ProposedChange> changes) {
        return changes.stream().map(ProposedChange::path).toList();
    }

    public static boolean safeRegularFile(Path root, String relativePath) throws IOException {
        Path safeRoot = root.toAbsolutePath().normalize();
        ChangeSet.validatePath(relativePath);
        Path target = safeRoot.resolve(relativePath).normalize();
        if (!target.startsWith(safeRoot)) return false;
        ensureNoSymlinkParents(safeRoot, target);
        return Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    public static String fingerprint(Path root) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            if (Files.exists(root)) {
                try (var paths = Files.walk(root)) {
                    for (Path file : paths.filter(Files::isRegularFile).filter(path -> !path.startsWith(root.resolve("target"))).sorted().toList()) {
                        digest.update(root.relativize(file).toString().getBytes(StandardCharsets.UTF_8));
                        digest.update(Files.readAllBytes(file));
                    }
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    public static void copyTree(Path source, Path target) throws IOException {
        if (Files.exists(target)) throw new IOException("Release source directory already exists: " + target);
        try (var paths = Files.walk(source)) {
            for (Path item : paths.toList()) {
                Path destination = target.resolve(source.relativize(item));
                if (Files.isDirectory(item)) Files.createDirectories(destination);
                else if (Files.isRegularFile(item)) {
                    Files.createDirectories(destination.getParent());
                    Files.copy(item, destination);
                }
            }
        }
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (var paths = Files.walk(root)) {
            for (Path item : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(item);
        }
    }

    private static void ensureNoSymlinkParents(Path root, Path target) throws IOException {
        Path current = root;
        for (Path part : root.relativize(target)) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) throw new IOException("Symbolic links are not allowed in the candidate tree.");
        }
    }

    private static void atomicWrite(Path path, String text) throws IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp-" + UUID.randomUUID());
        Files.writeString(temporary, text, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}

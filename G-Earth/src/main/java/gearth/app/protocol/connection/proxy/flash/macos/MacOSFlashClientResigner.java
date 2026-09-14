package gearth.app.protocol.connection.proxy.flash.macos;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

public final class MacOSFlashClientResigner {

    private static final String HABBO_APP_NAME = "Habbo.app";
    private static final String HABBO_EXECUTABLE = "Contents/MacOS/Habbo";
    private static final String GET_TASK_ALLOW = "com.apple.security.get-task-allow";

    private static final String ENTITLEMENTS = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>com.apple.security.get-task-allow</key>
                <true/>
            </dict>
            </plist>
            """;

    private final Path habboApp;
    private final Path executable;

    private MacOSFlashClientResigner(Path habboApp) {
        this.habboApp = habboApp.toAbsolutePath().normalize();
        this.executable = this.habboApp.resolve(HABBO_EXECUTABLE);
    }

    public static Optional<MacOSFlashClientResigner> locate() throws IOException {
        final Path airDownloads = Path.of(
                System.getProperty("user.home"),
                "Library", "Application Support", "Habbo Launcher", "downloads", "air"
        );
        if (!Files.isDirectory(airDownloads)) {
            return Optional.empty();
        }

        try (Stream<Path> versions = Files.list(airDownloads)) {
            return versions
                    .filter(Files::isDirectory)
                    .map(version -> version.resolve(HABBO_APP_NAME))
                    .filter(app -> Files.isRegularFile(app.resolve(HABBO_EXECUTABLE)))
                    .max(Comparator.comparingLong(MacOSFlashClientResigner::lastModified))
                    .map(MacOSFlashClientResigner::new);
        }
    }

    public Path getHabboApp() {
        return habboApp;
    }

    public boolean isPrepared() throws IOException, InterruptedException {
        final CommandResult entitlements = run("/usr/bin/codesign", "-d", "--entitlements", "-", executable.toString());
        final CommandResult details = run("/usr/bin/codesign", "-dv", "--verbose=4", executable.toString());

        final boolean adHoc = details.output().lines()
                .anyMatch(line -> line.equals("Signature=adhoc"));
        final boolean hardenedRuntime = details.output().lines()
                .filter(line -> line.startsWith("CodeDirectory "))
                .anyMatch(line -> line.contains("runtime"));

        return entitlements.exitCode() == 0
                && entitlements.output().contains(GET_TASK_ALLOW)
                && (entitlements.output().contains("<true/>")
                    || entitlements.output().contains("[Bool] true"))
                && details.exitCode() == 0
                && adHoc
                && !hardenedRuntime;
    }

    public boolean isRunning() {
        return ProcessHandle.allProcesses().anyMatch(process -> process.info().command()
                .map(Path::of)
                .map(Path::toAbsolutePath)
                .map(Path::normalize)
                .filter(executable::equals)
                .isPresent());
    }

    public Path resign() throws IOException, InterruptedException {
        if (isRunning()) {
            throw new IOException("Habbo is running. Close it before preparing the client.");
        }

        final Path temporaryDirectory = Files.createTempDirectory("gearth-habbo-");
        final Path temporaryApp = temporaryDirectory.resolve(HABBO_APP_NAME);
        final Path entitlements = temporaryDirectory.resolve("habbo.entitlements");
        Path stagingDirectory = null;
        Path backup = null;

        try {
            requireSuccess(run("/usr/bin/ditto", habboApp.toString(), temporaryApp.toString()), "copy Habbo");
            Files.writeString(entitlements, ENTITLEMENTS, StandardCharsets.UTF_8);

            final Path temporaryExecutable = temporaryApp.resolve(HABBO_EXECUTABLE);
            // Deep signing and Hardened Runtime break the bundled AIR runtime.
            requireSuccess(run(
                    "/usr/bin/codesign", "--force", "--sign", "-",
                    "--entitlements", entitlements.toString(),
                    temporaryExecutable.toString()
            ), "sign the Habbo executable");

            final MacOSFlashClientResigner prepared = new MacOSFlashClientResigner(temporaryApp);
            if (!prepared.isPrepared()) {
                throw new IOException("The prepared Habbo executable did not pass signature verification.");
            }

            final Path parent = habboApp.getParent();
            stagingDirectory = Files.createTempDirectory(parent, ".gearth-habbo-stage-");
            final Path stagedApp = stagingDirectory.resolve(HABBO_APP_NAME);
            requireSuccess(run("/usr/bin/ditto", temporaryApp.toString(), stagedApp.toString()), "stage Habbo");

            backup = nextBackupPath(parent);
            Files.move(habboApp, backup);
            try {
                move(stagedApp, habboApp);
            } catch (IOException installError) {
                try {
                    move(backup, habboApp);
                    backup = null;
                } catch (IOException rollbackError) {
                    installError.addSuppressed(rollbackError);
                }
                throw installError;
            }

            return backup;
        } finally {
            deleteRecursively(temporaryDirectory);
            if (stagingDirectory != null) {
                deleteRecursively(stagingDirectory);
            }
        }
    }

    private Path nextBackupPath(Path parent) {
        Path candidate = parent.resolve("Habbo.gearth-original.app");
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = parent.resolve("Habbo.gearth-original-" + suffix++ + ".app");
        }
        return candidate;
    }

    private static void move(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(source, destination);
        }
    }

    private static CommandResult run(String... command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final int exitCode = process.waitFor();
        return new CommandResult(exitCode, output);
    }

    private static void requireSuccess(CommandResult result, String operation) throws IOException {
        if (result.exitCode() != 0) {
            throw new IOException("Failed to " + operation + ": " + result.output().trim());
        }
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static void deleteRecursively(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }

        try (Stream<Path> paths = Files.walk(path)) {
            paths.sorted(Comparator.reverseOrder()).forEach(item -> {
                try {
                    Files.deleteIfExists(item);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            });
        } catch (IOException ignored) {
            // Best-effort cleanup.
        }
    }

    private record CommandResult(int exitCode, String output) {
    }
}

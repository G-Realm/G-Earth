package gearth.app.protocol.connection.proxy.nitro.os.linux;

import gearth.app.protocol.connection.proxy.nitro.os.NitroOsFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public abstract class NitroLinux implements NitroOsFunctions {
    private static final Logger log = LoggerFactory.getLogger(NitroLinux.class);

    protected static final String[] PROXY_IGNORE_LIST = {
            "localhost", "127.0.0.1", "::1",
            "discord.com", "discordapp.com", "canary.discord.com",
            "canary.discordapp.com", "github.com", "gateway.discord.gg"
    };

    // --- Certificate Management ---
    @Override
    public boolean isRootCertificateTrusted(File certificate) {
        Path pkiStorePath = Paths.get("/usr/local/share/ca-certificates/gearth-root.crt");
        Path rhelStorePath = Paths.get("/etc/pki/ca-trust/source/anchors/gearth-root.crt");
        return Files.exists(pkiStorePath) || Files.exists(rhelStorePath);
    }

    @Override
    public boolean installRootCertificate(File certificate) {
        try {
            String certPath = certificate.toPath().normalize().toAbsolutePath().toString();

            // Register in Chrome/Firefox NSS DB if available
            Path nssDb = Paths.get(System.getProperty("user.home"), ".pki", "nssdb");
            if (Files.exists(nssDb)) {
                runCommand("certutil", "-d", "sql:" + nssDb, "-A", "-t", "TCu,cu,cu", "-n", "G-Earth Root CA", "-i", certPath);
            }

            // System Trust Store
            boolean isDebianBased = Files.exists(Paths.get("/etc/debian_version"));
            String targetPath = isDebianBased
                    ? "/usr/local/share/ca-certificates/gearth-root.crt"
                    : "/etc/pki/ca-trust/source/anchors/gearth-root.crt";
            String updateCommand = isDebianBased ? "update-ca-certificates" : "update-ca-trust";

            CommandResult result = runCommand(
                    "pkexec", "sh", "-c",
                    String.format("cp '%s' '%s' && %s", certPath, targetPath, updateCommand)
            );

            return result.exitCode() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("Failed to install root certificate", e);
            return false;
        }
    }

    protected boolean commandExists(String command) {
        try {
            return runCommand("which", command).exitCode() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    protected CommandResult runCommand(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return new CommandResult(process.waitFor(), output);
    }

    protected record CommandResult(int exitCode, String output) {}
}
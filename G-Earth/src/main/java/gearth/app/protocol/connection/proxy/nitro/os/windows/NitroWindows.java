package gearth.app.protocol.connection.proxy.nitro.os.windows;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.Shell32;
import com.sun.jna.platform.win32.ShellAPI.SHELLEXECUTEINFO;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.ptr.IntByReference;
import gearth.app.protocol.connection.proxy.nitro.os.NitroOsFunctions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.HexFormat;

public class NitroWindows implements NitroOsFunctions {

    private static final Logger log = LoggerFactory.getLogger(NitroWindows.class);
    private static final String INTERNET_SETTINGS =
            "HKEY_CURRENT_USER\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";

    /**
     * Semicolon separated hosts to ignore for proxying.
     */
    private static final String PROXY_IGNORE = "discord.com;discordapp.com;canary.discord.com;canary.discordapp.com;github.com;gateway.discord.gg;";

    /**
     * Checks if the certificate is trusted by the local machine.
     * @param certificate Absolute path to the certificate.
     * @return true if trusted
     */
    @Override
    public boolean isRootCertificateTrusted(File certificate) {
        try {
            final CommandResult result = runCommand(
                    "certutil.exe", "-verifystore", "Root", certificateThumbprint(certificate)
            );
            if (result.exitCode() != 0) {
                log.debug("Certificate is not trusted by the local machine: {}", result.output());
            }
            return result.exitCode() == 0;
        } catch (IOException | InterruptedException | CertificateException | NoSuchAlgorithmException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to check if root certificate is trusted", e);
            return false;
        }
    }

    @Override
    public boolean installRootCertificate(File certificate) {
        final String certificatePath = certificate.toPath().normalize().toAbsolutePath().toString();

        final SHELLEXECUTEINFO executeInfo = new SHELLEXECUTEINFO();
        executeInfo.fMask = Shell32.SEE_MASK_NOCLOSEPROCESS | Shell32.SEE_MASK_FLAG_NO_UI;
        executeInfo.lpVerb = "runas";
        executeInfo.lpFile = "certutil.exe";
        executeInfo.lpParameters = "-f -addstore Root \"" + certificatePath + "\"";
        executeInfo.nShow = 0;

        if (!Shell32.INSTANCE.ShellExecuteEx(executeInfo)) {
            log.error("Failed to start certificate installation: {}", Native.getLastError());
            return false;
        }

        if (executeInfo.hProcess == null) {
            log.error("Certificate installation did not return a process handle");
            return false;
        }

        try {
            final int waitResult = Kernel32.INSTANCE.WaitForSingleObject(executeInfo.hProcess, WinBase.INFINITE);
            if (waitResult != WinBase.WAIT_OBJECT_0) {
                log.error("Failed to wait for certificate installation: {}", Native.getLastError());
                return false;
            }

            final IntByReference exitCode = new IntByReference();
            if (!Kernel32.INSTANCE.GetExitCodeProcess(executeInfo.hProcess, exitCode)) {
                log.error("Failed to read certificate installation result: {}", Native.getLastError());
                return false;
            }

            if (exitCode.getValue() != 0) {
                log.error("Certificate installation failed with exit code {}", exitCode.getValue());
                return false;
            }
        } finally {
            Kernel32.INSTANCE.CloseHandle(executeInfo.hProcess);
        }

        return isRootCertificateTrusted(certificate);
    }

    @Override
    public boolean registerSystemProxy(String host, int port) {
        try {
            final String proxy = String.format("%s:%d", host, port);

            return setRegistryValue("ProxyServer", "REG_SZ", proxy)
                    && setRegistryValue("ProxyOverride", "REG_SZ", PROXY_IGNORE)
                    && setRegistryValue("ProxyEnable", "REG_DWORD", "1");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to register system proxy", e);
            return false;
        }
    }

    @Override
    public boolean unregisterSystemProxy() {
        try {
            return setRegistryValue("ProxyEnable", "REG_DWORD", "0");
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            log.error("Failed to unregister system proxy", e);
            return false;
        }
    }

    private boolean setRegistryValue(String name, String type, String value)
            throws IOException, InterruptedException {
        final CommandResult result = runCommand(
                "reg.exe", "add", INTERNET_SETTINGS, "/v", name, "/t", type, "/d", value, "/f"
        );
        if (result.exitCode() != 0) {
            log.error("Failed to set {} (exit code {}): {}", name, result.exitCode(), result.output());
            return false;
        }
        return true;
    }

    private String certificateThumbprint(File certificate)
            throws IOException, CertificateException, NoSuchAlgorithmException {
        final X509Certificate parsed;
        try (var input = Files.newInputStream(certificate.toPath())) {
            parsed = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(input);
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(parsed.getEncoded()));
    }

    private CommandResult runCommand(String... command) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return new CommandResult(process.waitFor(), output);
    }

    private record CommandResult(int exitCode, String output) {
    }
}

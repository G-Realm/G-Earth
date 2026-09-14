package gearth.app.protocol.connection.proxy.nitro.os.macos;

import gearth.app.protocol.connection.proxy.http.HttpProxyAuthority;
import gearth.app.protocol.connection.proxy.nitro.os.NitroOsFunctions;
import gearth.app.protocol.hostreplacer.hostsfile.MacOSPrivilegedHostHelper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.util.Collection;

public class NitroMacOS implements NitroOsFunctions {

    private static final Logger LOG = LoggerFactory.getLogger(NitroMacOS.class);
    private static final Path LOGIN_KEYCHAIN = Path.of(
            System.getProperty("user.home"), "Library", "Keychains", "login.keychain-db"
    );
    private static final Path SYSTEM_KEYCHAIN = Path.of("/Library/Keychains/System.keychain");

    private final MacOSPrivilegedHostHelper helper = new MacOSPrivilegedHostHelper();
    private MacOSPrivilegedHostHelper.ProxyLease proxyLease;

    @Override
    public boolean isRootCertificateTrusted(File certificate) {
        if (!keychainContains(certificate, LOGIN_KEYCHAIN)
                && !keychainContains(certificate, SYSTEM_KEYCHAIN)) {
            return false;
        }

        try {
            final Process process = new ProcessBuilder(
                    "/usr/bin/security", "verify-cert", "-c", certificate.getCanonicalPath()
            ).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Error while checking certificate trust", e);
            return false;
        }
    }

    @Override
    public boolean installRootCertificate(File certificate) {
        try {
            final Process process = new ProcessBuilder(
                    "/usr/bin/security", "add-trusted-cert", "-r", "trustRoot",
                    "-k", LOGIN_KEYCHAIN.toString(), certificate.getCanonicalPath()
            ).redirectErrorStream(true).start();
            final String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            final int exitCode = process.waitFor();
            if (exitCode != 0) {
                LOG.error("Failed to trust the Nitro root certificate: {}", output.trim());
                return false;
            }
            return isRootCertificateTrusted(certificate);
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Failed to trust the Nitro root certificate", e);
            return false;
        }
    }

    private boolean keychainContains(File certificate, Path keychain) {
        try {
            final Process process = new ProcessBuilder(
                    "/usr/bin/security", "find-certificate", "-a", "-p",
                    "-c", HttpProxyAuthority.CERT_DESCRIPTION, keychain.toString()
            ).redirectErrorStream(true).start();
            final byte[] output = process.getInputStream().readAllBytes();
            if (process.waitFor() != 0 || output.length == 0) {
                return false;
            }

            final CertificateFactory factory = CertificateFactory.getInstance("X.509");
            final Certificate expected;
            try (var input = Files.newInputStream(certificate.toPath())) {
                expected = factory.generateCertificate(input);
            }

            final Collection<? extends Certificate> installed = factory.generateCertificates(
                    new ByteArrayInputStream(output)
            );
            return installed.contains(expected);
        } catch (IOException | CertificateException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            LOG.error("Failed to inspect Nitro root certificate", e);
            return false;
        }
    }

    @Override
    public synchronized boolean registerSystemProxy(String host, int port) {
        if (!"127.0.0.1".equals(host) || proxyLease != null) {
            return false;
        }
        proxyLease = helper.acquireProxy(port);
        return proxyLease != null;
    }

    @Override
    public synchronized boolean unregisterSystemProxy() {
        if (proxyLease == null) {
            return true;
        }
        try {
            proxyLease.close();
            return true;
        } catch (IOException e) {
            LOG.error("Error while unregistering system proxy", e);
            return false;
        } finally {
            proxyLease = null;
        }
    }
}

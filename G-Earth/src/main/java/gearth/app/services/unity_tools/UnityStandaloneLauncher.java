package gearth.app.services.unity_tools;

import gearth.app.misc.Cacher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.tukaani.xz.XZInputStream;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class UnityStandaloneLauncher {

    private static final Logger LOG = LoggerFactory.getLogger(UnityStandaloneLauncher.class);

    private static final String LAUNCHER_BASE = System.getenv("APPDATA") + "\\Habbo Launcher\\downloads\\unity";
    private static final String CLIENT_NAME = "habbo2020-global-prod";
    private static final String EXE_NAME = CLIENT_NAME + ".exe";
    private static final String AGENT_NAME = "agent.js";
    private static final String AGENT_RESOURCE = "/gearth/services/unity_tools/agent.js";
    private static final String INJECTOR_NAME = "frida-inject-x86.exe";

    private static final String FRIDA_VERSION = "17.9.11";
    private static final String INJECTOR_URL = "https://github.com/frida/frida/releases/download/"
            + FRIDA_VERSION + "/frida-inject-" + FRIDA_VERSION + "-windows-x86.exe.xz";
    private static final String INJECTOR_SHA256 = "ad34d6639bc3058823f0d59433684e70e940f54dd59aa08e144c8166e1288011";

    private final List<RandomAccessFile> claimFiles = new ArrayList<>();
    private final List<FileLock> claimLocks = new ArrayList<>();

    // attach to a free running client, else spawn one
    public Optional<Process> connect(int bridgePort) {
        for (long pid : findRunningClients()) {
            if (claim(pid)) {
                Optional<Process> attached = run(new String[]{"-p", Long.toString(pid)}, bridgePort, "attaching to running pid " + pid, null);
                if (attached.isPresent()) return attached;
                release();
            }
        }

        Optional<Path> clientExe = findClientExe();
        if (clientExe.isEmpty()) {
            LOG.error("Habbo Unity standalone client not found under {}", LAUNCHER_BASE);
            return Optional.empty();
        }

        Set<Long> before = new HashSet<>(findRunningClients());
        Optional<Process> spawned = run(new String[]{"-f", clientExe.get().toAbsolutePath().toString()}, bridgePort, "spawning " + clientExe.get(), clientExe.get().getParent().toFile());
        if (spawned.isPresent()) claimSpawned(before);
        return spawned;
    }

    // drop the claims so the clients can be hooked again
    public void release() {
        for (FileLock lock : claimLocks) {
            try { lock.release(); } catch (Exception ignored) {}
        }
        for (RandomAccessFile file : claimFiles) {
            try { file.close(); } catch (Exception ignored) {}
        }
        claimLocks.clear();
        claimFiles.clear();
    }

    // per pid lock file marks a client taken, tryLock is atomic and the os frees it on exit
    private boolean claim(long pid) {
        try {
            File lockFile = new File(cacheDir(), "hooked-" + pid + ".lock");
            RandomAccessFile raf = new RandomAccessFile(lockFile, "rw");
            FileLock lock = raf.getChannel().tryLock();
            if (lock == null) { raf.close(); return false; }
            claimFiles.add(raf);
            claimLocks.add(lock);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void claimSpawned(Set<Long> before) {
        Thread t = new Thread(() -> {
            for (int i = 0; i < 60; i++) {
                try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                for (long pid : findRunningClients()) {
                    if (!before.contains(pid) && claim(pid)) return;
                }
            }
        }, "unity-standalone-claim");
        t.setDaemon(true);
        t.start();
    }

    private static List<Long> findRunningClients() {
        return ProcessHandle.allProcesses()
                .filter(p -> p.info().command().map(c -> c.toLowerCase().contains(CLIENT_NAME)).orElse(false))
                .map(ProcessHandle::pid)
                .collect(Collectors.toList());
    }

    private Optional<Process> run(String[] target, int bridgePort, String what, File workingDir) {
        File agent = resolveAgent();
        if (agent == null) {
            LOG.error("agent.js could not be extracted");
            return Optional.empty();
        }

        File injector = resolveInjector();
        if (injector == null) {
            LOG.error("frida-inject is not available and could not be downloaded");
            return Optional.empty();
        }

        try {
            LOG.info("Habbo Unity standalone: {}", what);
            List<String> command = new ArrayList<>();
            command.add(injector.getAbsolutePath());
            command.addAll(Arrays.asList(target));
            command.add("-s");
            command.add(agent.getAbsolutePath());
            command.add("-R");
            command.add("v8");

            ProcessBuilder builder = new ProcessBuilder(command);
            if (workingDir != null) builder.directory(workingDir);
            builder.environment().put("GEARTH_BRIDGE_PORT", Integer.toString(bridgePort));
            builder.redirectErrorStream(true);
            Process process = builder.start();

            Thread drain = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) LOG.info("[unity] {}", line);
                } catch (IOException ignored) {
                }
            }, "unity-standalone-output");
            drain.setDaemon(true);
            drain.start();

            return Optional.of(process);
        } catch (IOException e) {
            LOG.error("Failed to launch frida-inject", e);
            return Optional.empty();
        }
    }

    private static Optional<Path> findClientExe() {
        File base = new File(LAUNCHER_BASE);
        if (!base.isDirectory()) return Optional.empty();

        Path newest = null;
        long newestTime = 0;
        for (File buildDir : orEmpty(base.listFiles(File::isDirectory))) {
            for (File platformDir : orEmpty(buildDir.listFiles(File::isDirectory))) {
                File exe = new File(platformDir, EXE_NAME);
                if (exe.isFile() && exe.lastModified() > newestTime) {
                    newestTime = exe.lastModified();
                    newest = exe.toPath();
                }
            }
        }
        return Optional.ofNullable(newest);
    }

    private File resolveAgent() {
        try (InputStream in = getClass().getResourceAsStream(AGENT_RESOURCE)) {
            if (in == null) return null;
            File target = new File(cacheDir(), AGENT_NAME);
            try (OutputStream out = new FileOutputStream(target)) {
                in.transferTo(out);
            }
            return target;
        } catch (IOException e) {
            LOG.error("Failed to extract agent.js", e);
            return null;
        }
    }

    private File resolveInjector() {
        File cached = new File(cacheDir(), INJECTOR_NAME);
        if (cached.isFile() && INJECTOR_SHA256.equalsIgnoreCase(sha256(cached))) return cached;
        return downloadInjector(cached);
    }

    private static File cacheDir() {
        File dir = new File(Cacher.getCacheDir(), "unity-standalone");
        dir.mkdirs();
        return dir;
    }

    private File downloadInjector(File target) {
        try {
            target.getParentFile().mkdirs();
            LOG.info("Downloading frida-inject {} (first run, ~19 MB)", FRIDA_VERSION);

            HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            HttpRequest request = HttpRequest.newBuilder(URI.create(INJECTOR_URL)).GET().build();
            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() != 200) {
                LOG.error("frida-inject download failed, http {}", response.statusCode());
                return null;
            }

            File part = new File(target.getParentFile(), INJECTOR_NAME + ".part");
            try (XZInputStream xz = new XZInputStream(response.body());
                 OutputStream out = new FileOutputStream(part)) {
                xz.transferTo(out);
            }

            String hash = sha256(part);
            if (!INJECTOR_SHA256.equalsIgnoreCase(hash)) {
                LOG.error("frida-inject checksum mismatch, refusing to use it (got {})", hash);
                part.delete();
                return null;
            }

            Files.move(part.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            LOG.info("frida-inject ready at {}", target);
            return target;
        } catch (Exception e) {
            LOG.error("Failed to download frida-inject", e);
            return null;
        }
    }

    private static String sha256(File file) {
        try (InputStream in = new FileInputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[1 << 16];
            int read;
            while ((read = in.read(buffer)) > 0) digest.update(buffer, 0, read);
            StringBuilder out = new StringBuilder();
            for (byte b : digest.digest()) out.append(String.format("%02x", b));
            return out.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static File[] orEmpty(File[] arr) {
        return arr != null ? arr : new File[0];
    }
}

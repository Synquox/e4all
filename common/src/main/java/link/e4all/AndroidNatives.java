package link.e4all;

import java.io.InputStream;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

// Android support for the native QUIC stack.
// Pojav-style launchers run desktop OpenJDK so Netty's Dalvik check misses and loads glibc.
// We unpack the bundled Bionic build and set link.e4mc.native_path.
public final class AndroidNatives {
    private static final Logger LOGGER = LoggerFactory.getLogger(E4allClient.MOD_ID);

    private static final String QUICHE_NATIVE = "/assets/e4all/natives/android-aarch64/libnetty_quiche.so";

    private static volatile boolean prepared = false;
    private static volatile boolean quicheNativeAvailable = false;
    private static volatile boolean irohNativeAvailable = false;

    private AndroidNatives() {}

    // Called once from mod init before anything touches a native lib
    public static synchronized void prepare() {
        if (prepared) {
            return;
        }
        prepared = true;
        try {
            if (!AndroidDetector.isAndroid()) {
                return;
            }

            Path targetDir = pickWritableExecDir();
            if (targetDir == null) {
                LOGGER.error("e4all: Android detected but no writable directory found for native libraries; "
                        + "QUIC tunnel will fail. Check that the launcher's java.io.tmpdir or config dir is writable.");
                return;
            }

            quicheNativeAvailable = "aarch64".equals(System.getProperty("os.arch"))
                    && extractNative(QUICHE_NATIVE, targetDir, "link.e4mc.native_path", "quiche");
            preloadCxxRuntime(targetDir);

            // libiroh_java.so wants an initialized android app context (ndk-context) that a
            // launcher JVM never has, and touching it aborts the whole process. dialtone p2p
            // stays off, relay hosting doesn't use it.

            if (!quicheNativeAvailable) {
                LOGGER.warn("e4all: No Bionic QUIC native bundled for this device (os.arch={}). "
                        + "Relay hosting will not work.", System.getProperty("os.arch"));
            }
            if (!irohNativeAvailable && Config.INSTANCE != null) {
                LOGGER.info("e4all: No Bionic iroh native for this device; Dialtone direct connections "
                        + "will be disabled on Android (relayed play still works).");
            }
            LOGGER.info("e4all: Android native preparation done. quiche={}, iroh={}", quicheNativeAvailable, irohNativeAvailable);
        } catch (Throwable t) {
            LOGGER.error("e4all: Failed to prepare Android natives; falling back to default loader behavior.", t);
            quicheNativeAvailable = false;
            irohNativeAvailable = false;
        }
    }

    public static boolean hasQuicheNative() {
        return quicheNativeAvailable;
    }

    public static boolean hasIrohNative() {
        return irohNativeAvailable;
    }

    // Candidate directories (writable + exec)
    private static Path pickWritableExecDir() {
        String[] candidates = {
                System.getProperty("java.io.tmpdir"),   // Pojav-family launchers point this at app-private cache
                System.getProperty("user.home"),         // launcher-managed home, usually app files dir
        };
        for (String raw : candidates) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            try {
                Path dir = Path.of(raw);
                Files.createDirectories(dir);
                if (!Files.isWritable(dir)) {
                    LOGGER.debug("e4all: {} not writable, trying next candidate", dir);
                    continue;
                }
                Path target = dir.resolve("e4all-natives");
                // TMPDIR exists but e4all-natives doesn't; Files.copy won't create parents
                Files.createDirectories(target);
                if (!Files.isWritable(target)) {
                    LOGGER.debug("e4all: {} not writable, trying next candidate", target);
                    continue;
                }
                return target;
            } catch (Throwable t) {
                LOGGER.debug("e4all: candidate dir {} unusable: {}", raw, t.toString());
            }
        }
        return null;
    }

    private static void preloadCxxRuntime(Path targetDir) {
        String resource = "/assets/e4all/natives/android-" + System.getProperty("os.arch", "") + "/libc++_shared.so";
        try (InputStream in = AndroidNatives.class.getResourceAsStream(resource)) {
            if (in == null) {
                return;
            }
            Path out = targetDir.resolve("libc++_shared.so");
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            String abs = out.toAbsolutePath().toString();
            try {
                System.load(abs);
            } catch (Throwable ignored) {
                // shim path below is a second chance
            }
            if (loadCxxShim(targetDir) && preloadCxx0(abs)) {
                LOGGER.info("e4all: preloaded libc++ runtime for Bionic natives (RTLD_GLOBAL)");
            }
        } catch (Throwable t) {
            LOGGER.warn("e4all: could not preload libc++ runtime, the quiche native may fail to load", t);
        }
    }

    private static boolean loadCxxShim(Path targetDir) {
        String resource = "/assets/e4all/natives/android-" + System.getProperty("os.arch", "") + "/libcxxpreload.so";
        try (InputStream in = AndroidNatives.class.getResourceAsStream(resource)) {
            if (in == null) {
                return false;
            }
            Path out = targetDir.resolve("libcxxpreload.so");
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            System.load(out.toAbsolutePath().toString());
            return true;
        } catch (Throwable t) {
            LOGGER.warn("e4all: could not load the libcxxpreload shim", t);
            return false;
        }
    }

    private static native boolean preloadCxx0(String path);


    private static boolean extractNative(String resource, Path targetDir,
                                         String propertyName, String logName) {
        try {
            Path outFile = targetDir.resolve(resource.substring(resource.lastIndexOf('/') + 1));
            try (InputStream in = AndroidNatives.class.getResourceAsStream(resource)) {
                if (in == null) {
                    LOGGER.debug("e4all: no bundled {} native at {}", logName, resource);
                    return false;
                }
                Files.copy(in, outFile, StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                java.nio.file.attribute.PosixFileAttributeView posix =
                        Files.getFileAttributeView(outFile, java.nio.file.attribute.PosixFileAttributeView.class);
                if (posix != null) {
                    posix.setPermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"));
                }
            } catch (Throwable ignored) {
                // chmod is ignored on FAT-style mounts anyway, and dlopen doesn't require +x.
            }
            System.setProperty(propertyName, outFile.toAbsolutePath().toString());
            LOGGER.info("e4all: using bundled Bionic {} native: {}", logName, outFile);
            return true;
        } catch (Throwable t) {
            LOGGER.warn("e4all: could not prepare {} native for Android", logName, t);
            return false;
        }
    }
}

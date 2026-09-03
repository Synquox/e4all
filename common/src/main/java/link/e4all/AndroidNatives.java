package link.e4all;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;

// Android support for the native QUIC stack.
// Pojav-style launchers run desktop OpenJDK so Netty's Dalvik check misses and loads glibc.
// We unpack the bundled Bionic build and set link.e4mc.native_path.
public final class AndroidNatives {
    private static final Logger LOGGER = LoggerFactory.getLogger(E4allClient.MOD_ID);

    private static final Map<String, String> QUICHE_NATIVES = new LinkedHashMap<>();

    static {
        // os.arch reports "aarch64" on ARM Android; 32-bit arm devices are out of scope.
        QUICHE_NATIVES.put("aarch64", "/assets/e4all/natives/android-aarch64/libnetty_quiche.so");
        QUICHE_NATIVES.put("x86_64", "/assets/e4all/natives/android-x86_64/libnetty_quiche.so");
    }


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

            quicheNativeAvailable = extractNative(QUICHE_NATIVES, targetDir, "link.e4mc.native_path", "quiche");
            // libiroh_java.so wants an initialized android app context (ndk-context) that a
            // launcher JVM never has, and touching it aborts the whole process. dialtone p2p
            // stays off, relay hosting doesn't use it.
            String cxxSoname = quicheNativeAvailable
                    ? patchQuicheDep(targetDir.resolve("libnetty_quiche.so"))
                    : null;
            preloadCxxRuntime(targetDir, cxxSoname);

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
                System.getProperty("java.io.tmpdir", "") + "/e4all-natives"
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

    // libnetty_quiche.so needs a C++ runtime on Bionic. Preload libc++ and repoint liblog.so
    private static void preloadCxxRuntime(Path targetDir, String cxxSoname) {
        String resource = "/assets/e4all/natives/android-" + System.getProperty("os.arch", "") + "/libc++_shared.so";
        try (InputStream in = AndroidNatives.class.getResourceAsStream(resource)) {
            if (in == null) {
                return;
            }
            Path out = targetDir.resolve("libc++_shared.so");
            Files.copy(in, out, StandardCopyOption.REPLACE_EXISTING);
            renameSoname(out, cxxSoname != null ? cxxSoname : "e4all_cxx.so");
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

    // has to fit the old 16 char "libc++_shared.so" slot
    private static void renameSoname(Path so, String soname) throws Exception {
        byte[] b = Files.readAllBytes(so);
        ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
        long phoff = buf.getLong(0x20);
        int phentsize = buf.getShort(0x36) & 0xffff;
        int phnum = buf.getShort(0x38) & 0xffff;
        long dynAddr = -1, dynSize = 0;
        long[] segOff = new long[phnum];
        long[] segVaddr = new long[phnum];
        long[] segSize = new long[phnum];
        int segs = 0;
        for (int i = 0; i < phnum; i++) {
            int p = (int) (phoff + (long) i * phentsize);
            int type = buf.getInt(p);
            if (type == 2) {
                dynAddr = buf.getLong(p + 16);
                dynSize = buf.getLong(p + 32);
            } else if (type == 1) {
                segOff[segs] = buf.getLong(p + 8);
                segVaddr[segs] = buf.getLong(p + 16);
                segSize[segs] = buf.getLong(p + 32);
                segs++;
            }
        }
        long dynOff = -1;
        for (int i = 0; i < segs; i++) {
            if (dynAddr >= segVaddr[i] && dynAddr < segVaddr[i] + segSize[i]) {
                dynOff = dynAddr - segVaddr[i] + segOff[i];
            }
        }
        long strtabAddr = -1, sonameOff = -1;
        for (long p = dynOff; p < dynOff + dynSize; p += 16) {
            long tag = buf.getLong((int) p);
            long val = buf.getLong((int) p + 8);
            if (tag == 0) break;
            if (tag == 5) strtabAddr = val;
            if (tag == 14) sonameOff = val;
        }
        long strOff = -1;
        for (int i = 0; i < segs; i++) {
            if (strtabAddr >= segVaddr[i] && strtabAddr < segVaddr[i] + segSize[i]) {
                strOff = strtabAddr - segVaddr[i] + segOff[i];
            }
        }
        byte[] name = soname.getBytes(StandardCharsets.US_ASCII);
        int at = (int) (strOff + sonameOff);
        System.arraycopy(name, 0, b, at, name.length);
        b[at + name.length] = 0;
        Files.write(so, b);
    }

    private static String patchQuicheDep(Path quiche) {
        try {
            byte[] b = Files.readAllBytes(quiche);
            ByteBuffer buf = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN);
            long phoff = buf.getLong(0x20);
            int phentsize = buf.getShort(0x36) & 0xffff;
            int phnum = buf.getShort(0x38) & 0xffff;
            long dynAddr = -1, dynSize = 0, strtabAddr = -1, strsz = 0;
            long[] segOff = new long[phnum];
            long[] segVaddr = new long[phnum];
            long[] segSize = new long[phnum];
            int segs = 0;
            for (int i = 0; i < phnum; i++) {
                int p = (int) (phoff + (long) i * phentsize);
                int type = buf.getInt(p);
                if (type == 2) {
                    dynAddr = buf.getLong(p + 16);
                    dynSize = buf.getLong(p + 32);
                } else if (type == 1) {
                    segOff[segs] = buf.getLong(p + 8);
                    segVaddr[segs] = buf.getLong(p + 16);
                    segSize[segs] = buf.getLong(p + 32);
                    segs++;
                }
            }
            long dynOff = -1;
            for (int i = 0; i < segs; i++) {
                if (dynAddr >= segVaddr[i] && dynAddr < segVaddr[i] + segSize[i]) {
                    dynOff = dynAddr - segVaddr[i] + segOff[i];
                }
            }
            for (long p = dynOff; p < dynOff + dynSize; p += 16) {
                long tag = buf.getLong((int) p);
                long val = buf.getLong((int) p + 8);
                if (tag == 0) break;
                if (tag == 5) strtabAddr = val;
                if (tag == 10) strsz = val;
            }
            long strOff = -1;
            for (int i = 0; i < segs; i++) {
                if (strtabAddr >= segVaddr[i] && strtabAddr < segVaddr[i] + segSize[i]) {
                    strOff = strtabAddr - segVaddr[i] + segOff[i];
                }
            }
            byte[] want = "_ZdlPv\0".getBytes(StandardCharsets.US_ASCII);
            int nameOff = -1;
            for (int i = 1; i < (int) strsz - want.length; i++) {
                if (b[(int) strOff + i - 1] != 0) continue;
                boolean hit = true;
                for (int j = 0; j < want.length; j++) {
                    if (b[(int) strOff + i + j] != want[j]) {
                        hit = false;
                        break;
                    }
                }
                if (hit) {
                    nameOff = i;
                    break;
                }
            }
            if (nameOff < 0) {
                return null;
            }
            byte[] dead = "liblog.so\0".getBytes(StandardCharsets.US_ASCII);
            for (long p = dynOff; p < dynOff + dynSize; p += 16) {
                long tag = buf.getLong((int) p);
                long val = buf.getLong((int) p + 8);
                if (tag == 0) break;
                if (tag == 1) {
                    int s = (int) (strOff + val);
                    boolean hit = true;
                    for (int j = 0; j < dead.length; j++) {
                        if (b[s + j] != dead[j]) {
                            hit = false;
                            break;
                        }
                    }
                    if (hit) {
                        buf.putLong((int) p + 8, nameOff);
                        Files.write(quiche, b);
                        return "_ZdlPv";
                    }
                }
            }
            return null;
        } catch (Throwable t) {
            LOGGER.warn("e4all: could not repoint the quiche native's C++ dependency", t);
            return null;
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


    private static boolean extractNative(Map<String, String> resourceMap, Path targetDir,
                                         String propertyName, String logName) {
        try {
            String resource = resourceMap.get(System.getProperty("os.arch", ""));
            if (resource == null) {
                return false;
            }
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

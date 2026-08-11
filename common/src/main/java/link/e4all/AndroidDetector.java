package link.e4all;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

public final class AndroidDetector {
    private static final Logger LOGGER = LoggerFactory.getLogger(E4allClient.MOD_ID);

    private static volatile DetectionResult cachedResult;

    private AndroidDetector() {}

    public static final class DetectionResult {
        private final boolean android;
        private final String reason;

        DetectionResult(boolean android, String reason) {
            this.android = android;
            this.reason = reason;
        }

        public boolean isAndroid() {
            return android;
        }

        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return "DetectionResult{android=" + android + ", reason='" + reason + "'}";
        }
    }

    public static DetectionResult detect() {
        DetectionResult result = cachedResult;
        if (result != null) {
            return result;
        }
        result = performDetection();
        cachedResult = result;
        if (result.isAndroid()) {
            LOGGER.warn("e4all: Android environment detected — {}. Native QUIC/Iroh libraries may not be compatible.", result.reason());
        } else {
            LOGGER.debug("e4all: Not running on Android ({})", result.reason());
        }
        return result;
    }

    public static boolean isAndroid() {
        return detect().isAndroid();
    }

    private static DetectionResult performDetection() {
        try {
            String vmName = System.getProperty("java.vm.name", "");
            if (vmName.toLowerCase(java.util.Locale.ROOT).contains("dalvik")
                    || vmName.toLowerCase(java.util.Locale.ROOT).contains("art")) {
                return new DetectionResult(true, "java.vm.name contains Dalvik/ART: " + vmName);
            }
        } catch (SecurityException ignored) {}

        try {
            String runtimeName = System.getProperty("java.runtime.name", "");
            if (runtimeName.toLowerCase(java.util.Locale.ROOT).contains("android")) {
                return new DetectionResult(true, "java.runtime.name contains 'android': " + runtimeName);
            }
        } catch (SecurityException ignored) {}

        if (new File("/system/build.prop").exists()) {
            return new DetectionResult(true, "/system/build.prop exists (Android system partition)");
        }

        try {
            Class.forName("android.os.Build", false, ClassLoader.getSystemClassLoader());
            return new DetectionResult(true, "android.os.Build class found on system classloader");
        } catch (ClassNotFoundException ignored) {}
        try {
            ClassLoader tcl = Thread.currentThread().getContextClassLoader();
            if (tcl != null) {
                Class.forName("android.os.Build", false, tcl);
                return new DetectionResult(true, "android.os.Build class found on thread context classloader");
            }
        } catch (ClassNotFoundException ignored) {}

        String osName = System.getProperty("os.name", "");
        if (osName.toLowerCase(java.util.Locale.ROOT).contains("linux")) {
            if (new File("/system/lib64/libc.so").exists()
                    || new File("/system/lib/libc.so").exists()) {
                return new DetectionResult(true, "bionic libc found at /system/lib[64]/libc.so");
            }

            if (new File("/system/bin/linker64").exists()
                    || new File("/system/bin/linker").exists()) {
                return new DetectionResult(true, "Android bionic linker found at /system/bin/linker[64]");
            }
        }

        try {
            String userHome = System.getProperty("user.home", "");
            if (osName.toLowerCase(java.util.Locale.ROOT).contains("linux")
                    && userHome.startsWith("/data/")
                    && new File("/system/bin/sh").exists()) {
                return new DetectionResult(true, "Linux with user.home under /data/ and Android-style /system/bin/sh present (likely Pojav-style launcher): " + userHome);
            }
        } catch (SecurityException ignored) {}

        return new DetectionResult(false, "no Android indicators found");
    }
}

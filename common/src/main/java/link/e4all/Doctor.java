package link.e4all;

import com.google.gson.Gson;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HexFormat;

public class Doctor {
    public static String doctor() {
        var result = new StringBuilder();
        result.append("mod sha512sum: ");
        try {
            var bytes = Files.readAllBytes(Agnos.jarPath());
            var md = MessageDigest.getInstance("SHA-512");
            var digest = md.digest(bytes);
            result.append(HexFormat.of().formatHex(digest));
        } catch (Exception e) {
            result.append("exception during digest:\n");
            var baos = new ByteArrayOutputStream();
            e.printStackTrace(new PrintStream(baos, true, StandardCharsets.UTF_8));
            result.append(baos.toString(StandardCharsets.UTF_8));
        }
        result.append("\n");
        result.append("platform info:\n");
        result.append("  os.name: ").append(System.getProperty("os.name", "unknown")).append("\n");
        result.append("  os.arch: ").append(System.getProperty("os.arch", "unknown")).append("\n");
        result.append("  java.vm.name: ").append(System.getProperty("java.vm.name", "unknown")).append("\n");
        result.append("  java.runtime.name: ").append(System.getProperty("java.runtime.name", "unknown")).append("\n");
        result.append("  user.home: ").append(System.getProperty("user.home", "unknown")).append("\n");
        var androidResult = AndroidDetector.detect();
        result.append("  android detected: ").append(androidResult.isAndroid()).append("\n");
        result.append("  android detection reason: ").append(androidResult.reason()).append("\n");
        if (androidResult.isAndroid()) {
            result.append("  android quiche native available: ")
                  .append(AndroidNatives.hasQuicheNative()).append("\n");
            result.append("  android dialtone (iroh) native available: ")
                  .append(AndroidNatives.hasIrohNative()).append("\n");
        }
        String nativePath = System.getProperty("link.e4mc.native_path");
        if (nativePath != null) {
            result.append("  link.e4mc.native_path: ").append(nativePath).append("\n");
        }
        String nativeUrl = System.getProperty("link.e4mc.native_url");
        if (nativeUrl != null) {
            result.append("  link.e4mc.native_url: ").append(nativeUrl).append("\n");
        }
        String dialtoneNativePath = System.getProperty("link.e4mc.dialtone.native_path");
        if (dialtoneNativePath != null) {
            result.append("  link.e4mc.dialtone.native_path: ").append(dialtoneNativePath).append("\n");
        }
        String dialtoneNativeUrl = System.getProperty("link.e4mc.dialtone.native_url");
        if (dialtoneNativeUrl != null) {
            result.append("  link.e4mc.dialtone.native_url: ").append(dialtoneNativeUrl).append("\n");
        }
        result.append("QuiclimeSession state: ");
        var session = E4allClient.session;
        if (session != null) {
            result.append(session.state);
            result.append("\n");
            result.append("assigned domain: ");
            result.append(session.assignedDomain != null ? session.assignedDomain : "(none)");
            result.append("\n");
            result.append("reconnect count: ");
            result.append(session.getReconnectCount());
            result.append("\n");
        } else {
            result.append("no session.\n");
        }
        result.append("QuiclimeSession recorded exception:\n");
        if (session != null && session.failureCause != null) {
            var baos = new ByteArrayOutputStream();
            session.failureCause.printStackTrace(new PrintStream(baos, true, StandardCharsets.UTF_8));
            result.append(baos.toString(StandardCharsets.UTF_8));
            result.append("\n");
        } else {
            result.append("none recorded.\n");
        }
        result.append("natives CDN test results:\n");
        try {
            var response = QuiclimeSession.httpFetch(new URI("https://natives.e4mc.link/doctor-test-target"));
            var exceptional = false;
            if (response.status != 200) {
                exceptional = true;
                result.append("status code was not 200, it was: ");
                result.append(response.status);
                result.append("\n");
            }
            if (!response.body.equals("if you can read this, e4mc natives are available. qmqj8c13nzdr0kd10gihcila")) {
                exceptional = true;
                result.append("response was unexpected, got: ");
                result.append(response.body);
                result.append("\n");
            }
            if (!exceptional) {
                result.append("no issues found.\n");
            }
        } catch (Exception e) {
            result.append("exception during request:\n");
            var baos = new ByteArrayOutputStream();
            e.printStackTrace(new PrintStream(baos, true, StandardCharsets.UTF_8));
            result.append(baos.toString(StandardCharsets.UTF_8));
            result.append("\n");
        }
        result.append("broker API test results:\n");
        QuiclimeSession.BrokerResponse brokerResponse = null;
        try {
            if (Config.INSTANCE.useBroker.value()) {
                result.append("using broker ");
                result.append(Config.INSTANCE.brokerUrl.value());
                result.append("\n");
                var response = QuiclimeSession.httpFetch(new URI(Config.INSTANCE.brokerUrl.value()));
                var exceptional = false;
                if (response.status != 200) {
                    exceptional = true;
                    result.append("status code was not 200, it was: ");
                    result.append(response.status);
                    result.append("\n");
                }
                var gson = new Gson();
                brokerResponse = gson.fromJson(response.body, QuiclimeSession.BrokerResponse.class);
                if (!exceptional) {
                    result.append("no issues found.\n");
                }
            } else {
                result.append("not using broker.\n");
                var resp = new QuiclimeSession.BrokerResponse();
                resp.id = "custom";
                resp.host = Config.INSTANCE.relayHost.value();
                resp.port = Config.INSTANCE.relayPort.value();
                brokerResponse = resp;
            }
        } catch (Exception e) {
            result.append("exception during request:\n");
            var baos = new ByteArrayOutputStream();
            e.printStackTrace(new PrintStream(baos, true, StandardCharsets.UTF_8));
            result.append(baos.toString(StandardCharsets.UTF_8));
            result.append("\n");
        }
        result.append("broker response:\n");
        if (brokerResponse == null) {
            result.append("none successfully received.\n");
        } else {
            result.append(String.format("relay id is %s, host is %s, port is %d.\n", brokerResponse.id, brokerResponse.host, brokerResponse.port));
        }
        result.append("relay HTTP test results:\n");
        if (brokerResponse == null) {
            result.append("no broker response.\n");
        } else if (!brokerResponse.host.endsWith(".e4mc.link")) {
            result.append("host is not standard. not attempting ping.\n");
        } else {
            try {
                var response = QuiclimeSession.httpFetch(new URI(String.format("https://%s/ping", brokerResponse.host)));
                var exceptional = false;
                if (response.status != 200) {
                    exceptional = true;
                    result.append("status code was not 200, it was: ");
                    result.append(response.status);
                    result.append("\n");
                }
                if (!response.body.equals("OK")) {
                    exceptional = true;
                    result.append("response was unexpected, got: ");
                    result.append(response.body);
                    result.append("\n");
                }
                if (!exceptional) {
                    result.append("no issues found.\n");
                }
            } catch (Exception e) {
                result.append("exception during request:\n");
                var baos = new ByteArrayOutputStream();
                e.printStackTrace(new PrintStream(baos, true, StandardCharsets.UTF_8));
                result.append(baos.toString(StandardCharsets.UTF_8));
                result.append("\n");
            }
        }
        return result.toString();
    }
}



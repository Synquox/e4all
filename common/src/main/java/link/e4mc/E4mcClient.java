package link.e4mc;

/**
 * Dummy class to provide the e4mc MOD_ID constant to third-party addons.
 * This class ensures that any addon hardcoding `link.e4mc.E4mcClient.MOD_ID`
 * will not crash with a ClassNotFoundException or NoSuchFieldError.
 * 
 * The rest of the mod is isolated in `link.e4all` to avoid conflicts
 * if the real e4mc is installed alongside this fork.
 */
public class E4mcClient {
    public static final String MOD_ID = "e4mc";
}

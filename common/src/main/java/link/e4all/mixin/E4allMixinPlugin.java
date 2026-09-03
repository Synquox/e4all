package link.e4all.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;
import java.util.List;
import java.util.Set;

public class E4allMixinPlugin implements IMixinConfigPlugin {
    private static final boolean HAS_SIGNATURES;
    private static final boolean HAS_COMMON_LISTENER_COOKIE;
    private static final boolean HAS_MULTIPLAYER_OPTIONS_SCREEN;
    private static final boolean HAS_SHARE_TO_LAN_SCREEN;
    private static final boolean HAS_WORLD_OPTIONS_SCREEN;
    static {
        boolean hasSignatures = false;
        try {
            ClassLoader cl = E4allMixinPlugin.class.getClassLoader();
            hasSignatures = cl.getResource("net/minecraft/network/chat/MessageSignature.class") != null
                         || cl.getResource("net/minecraft/network/message/MessageSignatureData.class") != null
                         || cl.getResource("net/minecraft/class_7469.class") != null;
        } catch (Throwable e) {
            // Pre-1.19
        }
        HAS_SIGNATURES = hasSignatures;

        boolean hasCookie = false;
        try {
            ClassLoader cl = E4allMixinPlugin.class.getClassLoader();
            hasCookie = cl.getResource("net/minecraft/server/network/CommonListenerCookie.class") != null
                     || cl.getResource("net/minecraft/class_8673.class") != null;
        } catch (Throwable ignored) {
        }
        HAS_COMMON_LISTENER_COOKIE = hasCookie;

        ClassLoader cl = E4allMixinPlugin.class.getClassLoader();
        HAS_MULTIPLAYER_OPTIONS_SCREEN =
                cl.getResource("net/minecraft/client/gui/screens/MultiplayerOptionsScreen.class") != null
             || cl.getResource("net/minecraft/client/gui/screens/options/MultiplayerOptionsScreen.class") != null;
        HAS_SHARE_TO_LAN_SCREEN =
                cl.getResource("net/minecraft/client/gui/screens/ShareToLanScreen.class") != null
             || cl.getResource("net/minecraft/class_527.class") != null;
        HAS_WORLD_OPTIONS_SCREEN =
                cl.getResource("net/minecraft/client/gui/screens/WorldOptionsScreen.class") != null
             || cl.getResource("net/minecraft/client/gui/screens/options/WorldOptionsScreen.class") != null;
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("PlayerListCookieMixin")) {
            return HAS_COMMON_LISTENER_COOKIE;
        }
        if (mixinClassName.endsWith("PlayerListLegacyMixin")) {
            return !HAS_COMMON_LISTENER_COOKIE;
        }

        if (mixinClassName.contains(".ncr.")) {
            return HAS_SIGNATURES;
        }

        if (mixinClassName.endsWith("MultiplayerOptionsScreenMixin")) {
            return HAS_MULTIPLAYER_OPTIONS_SCREEN;
        }
        if (mixinClassName.endsWith("ShareToLanScreenMixin")) {
            return HAS_SHARE_TO_LAN_SCREEN;
        }
        if (mixinClassName.endsWith("WorldOptionsScreenMixin")) {
            return HAS_WORLD_OPTIONS_SCREEN;
        }

        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}

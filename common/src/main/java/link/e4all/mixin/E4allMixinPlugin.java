package link.e4all.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;
import java.util.List;
import java.util.Set;

public class E4allMixinPlugin implements IMixinConfigPlugin {
    private static final boolean HAS_SIGNATURES;
    private static final boolean HAS_COMMON_LISTENER_COOKIE;
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
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.endsWith("PlayerListCookieMixin")
                || mixinClassName.endsWith("ClientCommonPacketListenerImplMixin")
                || mixinClassName.endsWith("ServerCommonPacketListenerImplMixin")) {
            return HAS_COMMON_LISTENER_COOKIE;
        }
        if (mixinClassName.endsWith("PlayerListLegacyMixin")) {
            return !HAS_COMMON_LISTENER_COOKIE;
        }

        if (mixinClassName.contains(".ncr.")) {
            if (!HAS_SIGNATURES) {
                return false;
            }
            if (targetClassName != null && !targetClassName.isEmpty()) {
                String resourcePath = targetClassName.replace('.', '/') + ".class";
                if (this.getClass().getClassLoader().getResource(resourcePath) == null) {
                    return false;
                }
            }
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

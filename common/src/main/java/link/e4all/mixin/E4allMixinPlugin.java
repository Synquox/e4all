package link.e4all.mixin;

import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;
import org.objectweb.asm.tree.ClassNode;
import java.util.List;
import java.util.Set;

public class E4allMixinPlugin implements IMixinConfigPlugin {
    private static final boolean HAS_SIGNATURES;
    static {
        boolean hasSignatures = false;
        try {
            Class.forName("net.minecraft.network.chat.MessageSignature", false, E4allMixinPlugin.class.getClassLoader());
            hasSignatures = true;
        } catch (Throwable e) {
            // Pre-1.19
        }
        HAS_SIGNATURES = hasSignatures;
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        // Only apply NCR mixins if chat signatures exist in the game version
        if (mixinClassName.contains(".ncr.")) {
            if (!HAS_SIGNATURES) {
                return false;
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

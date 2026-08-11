package link.e4all;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import java.util.concurrent.atomic.AtomicReference;

public final class ResourceLocReflector {
    private ResourceLocReflector() {}

    private static final String[] CANDIDATE_FQNS = {
            "net.minecraft.resources.ResourceLocation",
            "net.minecraft.resources.Identifier"
    };

    private static volatile Class<?> cachedClass;
    private static volatile Method cachedFactory;
    private static volatile Constructor<?> cachedCtor;

    private static final Object LOCK = new Object();

    static Class<?> classOrNull() {
        Class<?> c = cachedClass;
        if (c != null) return c;
        synchronized (LOCK) {
            if (cachedClass == null) {
                ClassLoader[] loaders = collectClassLoaders();
                Class<?> found = null;
                for (String fqn : CANDIDATE_FQNS) {
                    for (ClassLoader cl : loaders) {
                        try {
                            Class<?> probed = Class.forName(fqn, false, cl);
                            if (probed != null) {
                                found = probed;
                                break;
                            }
                        } catch (ClassNotFoundException | LinkageError ignored) {
                        }
                    }
                    if (found != null) break;
                }
                if (found == null) return null;
                cachedClass = found;
            }
            return cachedClass;
        }
    }

    private static ClassLoader[] collectClassLoaders() {
        ClassLoader ctx = null;
        try { ctx = Thread.currentThread().getContextClassLoader(); } catch (SecurityException ignored) {}
        ClassLoader own = ResourceLocReflector.class.getClassLoader();
        ClassLoader sys = ClassLoader.getSystemClassLoader();
        if (ctx != null && ctx != own && ctx != sys) {
            if (own != null && own != sys) return new ClassLoader[]{ctx, own, sys};
            return new ClassLoader[]{ctx, sys};
        }
        if (own != null && own != sys) return new ClassLoader[]{own, sys};
        return new ClassLoader[]{sys};
    }

    public static Class<?> classOrThrow() {
        Class<?> c = classOrNull();
        if (c == null) {
            throw new IllegalStateException(
                    "e4all: could not resolve ResourceLocation/Identifier class on this runtime; "
                            + "Minecraft version may be unsupported");
        }
        return c;
    }

    public static boolean isAvailable() {
        return classOrNull() != null;
    }

    public static Object create(String namespace, String path) {
        Class<?> cls = classOrThrow();
        Method factory = cachedFactory;
        if (factory == null) {
            synchronized (LOCK) {
                if (cachedFactory == null) {
                    try {
                        cachedFactory = cls.getMethod("fromNamespaceAndPath", String.class, String.class);
                    } catch (NoSuchMethodException ignoredFactory) {
                    }
                }
                factory = cachedFactory;
            }
        }
        if (factory != null) {
            try {
                return factory.invoke(null, namespace, path);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "e4all: fromNamespaceAndPath factory failed", e);
            }
        }
        Constructor<?> ctor = cachedCtor;
        if (ctor == null) {
            synchronized (LOCK) {
                if (cachedCtor == null) {
                    try {
                        cachedCtor = cls.getDeclaredConstructor(String.class, String.class);
                    } catch (NoSuchMethodException ignoredNoCtor) {
                    }
                }
                ctor = cachedCtor;
            }
        }
        if (ctor != null) {
            try {
                ctor.setAccessible(true);
                return ctor.newInstance(namespace, path);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(
                        "e4all: ResourceLocation/Identifier(String, String) constructor failed", e);
            }
        }
        throw new IllegalStateException(
                "e4all: no usable factory or constructor found for "
                        + cls.getName());
    }

    public static String getNamespace(Object id) {
        if (id == null) return null;
        return invokeStringGetter(id, "getNamespace");
    }

    public static String getPath(Object id) {
        if (id == null) return null;
        return invokeStringGetter(id, "getPath");
    }

    private static String invokeStringGetter(Object id, String methodName) {
        try {
            Method m = id.getClass().getMethod(methodName);
            return (String) m.invoke(id);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            return null;
        } catch (java.lang.reflect.InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            return null;
        }
    }

    public static boolean isInstance(Object candidate) {
        Class<?> cls = classOrNull();
        return cls != null && cls.isInstance(candidate);
    }

    public static boolean isAssignableFrom(Class<?> type) {
        if (type == null) return false;
        Class<?> cls = classOrNull();
        return cls != null && cls.isAssignableFrom(type);
    }
}

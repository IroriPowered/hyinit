package cc.irori.hyinit.util;

import java.lang.reflect.Method;

public final class ReflectionUtil {

    public static Class<?> loadClassOrNull(ClassLoader classLoader, String className) {
        try {
            return classLoader.loadClass(className);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    public static Object invokeMethod(Method method, Object object, Object... args) {
        try {
            return method.invoke(object, args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Method getDeclaredMethod(Class<?> clazz, String name, Class<?>... parameterTypes) {
        try {
            return clazz.getDeclaredMethod(name, parameterTypes);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}

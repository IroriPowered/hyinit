package cc.irori.hyinit.util;

import java.lang.reflect.*;
import java.util.*;
import sun.misc.Unsafe;

public final class ReflectionUtil {

    private static final Unsafe UNSAFE =
            (Unsafe) ReflectionUtil.getFieldValueAndSetAccessible(Unsafe.class, "theUnsafe", null);

    public static Class<?> loadClassOrNull(ClassLoader classLoader, String className) {
        try {
            return classLoader.loadClass(className);
        } catch (ClassNotFoundException ex) {
            return null;
        }
    }

    public static Class<?> loadClassOrNull(String className) {
        return loadClassOrNull(ReflectionUtil.class.getClassLoader(), className);
    }

    public static Class<?> getClass(String name) {
        try {
            return Class.forName(name);
        } catch (Exception e) {
            throw new RuntimeException(e);
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

    public static Method getDeclaredMethodAndSetAccessible(Class<?> clazz, String name, Class<?>... parameterTypes) {
        try {
            Method method = clazz.getDeclaredMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Field getField(Class<?> clazz, String name) {
        try {
            return clazz.getDeclaredField(name);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static void setFieldValue(Field field, Object obj, Object value) {
        try {
            field.set(obj, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getFieldValue(Field field, Object obj) {
        try {
            return field.get(obj);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Field getDeclaredFieldAndSetAccessible(Class<?> clazz, String name) {
        try {
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Field getFieldAndSetAccessible(Class<?> clazz, String name) {
        try {
            Field field = clazz.getField(name);
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getFieldValueAndSetAccessible(Class<?> clazz, String name, Object obj) {
        try {
            Field field = clazz.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(obj);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Constructor<?> getInnerClassConstructor(
            Class<?> clazz, String innerClassName, Class<?>... parameters) {
        Class<?> inner = null;
        for (Class<?> innerClazz : clazz.getDeclaredClasses()) {
            if (innerClazz.getSimpleName().equals(innerClassName)) {
                inner = innerClazz;
                break;
            }
        }
        if (inner == null) {
            throw new RuntimeException("Inner class not found!");
        }
        try {
            List<Class<?>> list = new ArrayList<>();
            list.add(clazz); // Add the class to the constructor (inner classes contain their main class)
            Collections.addAll(list, parameters);

            return inner.getConstructor(list.toArray(new Class<?>[0]));
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Constructor<?> getConstructor(String clazz, Class<?>... params) {
        try {
            return getConstructor(Class.forName(clazz), params);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }

    public static Constructor<?> getConstructor(Class<?> clazz, Class<?>... params) {
        try {
            return clazz.getConstructor(params);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static Object newInstance(Constructor<?> constructor, Object... args) {
        try {
            return constructor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    public static <T> void copyFields(Class<T> baseClass, T reference, Object to) {
        Class<?> toClass = to.getClass();

        if (baseClass.isAssignableFrom(toClass)) {
            for (Field field : baseClass.getDeclaredFields()) {
                if (Modifier.isPublic(field.getModifiers())) {
                    Field toField = ReflectionUtil.getFieldAndSetAccessible(toClass, field.getName());
                    Object fieldValue = ReflectionUtil.getFieldValue(field, reference);

                    ReflectionUtil.setFieldValue(toField, to, fieldValue);
                }
            }
        } else {
            throw new UnsupportedOperationException();
        }
    }
}

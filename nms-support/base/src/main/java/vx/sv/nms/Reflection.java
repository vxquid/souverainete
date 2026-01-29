package vx.sv.nms;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;

public final class Reflection {

    private static final Unsafe UNSAFE = getUnsafe();
    private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

    public void setFieldUsingUnsafe(@NotNull Field field, Object object, Object newValue) {
        try {
            field.setAccessible(true);
            int fieldModifiersMask = field.getModifiers();
            boolean isFinalModifierPresent = (fieldModifiersMask & Modifier.FINAL) == Modifier.FINAL;
            if (isFinalModifierPresent) {
                AccessController.doPrivileged((PrivilegedAction<Object>) () -> {
                    try {
                        long offset = UNSAFE.objectFieldOffset(field);
                        setFieldUsingUnsafe(object, field.getType(), offset, newValue);
                        return null;
                    } catch (Throwable throwable) {
                        throw new RuntimeException(throwable);
                    }
                });
            } else {
                try {
                    field.set(object, newValue);
                } catch (IllegalAccessException exception) {
                    throw new RuntimeException(exception);
                }
            }
        } catch (SecurityException exception) {
            throw new RuntimeException(exception);
        }
    }

    public static @Nullable MethodHandle getField(Class<?> refc, Class<?> instc, String name, boolean isGetter, String... extraNames) {
        try {
            Field temp = getFieldHandleRaw(refc, instc, name);
            MethodHandle handle = temp != null ? (isGetter ? LOOKUP.unreflectGetter(temp) : LOOKUP.unreflectSetter(temp)) : null;

            if (handle != null) return handle;

            if (extraNames != null && extraNames.length > 0) {
                if (extraNames.length == 1) return getField(refc, instc, extraNames[0], isGetter);
                return getField(refc, instc, extraNames[0], isGetter, removeFirst(extraNames));
            }
        } catch (IllegalAccessException exception) {
            exception.printStackTrace();
        }

        return null;
    }

    private static @NotNull String[] removeFirst(@NotNull String[] array) {
        int length = array.length;

        String[] result = new String[length - 1];
        System.arraycopy(array, 1, result, 0, length - 1);

        return result;
    }

    private static @Nullable Field getFieldHandleRaw(@NotNull Class<?> refc, Class<?> inscofc, String name) {
        for (Field field : refc.getDeclaredFields()) {
            field.setAccessible(true);

            if (!field.getName().equalsIgnoreCase(name)) continue;

            if (field.getType().isInstance(inscofc) || field.getType().isAssignableFrom(inscofc)) {
                return field;
            }
        }
        return null;
    }

    private static Unsafe getUnsafe() {
        try {
            Field field = Unsafe.class.getDeclaredField("theUnsafe");
            field.setAccessible(true);
            return (Unsafe) field.get(null);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            throw new RuntimeException(exception);
        }
    }

    static void setFieldUsingUnsafe(Object base, Class<?> type, long offset, Object newValue) {
        if (type == Integer.TYPE) {
            UNSAFE.putInt(base, offset, ((Integer) newValue));
        } else if (type == Short.TYPE) {
            UNSAFE.putShort(base, offset, ((Short) newValue));
        } else if (type == Long.TYPE) {
            UNSAFE.putLong(base, offset, ((Long) newValue));
        } else if (type == Byte.TYPE) {
            UNSAFE.putByte(base, offset, ((Byte) newValue));
        } else if (type == Boolean.TYPE) {
            UNSAFE.putBoolean(base, offset, ((Boolean) newValue));
        } else if (type == Float.TYPE) {
            UNSAFE.putFloat(base, offset, ((Float) newValue));
        } else if (type == Double.TYPE) {
            UNSAFE.putDouble(base, offset, ((Double) newValue));
        } else if (type == Character.TYPE) {
            UNSAFE.putChar(base, offset, ((Character) newValue));
        } else {
            UNSAFE.putObject(base, offset, newValue);
        }
    }

}
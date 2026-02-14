package cc.irori.hyinit.mixin;

import java.util.HashMap;
import java.util.Map;
import org.spongepowered.asm.service.IGlobalPropertyService;
import org.spongepowered.asm.service.IPropertyKey;

public class HyinitGlobalProperties implements IGlobalPropertyService {

    private final Map<String, IPropertyKey> keys = new HashMap<>();
    private final Map<IPropertyKey, Object> values = new HashMap<>();

    @Override
    public IPropertyKey resolveKey(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Property names must not be null or empty");
        }
        return this.keys.computeIfAbsent(name, Key::new);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key) {
        return (T) this.values.get(key);
    }

    @Override
    public void setProperty(IPropertyKey key, Object value) {
        this.values.put(key, value);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T getProperty(IPropertyKey key, T defaultValue) {
        return (T) this.values.getOrDefault(key, defaultValue);
    }

    @Override
    public String getPropertyString(IPropertyKey key, String defaultValue) {
        return this.getProperty(key, defaultValue);
    }

    public record Key(String name) implements IPropertyKey {}
}

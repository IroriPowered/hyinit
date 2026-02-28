package cc.irori.hyinit.shared;

public record SourceMetadata(boolean isEarlyPlugin, boolean hasMainClass) {

    public SourceMetadata(boolean isEarlyPlugin) {
        this(isEarlyPlugin, false);
    }
}

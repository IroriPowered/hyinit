package cc.irori.hyinit.shared;

import java.util.Set;

public record SourceMetadata(
        boolean isEarlyPlugin, boolean hasMainClass, Set<String> sharedPackages, boolean mixinsSkipped) {

    public SourceMetadata {
        sharedPackages = Set.copyOf(sharedPackages);
    }

    public SourceMetadata(boolean isEarlyPlugin) {
        this(isEarlyPlugin, false, Set.of(), false);
    }

    public boolean declaresSharedPackages() {
        return isEarlyPlugin && hasMainClass && !sharedPackages.isEmpty();
    }

    public boolean sharesClass(String className) {
        for (String pkg : sharedPackages) {
            if (className.length() > pkg.length()
                    && className.startsWith(pkg)
                    && className.charAt(pkg.length()) == '.') {
                return true;
            }
        }
        return false;
    }
}

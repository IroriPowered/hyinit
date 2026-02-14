package cc.irori.hyinit.mixin;

import cc.irori.hyinit.HyinitLogger;
import cc.irori.hyinit.Main;
import cc.irori.hyinit.util.UrlUtil;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Collection;
import java.util.Collections;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.launch.platform.container.ContainerHandleURI;
import org.spongepowered.asm.launch.platform.container.IContainerHandle;
import org.spongepowered.asm.logging.ILogger;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.transformer.IMixinTransformer;
import org.spongepowered.asm.mixin.transformer.IMixinTransformerFactory;
import org.spongepowered.asm.service.*;
import org.spongepowered.asm.util.ReEntranceLock;

public class HyinitMixinService
        implements IMixinService, IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker {

    private final ReEntranceLock lock = new ReEntranceLock(1);

    private static HyinitClassLoader gameClassLoader;
    private static IMixinTransformer transformer;

    public static void setGameClassLoader(HyinitClassLoader loader) {
        gameClassLoader = loader;
    }

    public static IMixinTransformer getTransformer() {
        return transformer;
    }

    public byte[] getClassBytes(String name, String transformedName) throws IOException {
        return gameClassLoader.getClassByteArray(name, true);
    }

    public byte[] getClassBytes(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        byte[] classBytes = gameClassLoader.getClassByteArray(name, runTransformers);

        if (classBytes != null) {
            return classBytes;
        } else {
            throw new ClassNotFoundException(name);
        }
    }

    @Override
    public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException {
        return getClassNode(name, true);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers) throws ClassNotFoundException, IOException {
        return getClassNode(name, runTransformers, 0);
    }

    @Override
    public ClassNode getClassNode(String name, boolean runTransformers, int readerFlags)
            throws ClassNotFoundException, IOException {
        ClassReader reader = new ClassReader(getClassBytes(name, runTransformers));
        ClassNode node = new ClassNode();
        reader.accept(node, readerFlags);
        return node;
    }

    @Override
    public URL[] getClassPath() {
        return new URL[0];
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        return gameClassLoader.loadClass(name);
    }

    @Override
    public Class<?> findClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, gameClassLoader);
    }

    @Override
    public Class<?> findAgentClass(String name, boolean initialize) throws ClassNotFoundException {
        return Class.forName(name, initialize, Main.class.getClassLoader());
    }

    @Override
    public String getName() {
        return "Hyinit";
    }

    @Override
    public boolean isValid() {
        return true;
    }

    @Override
    public MixinEnvironment.Phase getInitialPhase() {
        return MixinEnvironment.Phase.PREINIT;
    }

    @Override
    public void offer(IMixinInternal internal) {
        if (internal instanceof IMixinTransformerFactory factory) {
            transformer = factory.createTransformer();
        }
    }

    @Override
    public void init() {}

    @Override
    public void prepare() {}

    @Override
    public void beginPhase() {}

    @Override
    public void checkEnv(Object bootSource) {}

    @Override
    public ReEntranceLock getReEntranceLock() {
        return lock;
    }

    @Override
    public IClassProvider getClassProvider() {
        return this;
    }

    @Override
    public IClassBytecodeProvider getBytecodeProvider() {
        return this;
    }

    @Override
    public ITransformerProvider getTransformerProvider() {
        return this;
    }

    @Override
    public IClassTracker getClassTracker() {
        return this;
    }

    @Override
    public IMixinAuditTrail getAuditTrail() {
        return null;
    }

    @Override
    public Collection<String> getPlatformAgents() {
        return Collections.singletonList("org.spongepowered.asm.launch.platform.MixinPlatformAgentDefault");
    }

    @Override
    public IContainerHandle getPrimaryContainer() {
        return new ContainerHandleURI(UrlUtil.LOADER_CODE_SOURCE.toUri());
    }

    @Override
    public Collection<IContainerHandle> getMixinContainers() {
        return Collections.emptyList();
    }

    @Override
    public InputStream getResourceAsStream(String name) {
        return gameClassLoader.getResourceAsStream(name);
    }

    public void registerInvalidClass(String className) {}

    @Override
    public boolean isClassLoaded(String className) {
        return gameClassLoader.isClassLoaded(className);
    }

    @Override
    public String getClassRestrictions(String className) {
        return "";
    }

    @Override
    public Collection<ITransformer> getTransformers() {
        return Collections.emptyList();
    }

    @Override
    public Collection<ITransformer> getDelegatedTransformers() {
        return Collections.emptyList();
    }

    @Override
    public void addTransformerExclusion(String name) {}

    @Override
    public String getSideName() {
        return "SERVER";
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMinCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_21;
    }

    @Override
    public MixinEnvironment.CompatibilityLevel getMaxCompatibilityLevel() {
        return MixinEnvironment.CompatibilityLevel.JAVA_22;
    }

    @Override
    public ILogger getLogger(String name) {
        return HyinitLogger.get();
    }
}

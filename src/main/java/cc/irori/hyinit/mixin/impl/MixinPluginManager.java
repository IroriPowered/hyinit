package cc.irori.hyinit.mixin.impl;

import cc.irori.hyinit.shared.SourceMetaStore;
import cc.irori.hyinit.shared.SourceMetadata;
import cc.irori.hyinit.util.LoaderUtil;
import cc.irori.hyinit.util.UrlUtil;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.plugin.pending.PendingLoadJavaPlugin;
import java.io.*;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PluginManager.class)
public abstract class MixinPluginManager {

    private static final Pattern LEGACY_VERSION = Pattern.compile("^\\d{4}\\.\\d{2}\\.\\d{2}-[0-9a-f]+$");

    @Shadow
    private PendingLoadJavaPlugin loadPendingJavaPlugin(Path path) { return null; }

    @Redirect(
            method = "loadPluginsFromDirectory",
            at = @At(
                    value = "INVOKE",
                    target = "Lcom/hypixel/hytale/server/core/plugin/PluginManager;loadPendingJavaPlugin(Ljava/nio/file/Path;)Lcom/hypixel/hytale/server/core/plugin/pending/PendingLoadJavaPlugin;"))
    private PendingLoadJavaPlugin hyinit$safeLoadPendingJavaPlugin(PluginManager self, Path path) {
        hyinit$patchLegacyManifest(path);
        try {
            return this.loadPendingJavaPlugin(path);
        } catch (Exception e) {
            System.err.println("[Hyinit] Failed to load plugin from " + path.getFileName() + ": " + e.getMessage());
            return null;
        }
    }

    private static void hyinit$patchLegacyManifest(Path jarPath) {
        try (ZipFile zip = new ZipFile(jarPath.toFile())) {
            ZipEntry entry = zip.getEntry("manifest.json");
            if (entry == null) return;
            String json;
            try (InputStream is = zip.getInputStream(entry)) {
                json = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            }
            JsonObject obj = new Gson().fromJson(json, JsonObject.class);
            if (obj == null || !obj.has("ServerVersion")) return;
            String sv = obj.get("ServerVersion").getAsString();
            if (!LEGACY_VERSION.matcher(sv).matches()) {
                System.out.println("[Hyinit] Plugin " + jarPath.getFileName() + " ServerVersion '" + sv + "' is not legacy format, skipping patch");
                return;
            }
            obj.addProperty("ServerVersion", ">=0.4.0");
            String patched = new Gson().toJson(obj);
            File tmp = File.createTempFile("hyinit-patch-", ".jar");
            try {
                try (ZipFile src = new ZipFile(jarPath.toFile());
                     ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(tmp))) {
                    Enumeration<? extends ZipEntry> entries = src.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry e = entries.nextElement();
                        zos.putNextEntry(new ZipEntry(e.getName()));
                        if ("manifest.json".equals(e.getName())) {
                            zos.write(patched.getBytes(StandardCharsets.UTF_8));
                        } else {
                            try (InputStream is = src.getInputStream(e)) {
                                is.transferTo(zos);
                            }
                        }
                        zos.closeEntry();
                    }
                }
                java.nio.file.Files.copy(tmp.toPath(), jarPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[Hyinit] Patched legacy ServerVersion '" + sv + "' -> '>=0.4.0' in " + jarPath.getFileName());
            } finally {
                tmp.delete();
            }
        } catch (Exception e) {
            System.err.println("[Hyinit] Failed to patch manifest in " + jarPath.getFileName() + ": " + e.getMessage());
        }
    }

    @Redirect(
            method = "loadPluginsInClasspath",
            at =
                    @At(
                            value = "INVOKE",
                            target = "Ljava/lang/ClassLoader;getResources(Ljava/lang/String;)Ljava/util/Enumeration;"))
    private Enumeration<URL> hyinit$redirectManifestResources(ClassLoader instance, String name) throws IOException {
        // allow server and other classpath plugins through
        Path hyinitJarPath = UrlUtil.asPath(
                cc.irori.hyinit.Main.class.getProtectionDomain().getCodeSource().getLocation());

        List<URL> urls = new ArrayList<>();
        Enumeration<URL> resources = instance.getResources(name);
        while (resources.hasMoreElements()) {
            URL url = resources.nextElement();
            URLConnection connection = url.openConnection();
            if (connection instanceof JarURLConnection jarConnection) {
                Path jarPath = UrlUtil.asPath(jarConnection.getJarFileURL());

                if (jarPath.equals(hyinitJarPath)) {
                    continue;
                }

                SourceMetadata meta = SourceMetaStore.get(LoaderUtil.normalizeExistingPath(jarPath));
                if (meta != null && meta.isEarlyPlugin() && !meta.hasMainClass()) {
                    continue;
                }
            }
            urls.add(url);
        }
        return Collections.enumeration(urls);
    }
}

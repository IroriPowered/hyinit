package local.hyinit.ownership;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
public final class MainPlugin extends JavaPlugin {
    private static Object instance;
    public MainPlugin(JavaPluginInit init) { super(init); }
    public static void initialize(Object value) { instance = value; }
    public static Object get() { return instance; }
    public static String dependency() { return local.hyinit.dependency.Api.value(); }
    public static java.net.URL manifest() { return MainPlugin.class.getClassLoader().getResource("manifest.json"); }
}

package local.hyinit.ownership;
public final class MainPlugin {
    private static Object instance;
    public static void initialize(Object value) { instance = value; }
    public static Object get() { return instance; }
    public static String dependency() { return local.hyinit.dependency.Api.value(); }
    public static java.net.URL manifest() { return MainPlugin.class.getClassLoader().getResource("manifest.json"); }
}

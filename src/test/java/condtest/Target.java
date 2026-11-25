package condtest;

public class Target {

    public String trace() {
        StringBuilder out = new StringBuilder();
        record(out);
        return out.toString();
    }

    void record(StringBuilder out) {}

    public void unused() {}
}

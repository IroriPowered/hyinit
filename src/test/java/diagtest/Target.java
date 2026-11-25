package diagtest;

public class Target {

    public int value() {
        return 1;
    }

    public String greet(String name) {
        return helper(name);
    }

    String helper(String name) {
        return "hi " + name;
    }

    public int wrapped() {
        return compute(2);
    }

    int compute(int x) {
        return x * 10;
    }

    public String replaced() {
        return "original";
    }

    public String callsReplaced() {
        return replaced() + "!";
    }

    public int untouched() {
        return 7;
    }

    public int line(int x) {
        return new Throwable().getStackTrace()[0].getLineNumber();
    }

    public int line(String x) {
        return new Throwable().getStackTrace()[0].getLineNumber();
    }
}

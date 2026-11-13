package local.hyinit.capabilities;

public final class ProbeTargets {
    public enum Extended {
        FIRST(10, 0), SECOND(20, 1);

        public final int payload;
        public final int constructedOrdinal;

        Extended(int payload, int constructedOrdinal) {
            this.payload = payload;
            this.constructedOrdinal = constructedOrdinal;
        }
    }

    public static class Helpers {
        public int run() {
            return -1;
        }
    }
}

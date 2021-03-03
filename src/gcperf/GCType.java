package gcperf;

public enum GCType {
    SERIAL("-XX:+UseSerialGC"),
    PARALLEL("-XX:+UseParallelGC"),
    G1("-XX:+UseG1GC"),
    ZGC("-XX:+UseZGC"),
    SHENANDOAH("-XX:+UseShenandoahGC");

    private final String cliOption;

    GCType(String cliOption) {
        this.cliOption = cliOption;
    }

    public String getCliOption() {
        return cliOption;
    }
}

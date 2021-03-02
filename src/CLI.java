import java.util.Arrays;

public class CLI {

    private GCType gcType;
    private VMOptions[] vmOptions;
    private VMOptions.GCOptions[] gcOptions;
    private VMOptions.XlogOptions[] xlogOptions;

    public CLI(GCType gcType) {
        this.gcType = gcType;
    }

    public CLI withVMOptions(VMOptions... vmOptions) {
        this.vmOptions = vmOptions;
        return this;
    }

    public CLI withGCOptions(VMOptions.GCOptions... gcOptions) {
        this.gcOptions = gcOptions;
        return this;
    }

    public CLI withXlogOptions(VMOptions.XlogOptions... xlogOptions) {
        this.xlogOptions = xlogOptions;
        return this;
    }

    public GCType getGcType() {
        return gcType;
    }

    public VMOptions[] getVmOptions() {
        return vmOptions;
    }

    public VMOptions.GCOptions[] getGcOptions() {
        return gcOptions;
    }

    public VMOptions.XlogOptions[] getXlogOptions() {
        return xlogOptions;
    }

    @Override
    public String toString() {
        return "CLI{" +
                "vmOptions=" + Arrays.toString(vmOptions) +
                ", gcOptions=" + Arrays.toString(gcOptions) +
                ", xlogOptions=" + Arrays.toString(xlogOptions) +
                '}';
    }

    static record IntContainer(int integer) {
        public int getInteger() {
            return integer;
        }
    }

    /***
     * Xms - start heap size in bytes
     * Xmx - max heap size in bytes
     */
    public enum VMOptions {
        Xms(new IntContainer(8)),
        Xmx(new IntContainer(64));

        private IntContainer size;

        VMOptions(IntContainer size) {
            this.size = size;
        }

        public int getSize() {
            return size.getInteger();
        }

        public void setSize(int size) {
            this.size = new IntContainer(size);
        }

        public String stringifyHeapSizeOption() {
            return "-" + this.name() + getSize() + "m";
        }

        public enum GCOptions {
            UnlockExperimental("-XX:+UnlockExperimentalVMOptions"),
            VerboseGC("-verbose:gc");

            private final String optionString;

            GCOptions(String optionString) {
                this.optionString = optionString;
            }

            public String getOptionString() {
                return optionString;
            }

        }

        public enum XlogOptions {
            TimeLevelTags("-Xlog:::time,level,tags"),
            GC("-Xlog:gc"),
            GCStart("-Xlog:gc+start"),
            GCInit("-Xlog:gc+init"),
            GCLoad("-Xlog:gc+load"),
            GCCpu("-Xlog:gc+cpu"),
            GCHeap("-Xlog:gc+heap"),
            GCHeapExit("-Xlog:gc+heap+exit"),
            GCHeapCoops("-Xlog:gc+heap+coops"),
            GCMetaspace("-Xlog:gc+metaspace"),
            GCPhases("-Xlog:gc+phases"),
            GCPhasesStart("-Xlog:gc+phases+start"),
            GCMMU("-Xlog:gc+mmu"),
            GCMarking("-Xlog:gc+marking"),
            GCReloc("-Xlog:gc+reloc"),
            GCNMethod("-Xlog:gc+nmethod"),
            GCRef("-Xlog:gc+ref"),
            GCTask("-Xlog:gc+task"),
            GCCds("-Xlog:gc+cds"),
            GCErgo("-Xlog:gc+ergo"),
            GCStats("-Xlog:gc+stats");

            private final String optionString;

            XlogOptions(String optionString) {
                this.optionString = optionString;
            }

            public String getOptionString() {
                return optionString;
            }
        }
    }
}
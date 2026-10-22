package dev.seedy.recovery;

public final class BlockSearchProgress {
    public record View(int pass, int passCount, String sample, String phase, int stage, long elapsedSeconds, long passSeconds, long stageSeconds, long checkedCandidates, long totalCandidates, double fraction, long etaSeconds, int rejectedStates, String previousPass, boolean running) { }

    private enum Phase {
        BEDROCK("Checking bedrock pattern"),
        DEEPSLATE("Checking deepslate pattern"),
        VEIN("Checking vein pattern"),
        ORE("Checking ore pattern"),
        RAW("Checking raw ore pattern"),
        RESOLVE("Resolving world seed"),
        VALIDATE("Checking random state against loaded blocks"),
        CANDIDATES("Checking world seed candidates");

        private final String text;
        Phase(String text) { this.text = text; }
    }

    private boolean running;
    private int pass, passCount, rejectedStates;
    private long startedAt, passStartedAt, stageStartedAt, candidateStartedAt, finishedAt, checkedCandidates, totalCandidates;
    private String sample = "", previousPass = "";
    private Phase phase = Phase.BEDROCK;

    public synchronized void reset() {
        running = false;
        pass = passCount = rejectedStates = 0;
        startedAt = passStartedAt = stageStartedAt = candidateStartedAt = finishedAt = checkedCandidates = totalCandidates = 0;
        sample = previousPass = "";
        phase = Phase.BEDROCK;
    }

    public synchronized void start(int passes, long now) {
        if (passes < 1) throw new IllegalArgumentException("A search needs at least one pass");
        reset();
        running = true;
        passCount = passes;
        startedAt = now;
    }

    public synchronized void beginPass(int number, String description, long now) {
        if (!running || number <= pass || number > passCount) throw new IllegalStateException("Invalid search pass");
        pass = number;
        sample = description;
        phase = Phase.BEDROCK;
        passStartedAt = stageStartedAt = now;
        candidateStartedAt = checkedCandidates = totalCandidates = 0;
        rejectedStates = 0;
    }

    public synchronized boolean update(String key, long now) {
        if (!running || pass == 0) return false;
        Phase next = switch (key) {
            case "commands.bedrockcrack.completedBitSearch1" -> Phase.DEEPSLATE;
            case "commands.bedrockcrack.completedBitSearch2" -> Phase.VEIN;
            case "commands.bedrockcrack.completedBitSearch3" -> Phase.ORE;
            case "commands.bedrockcrack.completedBitSearch4" -> Phase.RAW;
            case "commands.bedrockcrack.completedBitSearch5", "commands.bedrockcrack.startingZ3Search", "commands.bedrockcrack.stateCandidateRejected" -> Phase.RESOLVE;
            case "commands.bedrockcrack.validatingRandomState" -> Phase.VALIDATE;
            case "commands.bedrockcrack.crackedRandomStateRandom" -> Phase.CANDIDATES;
            default -> null;
        };
        if (next == null || totalCandidates > 0 && next != Phase.CANDIDATES) return false;
        if (key.equals("commands.bedrockcrack.stateCandidateRejected")) rejectedStates++;
        boolean changed = phase != next;
        if (changed) { phase = next; stageStartedAt = now; }
        return changed;
    }

    public synchronized void candidates(long checked, long total, long now) {
        if (!running || pass == 0 || checked < 0 || total <= 0 || totalCandidates != 0 && total != totalCandidates) return;
        if (phase != Phase.CANDIDATES) { phase = Phase.CANDIDATES; stageStartedAt = now; }
        if (candidateStartedAt == 0) candidateStartedAt = now;
        checkedCandidates = Math.max(checkedCandidates, Math.min(checked, total));
        totalCandidates = total;
    }

    public synchronized void passResult(String result) { if (running) previousPass = result; }

    public synchronized void finish(long now) {
        if (!running) return;
        finishedAt = now;
        running = false;
    }

    public synchronized View view(long now) {
        long end = running ? now : finishedAt;
        long elapsed = startedAt == 0 ? 0 : Math.max(0, end - startedAt);
        long passElapsed = passStartedAt == 0 ? 0 : Math.max(0, end - passStartedAt);
        long stageElapsed = stageStartedAt == 0 ? 0 : Math.max(0, end - stageStartedAt);
        long estimate = -1;
        if (running && candidateStartedAt > 0 && totalCandidates > 0 && checkedCandidates > 0 && checkedCandidates >= totalCandidates / 100 && now - candidateStartedAt >= 3_000_000_000L) {
            estimate = Math.max(0, (long)((double)(now - candidateStartedAt) / checkedCandidates * (totalCandidates - checkedCandidates) / 1_000_000_000D));
        }
        return new View(pass, passCount, sample, phase.text, phase.ordinal() + 1, elapsed / 1_000_000_000L, passElapsed / 1_000_000_000L, stageElapsed / 1_000_000_000L, checkedCandidates, totalCandidates, totalCandidates == 0 ? -1 : (double)checkedCandidates / totalCandidates, estimate, rejectedStates, previousPass, running);
    }
}

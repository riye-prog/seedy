package dev.seedy;

import dev.seedy.recovery.SeedHash;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;

public final class RecoveryReplay {
    public static void main(String[] args) throws Exception {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        var positions = new ArrayList<BlockPos>();
        try (var input = new DataInputStream(Files.newInputStream(Path.of(args[0])))) {
            while (true) {
                try {
                    int x = input.readInt(), y = input.readInt(), z = input.readInt(), type = input.readInt();
                    if (type == 1) positions.add(new BlockPos(x, y, z));
                } catch (EOFException done) { break; }
            }
        }
        String version = args[1].equals("state") ? args.length > 4 ? args[4] : null : args.length > 3 ? args[3] : null;
        var check = version == null ? new OreVeinCheck(positions) : new OreVeinCheck(positions, version);
        System.out.println("raw positions " + positions.size());
        if (args[1].equals("state")) {
            System.out.println("ore vein state matches " + check.matches(Long.parseUnsignedLong(args[2]), Long.parseUnsignedLong(args[3])));
            return;
        }
        long hash = Long.parseLong(args[1]);
        var builder = new ProcessBuilder(Path.of(args[2]).toAbsolutePath().toString(), Path.of(args[0]).toAbsolutePath().toString(), "any", "63");
        builder.environment().put("SEEDY_JAVA_VALIDATOR", "1");
        builder.environment().putIfAbsent("SEEDY_CANCEL_AFTER_SECONDS", "600");
        builder.redirectError(ProcessBuilder.Redirect.INHERIT);
        long started = System.nanoTime();
        Process process = builder.start();
        Long seed = null;
        int rejected = 0;
        boolean completed = false;
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream())); var writer = new BufferedWriter(new OutputStreamWriter(process.getOutputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("validateState ")) {
                    String[] values = line.split(" ");
                    boolean accepted = check.matches(Long.parseUnsignedLong(values[1]), Long.parseUnsignedLong(values[2]));
                    if (!accepted) rejected++;
                    System.out.println("ore vein state " + (accepted ? "accepted" : "rejected"));
                    writer.write(accepted ? "1\n" : "0\n");
                    writer.flush();
                } else if (line.startsWith("validateSeed ")) {
                    long candidate = Long.parseUnsignedLong(line.substring(13));
                    boolean accepted = SeedHash.hash(candidate) == hash;
                    if (accepted) seed = candidate;
                    writer.write(accepted ? "1\n" : "0\n");
                    writer.flush();
                } else System.out.println(line);
            }
            completed = true;
        } finally {
            if (!completed && process.isAlive()) process.destroyForcibly();
        }
        int exit = process.waitFor();
        System.out.println("replay seconds " + (System.nanoTime() - started) / 1_000_000_000.0 + " noise states rejected " + rejected);
        if (exit != 0 || seed == null) throw new IllegalStateException("No hash-verified world seed recovered; native exit " + exit);
        System.out.println("verified seed " + seed);
    }
}

package dev.seedy;

import com.google.gson.GsonBuilder;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.invoke.MethodHandle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import static java.lang.foreign.ValueLayout.*;

public final class McoptRenderProbe {
    private static SymbolLookup library;
    private static final int WIDTH = 1440, HEIGHT = 1000, BYTES = WIDTH * HEIGHT * 4;
    private static MethodHandle function(String name, MemoryLayout result, MemoryLayout... arguments) {
        var descriptor = result == null ? FunctionDescriptor.ofVoid(arguments) : FunctionDescriptor.of(result,arguments);
        return Linker.nativeLinker().downcallHandle(library.find(name).orElseThrow(),descriptor);
    }

    private static int[] terrainPixels(int chunkX, int chunkZ) {
        int[] colors = new int[256];
        for (int i = 0; i < 256; ++i) colors[i] = ((chunkX * 16 + i % 16) / 8 + (chunkZ * 16 + i / 16) / 12) % 5 == 0 ? 0x376b43 : 0x75a85a;
        return colors;
    }

    private static String terrainPacket(int x, int z) {
        var data = TerrainMap.encode(x,z,terrainPixels(x,z),new int[256]);
        data.addProperty("epoch",1); data.addProperty("dimension","overworld"); return data.toString();
    }

    public static void main(String[] arguments) throws Throwable {
        var nativeType = Class.forName("mcopt.metal.Native");
        var lookup = nativeType.getDeclaredField("LIB");
        lookup.setAccessible(true);
        library = (SymbolLookup)lookup.get(null);
        System.load(Path.of(arguments[0]).toAbsolutePath().toString());
        var frame = NativeGui.class.getDeclaredMethod("frame",String.class,int.class,int.class,float.class,double[].class,long[].class,double[].class,boolean.class,boolean.class);
        frame.setAccessible(true);
        var dispose = NativeGui.class.getDeclaredMethod("dispose");
        dispose.setAccessible(true);
        var create = function("mc_create",JAVA_LONG,JAVA_LONG,JAVA_INT,JAVA_LONG,JAVA_INT);
        var newEncoder = function("mc_enc_new",JAVA_LONG,JAVA_LONG);
        var newTexture = function("mc_texture_new",JAVA_LONG,JAVA_LONG,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT);
        var newBuffer = function("mc_buffer_new",JAVA_LONG,JAVA_LONG,JAVA_LONG);
        var contents = function("mc_buffer_contents",JAVA_LONG,JAVA_LONG);
        var upload = function("mc_blit_buffer_to_texture",null,JAVA_LONG,JAVA_LONG,JAVA_LONG,JAVA_INT,JAVA_INT,JAVA_LONG,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT);
        var readback = function("mc_blit_texture_to_buffer",null,JAVA_LONG,JAVA_LONG,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_INT,JAVA_LONG,JAVA_LONG,JAVA_INT);
        var commit = function("mc_enc_commit",JAVA_LONG,JAVA_LONG);
        var wait = function("mc_cmd_wait",null,JAVA_LONG);
        var release = function("mc_release",null,JAVA_LONG);
        var counters = function("mc_enc_counters",null,JAVA_LONG);
        try (var arena = Arena.ofConfined()) {
            var name = arena.allocate(256);
            var error = arena.allocate(1024);
            long context = (long)create.invokeExact(name.address(),256,error.address(),1024);
            if (context == 0) throw new IllegalStateException(error.getString(0));
            long encoder = (long)newEncoder.invokeExact(context);
            long texture = (long)newTexture.invokeExact(context,80,WIDTH,HEIGHT,1,1,5,0);
            long background = (long)newBuffer.invokeExact(context,(long)BYTES);
            long pixels = (long)contents.invokeExact(background);
            var backgroundPixels = MemorySegment.ofAddress(pixels).reinterpret(BYTES);
            var backdrop = new byte[BYTES];
            for (int index = 0; index < BYTES; index += 4) { backdrop[index] = 24; backdrop[index + 1] = 16; backdrop[index + 2] = 8; backdrop[index + 3] = -1; }
            backgroundPixels.copyFrom(MemorySegment.ofArray(backdrop));
            var buffers = new long[3];
            for (int index = 0; index < buffers.length; index++) buffers[index] = (long)newBuffer.invokeExact(context,(long)BYTES);
            var settings = Map.of("hudEnabled",true,"hudAnchor",1,"hudSeed",true,"miniEnabled",false);
            var state = Map.ofEntries(Map.entry("connected",true),Map.entry("seed","2589511696370800371"),Map.entry("verified",true),Map.entry("gameVersion","26.3"),Map.entry("generationVersion","26.3"),Map.entry("dimension","overworld"),Map.entry("biome","minecraft:cherry_grove"),Map.entry("x",128),Map.entry("y",72),Map.entry("z",96),Map.entry("fps",144),Map.entry("structures",new String[]{"minecraft:ancient_cities"}),Map.entry("recoveryTypes",new String[]{"minecraft:desert_pyramids"}),Map.entry("observations",new Object[0]),Map.entry("uiSettings",settings));
            String snapshot = new GsonBuilder().create().toJson(state);
            var inFlight = new ArrayDeque<Long>();
            var samples = new ArrayList<Long>();
            var countData = arena.allocate(JAVA_INT,4);
            var output = new ArrayList<Map<String,Object>>();
            for (int pass = 0; pass < (arguments.length > 2 ? 4 : 3); pass++) {
                boolean minimap = pass == 3;
                if (minimap) {
                    var data = NativeGui.class.getDeclaredMethod("mapData",int.class,String.class); data.setAccessible(true);
                    var position = NativeGui.class.getDeclaredMethod("mapPosition",double.class,double.class,float.class); position.setAccessible(true);
                    data.invoke(null,1,Files.readString(Path.of(arguments[2])));
                    data.invoke(null,2,"{\"reset\":1,\"dimension\":\"overworld\"}");
                    for (int z = 5; z <= 8; ++z) for (int x = 7; x <= 10; ++x) data.invoke(null,2,terrainPacket(x,z));
                    position.invoke(null,128.0,96.0,45f);
                    var next = com.google.gson.JsonParser.parseString(snapshot).getAsJsonObject();
                    next.addProperty("hudRevision",1); next.getAsJsonObject("uiSettings").addProperty("miniEnabled",true);
                    snapshot = next.toString();
                }
                boolean panel = pass == 1;
                boolean hud = pass == 0 || minimap;
                int frames = hud ? 120 : 8;
                double[] lines = pass == 2 ? new double[]{400,300,700,300,2,700,300,700,500,2,700,500,400,500,2,400,500,400,300,2,900,300,1200,300,0} : new double[0];
                for (int index = 0; index < frames; index++) {
                    if (inFlight.size() == 2) { long done = inFlight.remove(); wait.invokeExact(done); release.invokeExact(done); }
                    upload.invokeExact(encoder,background,0L,WIDTH * 4,BYTES,texture,0,0,0,0,WIDTH,HEIGHT);
                    long started = System.nanoTime();
                    Object action = frame.invoke(null,index == 0 ? snapshot : null,WIDTH,HEIGHT,1f,new double[0],McoptBridge.graphics(encoder,texture),lines,panel,hud);
                    if (hud && action != null) throw new IllegalStateException("Read-only HUD emitted an action: " + action);
                    if (pass == 0 && index > 10) samples.add(System.nanoTime() - started);
                    counters.invokeExact(countData.address());
                    if (countData.get(JAVA_INT,0) != 1) throw new IllegalStateException("Overlay created another command buffer");
                    readback.invokeExact(encoder,texture,0,0,0,WIDTH,HEIGHT,buffers[index % buffers.length],0L,WIDTH * 4);
                    inFlight.add((long)commit.invokeExact(encoder));
                }
                while (!inFlight.isEmpty()) { long done = inFlight.remove(); wait.invokeExact(done); release.invokeExact(done); }
                long address = (long)contents.invokeExact(buffers[(frames - 1) % buffers.length]);
                byte[] image = MemorySegment.ofAddress(address).reinterpret(BYTES).toArray(JAVA_BYTE);
                int changed = 0;
                for (int index = 0; index < BYTES; index += 4) if (!Arrays.equals(image,index,index + 4,backdrop,index,index + 4)) changed++;
                if (changed < 1000) throw new IllegalStateException("Overlay pixels were not rendered");
                for (int corner : new int[]{0,(WIDTH - 1) * 4,(HEIGHT - 1) * WIDTH * 4,BYTES - 4}) if (!Arrays.equals(image,corner,corner + 4,backdrop,corner,corner + 4)) throw new IllegalStateException("Overlay damaged pixels outside its geometry");
                if (pass == 2) {
                    boolean colored = false;
                    for (int row = HEIGHT - 302; row <= HEIGHT - 299; row++) {
                        int pixel = (row * WIDTH + 550) * 4;
                        if (Byte.toUnsignedInt(image[pixel]) >= 220 && Byte.toUnsignedInt(image[pixel + 1]) >= 130 && Byte.toUnsignedInt(image[pixel + 2]) >= 170) colored = true;
                        int skipped = (row * WIDTH + 1050) * 4;
                        if (!Arrays.equals(image,skipped,skipped + 4,backdrop,skipped,skipped + 4)) throw new IllegalStateException("A non-loot outline was drawn");
                    }
                    if (!colored) throw new IllegalStateException("Matching-loot outline color was not preserved");
                }
                var path = Path.of(arguments[1] + (minimap ? "-minimap" : panel ? "-panel" : hud ? "-hud" : "-outlines") + ".bgra");
                Files.write(path,image);
                output.add(Map.of("panel",panel,"hud",hud,"minimap",minimap,"frames",frames,"changed_pixels",changed,"image",path.toString()));
            }
            dispose.invoke(null);
            release.invokeExact(texture);
            release.invokeExact(background);
            for (long buffer : buffers) release.invokeExact(buffer);
            samples.sort(Long::compare);
            var report = Map.of("mcopt", "0.3.0-alpha.2", "device",name.getString(0),"width",WIDTH,"height",HEIGHT,"in_flight",2,"jni_cpu_median_ms",samples.get(samples.size() / 2) / 1_000_000.0,"one_mcopt_command_buffer_per_frame",true,"frames",output,"scope","actual released mcopt native library and Seedy JNI renderer; no Minecraft instance launched; not an in-game FPS measurement");
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
            Files.writeString(Path.of(arguments[1] + ".json"),json + "\n");
            System.out.println(json);
        }
    }
}

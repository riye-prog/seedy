package dev.seedy;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.util.ArrayList;
import java.util.List;

public final class OutlineProjection {
    private static final int[][] EDGES = {{0,1},{0,2},{0,4},{1,3},{1,5},{2,3},{2,6},{3,7},{4,5},{4,6},{5,7},{6,7}};

    public static double[] frame(int width, int height) {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.level == null || !LoadedObjects.active()) return new double[0];
        var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (camera == null || !camera.initialized) return new double[0];
        var matrix = new Matrix4f(camera.projectionMatrix).mul(camera.viewRotationMatrix);
        var lines = new ArrayList<Double>();
        for (var object : LoadedObjects.snapshot()) {
            boolean treasure = object.kind().equals("Buried treasure candidate");
            boolean loot = object.kind().equals("Matching loot chest");
            if (loot ? !LoadedObjects.lootEnabled : treasure ? !LoadedObjects.treasureEnabled : !LoadedObjects.spawnersEnabled) continue;
            double x = object.x() - camera.pos.x, y = object.y() - camera.pos.y, z = object.z() - camera.pos.z;
            if (x * x + y * y + z * z > 512 * 512) continue;
            box(matrix, x, y, z, width, height, loot ? 2 : treasure ? 1 : 0, lines);
        }
        return lines.stream().mapToDouble(Double::doubleValue).toArray();
    }

    static void box(Matrix4f matrix, double x, double y, double z, int width, int height, int kind, List<Double> lines) {
        var corners = new Vector4f[8];
        for (int i = 0; i < 8; i++) corners[i] = matrix.transform(new Vector4f((float)(x + (i & 1)), (float)(y + ((i >> 1) & 1)), (float)(z + ((i >> 2) & 1)), 1));
        for (var edge : EDGES) {
            var a = new Vector4f(corners[edge[0]]);
            var b = new Vector4f(corners[edge[1]]);
            if (!clip(a, b)) continue;
            lines.add((double)(a.x / a.w + 1) * width / 2);
            lines.add((double)(1 - a.y / a.w) * height / 2);
            lines.add((double)(b.x / b.w + 1) * width / 2);
            lines.add((double)(1 - b.y / b.w) * height / 2);
            lines.add((double)kind);
        }
    }

    static boolean clip(Vector4f a, Vector4f b) {
        for (int plane = 0; plane < 7; plane++) {
            float start = plane(a, plane), end = plane(b, plane);
            if (start < 0 && end < 0) return false;
            if (start < 0 || end < 0) {
                var crossing = new Vector4f(a).lerp(b, start / (start - end));
                if (start < 0) a.set(crossing); else b.set(crossing);
            }
        }
        return true;
    }

    private static float plane(Vector4f p, int plane) {
        return switch (plane) { case 0 -> p.w - 0.001f; case 1 -> p.w + p.x; case 2 -> p.w - p.x; case 3 -> p.w + p.y; case 4 -> p.w - p.y; case 5 -> p.w + p.z; default -> p.w - p.z; };
    }
}

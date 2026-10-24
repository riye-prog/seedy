package dev.seedy;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import java.util.Arrays;
import java.util.List;

public final class OutlineProjection {
    private static final double[] EMPTY = new double[0];
    private static final ProjectionBuffer BUFFER = new ProjectionBuffer();
    private static final int[][] EDGES = {{0,1},{0,2},{0,4},{1,3},{1,5},{2,3},{2,6},{3,7},{4,5},{4,6},{5,7},{6,7}};

    public static double[] frame(int width, int height) {
        var client = net.minecraft.client.Minecraft.getInstance();
        if (client.level == null || !LoadedObjects.active()) return EMPTY;
        var camera = client.gameRenderer.gameRenderState().levelRenderState.cameraRenderState;
        if (camera == null || !camera.initialized) return EMPTY;
        BUFFER.matrix.set(camera.projectionMatrix).mul(camera.viewRotationMatrix);
        return project(BUFFER.matrix,LoadedObjects.snapshot(),camera.pos.x,camera.pos.y,camera.pos.z,width,height);
    }

    static double[] project(Matrix4f matrix, List<LoadedObjects.ObjectPosition> objects, double cameraX, double cameraY, double cameraZ, int width, int height) {
        BUFFER.count = 0;
        for (var object : objects) {
            if (!object.kind().equals("Matching loot chest")) continue;
            double x = object.x() - cameraX, y = object.y() - cameraY, z = object.z() - cameraZ;
            if (x * x + y * y + z * z <= 512 * 512) BUFFER.box(matrix,x,y,z,width,height,2);
        }
        return BUFFER.count == 0 ? EMPTY : Arrays.copyOf(BUFFER.values,BUFFER.count);
    }

    static void box(Matrix4f matrix, double x, double y, double z, int width, int height, int kind, List<Double> lines) {
        var buffer = new ProjectionBuffer();
        buffer.box(matrix,x,y,z,width,height,kind);
        for (int i = 0; i < buffer.count; i++) lines.add(buffer.values[i]);
    }

    private static final class ProjectionBuffer {
        private final Matrix4f matrix = new Matrix4f();
        private final Vector4f[] corners = new Vector4f[8];
        private final Vector4f a = new Vector4f(), b = new Vector4f();
        private double[] values = new double[256];
        private int count;

        private ProjectionBuffer() { for (int i = 0; i < corners.length; i++) corners[i] = new Vector4f(); }

        private void box(Matrix4f matrix, double x, double y, double z, int width, int height, int kind) {
            for (int i = 0; i < 8; i++) matrix.transform(corners[i].set((float)(x + (i & 1)),(float)(y + ((i >> 1) & 1)),(float)(z + ((i >> 2) & 1)),1));
            for (var edge : EDGES) {
                a.set(corners[edge[0]]); b.set(corners[edge[1]]);
                if (!clip(a,b)) continue;
                if (count + 5 > values.length) values = Arrays.copyOf(values,values.length * 2);
                values[count++] = (double)(a.x / a.w + 1) * width / 2;
                values[count++] = (double)(1 - a.y / a.w) * height / 2;
                values[count++] = (double)(b.x / b.w + 1) * width / 2;
                values[count++] = (double)(1 - b.y / b.w) * height / 2;
                values[count++] = kind;
            }
        }
    }

    static boolean clip(Vector4f a, Vector4f b) {
        for (int plane = 0; plane < 7; plane++) {
            float start = plane(a, plane), end = plane(b, plane);
            if (start < 0 && end < 0) return false;
            if (start < 0 || end < 0) {
                float fraction = start / (start - end);
                if (start < 0) a.lerp(b,fraction); else b.set(a.x + (b.x - a.x) * fraction,a.y + (b.y - a.y) * fraction,a.z + (b.z - a.z) * fraction,a.w + (b.w - a.w) * fraction);
            }
        }
        return true;
    }

    private static float plane(Vector4f p, int plane) {
        return switch (plane) { case 0 -> p.w - 0.001f; case 1 -> p.w + p.x; case 2 -> p.w - p.x; case 3 -> p.w + p.y; case 4 -> p.w - p.y; case 5 -> p.w + p.z; default -> p.w - p.z; };
    }
}

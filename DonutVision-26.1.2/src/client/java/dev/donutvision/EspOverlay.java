package dev.donutvision;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

/**
 * Block ESP / Player ESP overlay visible through geometry.
 * The custom 26.1.2 render pipeline intentionally has no depth test.
 * Game-world information is snapshotted during extraction, never read during the draw callback.
 */
public final class EspOverlay {
    private static final RenderPipeline XRAY_PIPELINE = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
                    .withLocation(Identifier.fromNamespaceAndPath("donutvision", "pipeline/esp_xray"))
                    .withDepthStencilState(Optional.empty())
                    .withCull(false)
                    .build());

    private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
    private static final Vector4f WHITE = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f ZERO = new Vector3f();
    private static final org.joml.Matrix4f IDENTITY = new org.joml.Matrix4f();

    private record Box(double minX, double minY, double minZ,
                       double maxX, double maxY, double maxZ, int color, boolean tracer) { }

    private static volatile List<Box> extracted = List.of();
    private static volatile Vec3 viewForward = new Vec3(0, 0, 1);
    private static MappableRingBuffer vertexBuffer;

    private EspOverlay() { }

    public static void install() {
        LevelRenderEvents.END_EXTRACTION.register(ctx -> extract());
        LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(EspOverlay::draw);
    }

    private static void extract() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) {
            extracted = List.of();
            return;
        }
        viewForward = mc.player.getLookAngle();
        List<Box> boxes = new ArrayList<>();
        if (DonutVisionClient.espEnabled()) {
            boolean tracer = DonutVisionClient.blockTracers();
            for (BlockEsp.Target target : BlockEsp.targets()) {
                double x = target.pos().getX(), y = target.pos().getY(), z = target.pos().getZ();
                boxes.add(new Box(x, y, z, x + 1, y + 1, z + 1, target.color(), tracer));
            }
        }
        if (PlayerEsp.enabled()) {
            // This list consists only of players already known to the client.
            for (AbstractClientPlayer player : mc.level.players()) {
                if (player == mc.player || player.isRemoved() || player.isSpectator()) continue;
                int playerRange = PlayerEsp.radius();
                if (playerRange != BlockEsp.UNLIMITED &&
                        player.position().distanceToSqr(mc.player.position()) > (double)playerRange * playerRange) continue;
                AABB hitbox = player.getBoundingBox();
                boxes.add(new Box(hitbox.minX, hitbox.minY, hitbox.minZ,
                        hitbox.maxX, hitbox.maxY, hitbox.maxZ,
                        0xFF32FF76, PlayerEsp.tracers()));
                if (boxes.size() >= 600) break;
            }
        }
        extracted = List.copyOf(boxes);
    }

    private static void draw(LevelRenderContext ctx) {
        List<Box> boxes = extracted;
        if (boxes.isEmpty()) return;
        Vec3 camera = ctx.levelState().cameraRenderState.pos;
        PoseStack stack = ctx.poseStack();
        stack.pushPose();
        stack.translate(-camera.x, -camera.y, -camera.z);
        Matrix4fc transform = stack.last().pose();
        BufferBuilder buffer = new BufferBuilder(ALLOCATOR,
                XRAY_PIPELINE.getVertexFormatMode(), XRAY_PIPELINE.getVertexFormat());

        int segmentCount = 0;
        for (Box b : boxes) {
            // Cube/frame edges. Solid-colored ribbons stay visible behind blocks or entities.
            double[][] v = {
                    {b.minX, b.minY, b.minZ}, {b.maxX, b.minY, b.minZ},
                    {b.maxX, b.maxY, b.minZ}, {b.minX, b.maxY, b.minZ},
                    {b.minX, b.minY, b.maxZ}, {b.maxX, b.minY, b.maxZ},
                    {b.maxX, b.maxY, b.maxZ}, {b.minX, b.maxY, b.maxZ}
            };
            int[][] edges = {
                    {0, 1}, {1, 2}, {2, 3}, {3, 0},
                    {4, 5}, {5, 6}, {6, 7}, {7, 4},
                    {0, 4}, {1, 5}, {2, 6}, {3, 7}
            };
            for (int[] edge : edges) {
                Vec3 a = new Vec3(v[edge[0]][0], v[edge[0]][1], v[edge[0]][2]);
                Vec3 c = new Vec3(v[edge[1]][0], v[edge[1]][1], v[edge[1]][2]);
                ribbon(buffer, transform, camera, a, c, b.color, 0.018);
                segmentCount++;
            }
            if (b.tracer) {
                // Draw a ray from close to the camera to the middle of the target.
                Vec3 mid = new Vec3((b.minX + b.maxX) * .5,
                        (b.minY + b.maxY) * .5, (b.minZ + b.maxZ) * .5);
                Vec3 offset = mid.subtract(camera);
                if (offset.lengthSqr() > 1.0) {
                    // Start in front of the camera, projecting to the CENTER of the screen.
                    Vec3 from = camera.add(viewForward.scale(0.65));
                    ribbon(buffer, transform, camera, from, mid, b.color, 0.012);
                    segmentCount++;
                }
            }
        }
        stack.popPose();
        if (segmentCount == 0) return;

        MeshData mesh = buffer.buildOrThrow();
        try {
            MeshData.DrawState state = mesh.drawState();
            VertexFormat format = state.format();
            int bytes = state.vertexCount() * format.getVertexSize();
            if (vertexBuffer == null || vertexBuffer.size() < bytes) {
                if (vertexBuffer != null) vertexBuffer.close();
                vertexBuffer = new MappableRingBuffer(() -> "DonutVision ESP geometry",
                        GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, bytes);
            }
            CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
            try (GpuBuffer.MappedView mapped = encoder.mapBuffer(
                    vertexBuffer.currentBuffer().slice(0, mesh.vertexBuffer().remaining()), false, true)) {
                MemoryUtil.memCopy(mesh.vertexBuffer(), mapped.data());
            }
            GpuBuffer indices;
            VertexFormat.IndexType indexType;
            if (XRAY_PIPELINE.getVertexFormatMode() == VertexFormat.Mode.QUADS) {
                mesh.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
                indices = format.uploadImmediateIndexBuffer(mesh.indexBuffer());
                indexType = mesh.drawState().indexType();
            } else {
                RenderSystem.AutoStorageIndexBuffer auto = RenderSystem.getSequentialBuffer(XRAY_PIPELINE.getVertexFormatMode());
                indices = auto.getBuffer(state.indexCount());
                indexType = auto.type();
            }
            GpuBufferSlice uniforms = RenderSystem.getDynamicUniforms()
                    .writeTransform(RenderSystem.getModelViewMatrix(), WHITE, ZERO, IDENTITY);
            Minecraft mc = Minecraft.getInstance();
            try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
                    () -> "DonutVision ESP pass",
                    mc.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
                    mc.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
                pass.setPipeline(XRAY_PIPELINE);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", uniforms);
                pass.setVertexBuffer(0, vertexBuffer.currentBuffer());
                pass.setIndexBuffer(indices, indexType);
                pass.drawIndexed(0, 0, state.indexCount(), 1);
            }
            vertexBuffer.rotate();
        } finally {
            mesh.close();
        }
    }

    /** Adds a camera-facing 3D quad representing a thin coloured line. */
    private static void ribbon(BufferBuilder buffer, Matrix4fc matrix, Vec3 camera,
                               Vec3 start, Vec3 end, int argb, double nominalWidth) {
        Vec3 direction = end.subtract(start);
        if (direction.lengthSqr() < 1.0e-7) return;
        Vec3 mid = start.add(end).scale(.5);
        Vec3 face = camera.subtract(mid);
        Vec3 across = direction.cross(face);
        if (across.lengthSqr() < 1.0e-7) across = direction.cross(new Vec3(0, 1, 0));
        if (across.lengthSqr() < 1.0e-7) across = direction.cross(new Vec3(1, 0, 0));
        double distance = Math.sqrt(camera.distanceToSqr(mid));
        Vec3 side = across.normalize().scale(Math.max(nominalWidth, distance * 0.001) * .5);
        emit(buffer, matrix, start.add(side), argb);
        emit(buffer, matrix, start.subtract(side), argb);
        emit(buffer, matrix, end.subtract(side), argb);
        emit(buffer, matrix, end.add(side), argb);
    }

    private static void emit(BufferBuilder buffer, Matrix4fc matrix, Vec3 p, int color) {
        buffer.addVertex(matrix, (float)p.x, (float)p.y, (float)p.z)
                .setColor(((color >>> 16) & 255) / 255f,
                          ((color >>> 8) & 255) / 255f,
                          (color & 255) / 255f, 1f);
    }
}

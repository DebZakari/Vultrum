package dev.vultrum.cull;

import org.joml.Matrix4f;

import java.nio.ByteBuffer;

/**
 * The cull's per-frame header: the std430 {@code Frame} block at binding 0 of {@code occlusion_cull.comp}
 * and {@code occlusion_hiz.comp}. The cull shader's {@code ivec4 sections[]} follows it at {@link #BYTES}:
 * section x, y, z and visibility key per command, a key below 0 leaving the command alone.
 * Offsets are in bytes; {@code CullFrameTest} checks them against both shaders.
 *
 * @param viewProjection camera-relative
 * @param pinned         benchmark: phase 1 keeps the last recorded visibility and phase 2 records none
 * @param commandBase    ring index of the first command
 * @param width0         Hi-Z level 0 size: depth size in 8-pixel blocks, rounded up
 */
record CullFrame(Matrix4f viewProjection, int cameraX, int cameraY, int cameraZ, boolean pinned,
                 float fracX, float fracY, float fracZ, int frame, int commandBase, int commandCount, int levels,
                 int depthWidth, int depthHeight, int width0, int height0) {
    static final int VIEW_PROJ = 0, CAMERA_INT = 64, PINNED = 76, CAMERA_FRAC = 80, FRAME = 96, COMMAND_BASE = 100,
            COMMAND_COUNT = 104, LEVELS = 108, DEPTH_SIZE = 112, SIZE0 = 120, BYTES = 128;
    static final int SECTION_BYTES = 16;

    /** Writes the header at the start of {@code to}, a direct little-endian buffer (JOML writes the matrix to its address). Leaves its position alone. */
    void pack(ByteBuffer to) {
        viewProjection.get(VIEW_PROJ, to);
        to.putInt(CAMERA_INT, cameraX).putInt(CAMERA_INT + 4, cameraY).putInt(CAMERA_INT + 8, cameraZ).putInt(PINNED, pinned ? 1 : 0);
        to.putFloat(CAMERA_FRAC, fracX).putFloat(CAMERA_FRAC + 4, fracY).putFloat(CAMERA_FRAC + 8, fracZ).putFloat(CAMERA_FRAC + 12, 0);
        to.putInt(FRAME, frame).putInt(COMMAND_BASE, commandBase).putInt(COMMAND_COUNT, commandCount).putInt(LEVELS, levels);
        to.putInt(DEPTH_SIZE, depthWidth).putInt(DEPTH_SIZE + 4, depthHeight).putInt(SIZE0, width0).putInt(SIZE0 + 4, height0);
    }

    static CullFrame unpack(ByteBuffer from) {
        return new CullFrame(new Matrix4f().set(VIEW_PROJ, from),
                from.getInt(CAMERA_INT), from.getInt(CAMERA_INT + 4), from.getInt(CAMERA_INT + 8), from.getInt(PINNED) != 0,
                from.getFloat(CAMERA_FRAC), from.getFloat(CAMERA_FRAC + 4), from.getFloat(CAMERA_FRAC + 8),
                from.getInt(FRAME), from.getInt(COMMAND_BASE), from.getInt(COMMAND_COUNT), from.getInt(LEVELS),
                from.getInt(DEPTH_SIZE), from.getInt(DEPTH_SIZE + 4), from.getInt(SIZE0), from.getInt(SIZE0 + 4));
    }
}

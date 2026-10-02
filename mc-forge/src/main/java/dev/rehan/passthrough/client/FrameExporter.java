package dev.rehan.passthrough.client;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.rehan.passthrough.Passthrough;
import java.lang.invoke.VarHandle;
import net.minecraft.client.Minecraft;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL32;
import org.lwjgl.system.MemoryUtil;

/**
 * Hands Minecraft's frame to the host through the shared memory "Local\MCPassthroughFrame".
 *
 * <pre>
 * header (4096 bytes, little-endian)
 *   0 int magic "MCPT"   4 int version (1)   8 int header bytes (4096)   12 int slot count (3)
 *   16 long slot stride   24 int max width   28 int max height
 *   32 long publish counter (bumped after each completed slot)   40 int latest slot (-1: none yet)   44 int Minecraft pid
 *   256 + 128 * i: slot i
 *     +0 long seq (odd while being written)   +8 long Minecraft frame   +16 long host frame
 *     +24 int width   +28 int height   +32 float near   +36 float far   +40 float vertical fov (degrees)
 *     +44 int flags: 1 = depth in [0, 1] (zZeroToOne), 2 = rows bottom-up, 4 = reversed Z (1 = near, 0 = far/empty)
 *     +48 double camera x, +56 y, +64 z   +72 float yaw   +76 pitch   +80 roll   +84 int first person
 *     +88 long capture time (System.nanoTime)   +96 long publish time
 * slot i data at 4096 + i * stride, each layer width * height * 4 bytes:
 *   world colour RGBA8 (premultiplied alpha), world depth float32, overlay RGBA8 (hand + HUD, premultiplied alpha)
 * </pre>
 *
 * The world layer is read just before the hand is drawn; the colour target is then cleared so what follows (hand,
 * screen effects, GUI) forms the overlay layer, read at the end of the frame. Readback is asynchronous: a ring of
 * pixel buffer objects, published when the frame's GPU fence has passed (next frame at the latest).
 *
 * <p>This is Minecraft 1.20.1's OpenGL: the depth buffer is the classic one (0 = near, 1 = far/empty, from clip z in
 * [-1, 1]), so the flags are just "rows bottom-up" and the host's effect linearises it accordingly.
 */
public final class FrameExporter {
	public static final String NAME = "Local\\MCPassthroughFrame";
	private static final int MAGIC = 0x5450434D;
	private static final int VERSION = 1;
	private static final int HEADER = 4096;
	private static final int SLOTS = 3;
	private static final int SLOT_DESC = 256;
	private static final int SLOT_DESC_BYTES = 128;
	private static final int MAX_W = 3840;
	private static final int MAX_H = 2160;
	private static final long LAYER_MAX = (long) MAX_W * MAX_H * 4;
	private static final long STRIDE = LAYER_MAX * 3;
	private static final int RING = 3;
	private static final long STUCK_NANOS = 1_000_000_000L;
	private static final int FLAG_ROWS_BOTTOM_UP = 2;
	private static final float NEAR = 0.05F;

	private static SharedMemory shm;
	private static boolean failed;
	private static boolean warnedSize;
	private static final Capture[] ring = new Capture[RING];
	private static int ringNext;
	private static int slotNext;
	private static long frameCounter;
	private static long publishCounter;
	private static Capture current;

	private FrameExporter() {
	}

	private static final class Capture {
		int color;
		int depth;
		int overlay;
		int width;
		int height;
		/** The fence after this capture's last read; 0 when nothing is pending. */
		long sync;
		long busySince;
		HostState.Pose pose;
		float far;
		long frame;
		long captureNanos;

		void allocate(final int w, final int h) {
			this.free();
			long n = (long) w * h * 4;
			this.color = buffer(n);
			this.depth = buffer(n);
			this.overlay = buffer(n);
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
			this.width = w;
			this.height = h;
		}

		private static int buffer(final long bytes) {
			int id = GL15.glGenBuffers();
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, id);
			GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, bytes, GL15.GL_STREAM_READ);
			return id;
		}

		void free() {
			this.cancel();
			for (int id : new int[] {this.color, this.depth, this.overlay}) {
				if (id != 0) {
					GL15.glDeleteBuffers(id);
				}
			}

			this.color = this.depth = this.overlay = 0;
		}

		void cancel() {
			if (this.sync != 0L) {
				GL32.glDeleteSync(this.sync);
				this.sync = 0L;
			}
		}
	}

	public static boolean exporting() {
		return shm != null;
	}

	private static boolean ensureShm() {
		if (shm != null) {
			return true;
		}

		if (failed) {
			return false;
		}

		try {
			shm = SharedMemory.create(NAME, HEADER + STRIDE * SLOTS);
			long m = shm.address;
			MemoryUtil.memPutInt(m, MAGIC);
			MemoryUtil.memPutInt(m + 4, VERSION);
			MemoryUtil.memPutInt(m + 8, HEADER);
			MemoryUtil.memPutInt(m + 12, SLOTS);
			MemoryUtil.memPutLong(m + 16, STRIDE);
			MemoryUtil.memPutInt(m + 24, MAX_W);
			MemoryUtil.memPutInt(m + 28, MAX_H);
			MemoryUtil.memPutInt(m + 40, -1);
			MemoryUtil.memPutInt(m + 44, (int) ProcessHandle.current().pid());
			Passthrough.LOG.info("frame export: shared memory {} ({} MB)", NAME, (HEADER + STRIDE * SLOTS) >> 20);
			return true;
		} catch (Throwable t) {
			failed = true;
			Passthrough.LOG.error("frame export disabled: couldn't create shared memory", t);
			return false;
		}
	}

	/** GameRenderer.renderLevel, just before the hand: read the world layer, then clear colour for the overlay. */
	public static void captureWorld(final RenderTarget target) {
		RenderSystem.assertOnRenderThread();
		current = null;
		poll();
		HostState.Pose pose = HostState.frame();
		if (pose == null || !ensureShm()) {
			return;
		}

		int w = target.width;
		int h = target.height;
		if ((long) w * h * 4 > LAYER_MAX) {
			if (!warnedSize) {
				warnedSize = true;
				Passthrough.LOG.warn("frame export: {}x{} is bigger than {}x{}, not exporting", w, h, MAX_W, MAX_H);
			}

			return;
		}

		Capture c = ring[ringNext];
		if (c == null) {
			c = ring[ringNext] = new Capture();
		}

		long now = System.nanoTime();
		if (c.sync != 0L) {
			if (now - c.busySince < STUCK_NANOS) {
				return; // the GPU hasn't finished the frame this buffer holds: skip this one
			}

			c.cancel();
		}

		if (c.width != w || c.height != h || c.color == 0) {
			c.allocate(w, h);
		}

		c.busySince = now;
		c.pose = pose;
		c.far = Minecraft.getInstance().gameRenderer.getDepthFar();
		c.frame = ++frameCounter;
		c.captureNanos = now;
		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
		packState();
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, c.color);
		GL11.glReadPixels(0, 0, w, h, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, c.depth);
		GL11.glReadPixels(0, 0, w, h, GL11.GL_DEPTH_COMPONENT, GL11.GL_FLOAT, 0L);
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		RenderSystem.colorMask(true, true, true, true);
		RenderSystem.clearColor(0.0F, 0.0F, 0.0F, 0.0F);
		RenderSystem.clear(GL11.GL_COLOR_BUFFER_BIT, Minecraft.ON_OSX);
		current = c;
	}

	/** End of GameRenderer.render: the overlay (hand, screen effects, GUI) is complete. */
	public static void captureOverlay(final RenderTarget target) {
		RenderSystem.assertOnRenderThread();
		Capture c = current;
		current = null;
		if (c == null) {
			return;
		}

		if (target.width != c.width || target.height != c.height) {
			return;
		}

		GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, target.frameBufferId);
		packState();
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, c.overlay);
		GL11.glReadPixels(0, 0, c.width, c.height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L);
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
		c.sync = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
		ringNext = (ringNext + 1) % RING;
	}

	private static void packState() {
		GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
		GL11.glPixelStorei(GL11.GL_PACK_ROW_LENGTH, 0);
		GL11.glPixelStorei(GL11.GL_PACK_SKIP_ROWS, 0);
		GL11.glPixelStorei(GL11.GL_PACK_SKIP_PIXELS, 0);
	}

	/** Publish every capture whose reads the GPU has finished, oldest first. */
	private static void poll() {
		for (int i = 0; i < RING; i++) {
			Capture c = ring[(ringNext + i) % RING];
			if (c == null || c.sync == 0L) {
				continue;
			}

			int state = GL32.glClientWaitSync(c.sync, 0, 0L);
			if (state == GL32.GL_TIMEOUT_EXPIRED) {
				continue;
			}

			c.cancel();
			if (state == GL32.GL_ALREADY_SIGNALED || state == GL32.GL_CONDITION_SATISFIED) {
				publish(c);
			}
		}
	}

	private static void publish(final Capture c) {
		try {
			long m = shm.address;
			int slot = slotNext;
			slotNext = (slotNext + 1) % SLOTS;
			long desc = m + SLOT_DESC + (long) SLOT_DESC_BYTES * slot;
			long seq = MemoryUtil.memGetLong(desc);
			if ((seq & 1L) != 0L) {
				seq++;
			}

			MemoryUtil.memPutLong(desc, seq + 1L);
			VarHandle.fullFence();
			long base = m + HEADER + STRIDE * slot;
			long n = (long) c.width * c.height * 4;
			boolean ok = copy(c.color, base, n) & copy(c.depth, base + n, n) & copy(c.overlay, base + 2 * n, n);
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0);
			HostState.Pose p = c.pose;
			MemoryUtil.memPutLong(desc + 8, c.frame);
			MemoryUtil.memPutLong(desc + 16, p.hostFrame());
			MemoryUtil.memPutInt(desc + 24, ok ? c.width : 0);
			MemoryUtil.memPutInt(desc + 28, ok ? c.height : 0);
			MemoryUtil.memPutFloat(desc + 32, NEAR);
			MemoryUtil.memPutFloat(desc + 36, c.far);
			MemoryUtil.memPutFloat(desc + 40, p.fov());
			MemoryUtil.memPutInt(desc + 44, FLAG_ROWS_BOTTOM_UP);
			MemoryUtil.memPutDouble(desc + 48, p.x());
			MemoryUtil.memPutDouble(desc + 56, p.y());
			MemoryUtil.memPutDouble(desc + 64, p.z());
			MemoryUtil.memPutFloat(desc + 72, p.yaw());
			MemoryUtil.memPutFloat(desc + 76, p.pitch());
			MemoryUtil.memPutFloat(desc + 80, p.roll());
			MemoryUtil.memPutInt(desc + 84, p.firstPerson() ? 1 : 0);
			MemoryUtil.memPutLong(desc + 88, c.captureNanos);
			MemoryUtil.memPutLong(desc + 96, System.nanoTime());
			VarHandle.fullFence();
			MemoryUtil.memPutLong(desc, seq + 2L);
			if (ok) {
				MemoryUtil.memPutInt(m + 40, slot);
				VarHandle.fullFence();
				MemoryUtil.memPutLong(m + 32, ++publishCounter);
			}
		} catch (RuntimeException e) {
			Passthrough.LOG.warn("frame export failed", e);
		}
	}

	private static boolean copy(final int buffer, final long dst, final long n) {
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, buffer);
		long src = GL30.nglMapBufferRange(GL21.GL_PIXEL_PACK_BUFFER, 0L, n, GL30.GL_MAP_READ_BIT);
		if (src == 0L) {
			return false;
		}

		MemoryUtil.memCopy(src, dst, n);
		GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER);
		return true;
	}
}

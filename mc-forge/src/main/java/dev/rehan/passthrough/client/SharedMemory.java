package dev.rehan.passthrough.client;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.win32.StdCallLibrary;

/**
 * A named, pagefile-backed Win32 file mapping: other processes open it by name, and nothing touches the disk.
 * (Java 17 has no finished foreign-function API, so this goes through the JNA that Minecraft already ships.)
 */
final class SharedMemory {
	private static final int PAGE_READWRITE = 0x04;
	private static final int FILE_MAP_ALL_ACCESS = 0xF001F;
	/** The view's address, for LWJGL's MemoryUtil. */
	final long address;
	final long size;

	private interface Kernel32 extends StdCallLibrary {
		Pointer CreateFileMappingW(Pointer file, Pointer attributes, int protect, int sizeHigh, int sizeLow, WString name);

		Pointer MapViewOfFile(Pointer mapping, int access, int offsetHigh, int offsetLow, long bytes);
	}

	private SharedMemory(final long address, final long size) {
		this.address = address;
		this.size = size;
	}

	static SharedMemory create(final String name, final long size) {
		Kernel32 kernel32 = Native.load("kernel32", Kernel32.class);
		Pointer handle = kernel32.CreateFileMappingW(Pointer.createConstant(-1L), null, PAGE_READWRITE, (int) (size >>> 32), (int) size, new WString(name));
		if (handle == null) {
			throw new IllegalStateException("CreateFileMappingW failed for " + name + " (error " + Native.getLastError() + ")");
		}

		Pointer view = kernel32.MapViewOfFile(handle, FILE_MAP_ALL_ACCESS, 0, 0, size);
		if (view == null) {
			throw new IllegalStateException("MapViewOfFile failed for " + name + " (error " + Native.getLastError() + ")");
		}

		return new SharedMemory(Pointer.nativeValue(view), size);
	}
}

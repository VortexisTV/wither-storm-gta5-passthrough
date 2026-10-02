package dev.rehan.passthrough.client;

import dev.rehan.passthrough.Passthrough;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * A small WebSocket server (RFC 6455, text frames) on the loopback interface: all the host link needs, with nothing
 * to bundle into the mod jar. Each connection has a reader thread (messages are handled on it) and a writer thread
 * fed by a queue, so a send never blocks the game.
 */
abstract class WsServer {
	private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
	private static final int MAX_MESSAGE = 32 << 20;
	private static final int MAX_QUEUED = 4096;
	private final int port;
	private final Set<Conn> connections = ConcurrentHashMap.newKeySet();
	private volatile ServerSocket server;

	WsServer(final int port) {
		this.port = port;
	}

	final class Conn {
		private final Socket socket;
		private final LinkedBlockingQueue<byte[]> out = new LinkedBlockingQueue<>(MAX_QUEUED);
		private volatile boolean open = true;

		private Conn(final Socket socket) {
			this.socket = socket;
		}

		SocketAddress remote() {
			return this.socket.getRemoteSocketAddress();
		}

		boolean isOpen() {
			return this.open;
		}

		/** Queue a text message; dropped if the client has stopped reading (the queue is full). */
		void send(final String text) {
			if (this.open) {
				this.out.offer(frame(0x1, text.getBytes(StandardCharsets.UTF_8)));
			}
		}

		private void close() {
			if (!this.open) {
				return;
			}

			this.open = false;
			this.out.clear();
			this.out.offer(new byte[0]); // wakes the writer
			try {
				this.socket.close();
			} catch (IOException ignored) {
			}
		}
	}

	abstract void onStart(int port);

	abstract void onOpen(Conn conn);

	abstract void onMessage(Conn conn, String message);

	abstract void onClose(Conn conn);

	Set<Conn> connections() {
		return this.connections;
	}

	void broadcast(final String text) {
		if (this.connections.isEmpty()) {
			return;
		}

		byte[] frame = frame(0x1, text.getBytes(StandardCharsets.UTF_8));
		for (Conn c : this.connections) {
			if (c.open) {
				c.out.offer(frame);
			}
		}
	}

	void start() {
		Thread t = new Thread(this::accept, "passthrough-link");
		t.setDaemon(true);
		t.start();
	}

	private void accept() {
		try (ServerSocket s = new ServerSocket()) {
			s.setReuseAddress(true);
			// 127.0.0.1 by its bytes: the host connects to that address, and getLoopbackAddress() is ::1 on some setups
			s.bind(new InetSocketAddress(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}), this.port));
			this.server = s;
			this.onStart(this.port);
			while (!s.isClosed()) {
				Socket socket = s.accept();
				socket.setTcpNoDelay(true);
				Conn conn = new Conn(socket);
				Thread reader = new Thread(() -> this.serve(conn), "passthrough-link-read");
				reader.setDaemon(true);
				reader.start();
			}
		} catch (IOException e) {
			Passthrough.LOG.error("host link: can't listen on 127.0.0.1:{} (is another Minecraft with the passthrough mod running?)", this.port, e);
		}
	}

	private void serve(final Conn conn) {
		try {
			DataInputStream in = new DataInputStream(new BufferedInputStream(conn.socket.getInputStream(), 1 << 16));
			OutputStream out = conn.socket.getOutputStream();
			if (!handshake(in, out)) {
				conn.close();
				return;
			}

			this.connections.add(conn);
			Thread writer = new Thread(() -> write(conn, out), "passthrough-link-write");
			writer.setDaemon(true);
			writer.start();
			this.onOpen(conn);
			ByteArrayOutputStream partial = new ByteArrayOutputStream();
			while (conn.open) {
				int b0 = in.readUnsignedByte();
				int b1 = in.readUnsignedByte();
				int opcode = b0 & 0x0F;
				boolean fin = (b0 & 0x80) != 0;
				long size = b1 & 0x7F;
				if (size == 126) {
					size = in.readUnsignedShort();
				} else if (size == 127) {
					size = in.readLong();
				}

				if (size < 0 || size + partial.size() > MAX_MESSAGE) {
					break;
				}

				byte[] mask = new byte[4];
				boolean masked = (b1 & 0x80) != 0;
				if (masked) {
					in.readFully(mask);
				}

				byte[] payload = new byte[(int) size];
				in.readFully(payload);
				if (masked) {
					for (int i = 0; i < payload.length; i++) {
						payload[i] ^= mask[i & 3];
					}
				}

				switch (opcode) {
					case 0x0, 0x1 -> {
						partial.write(payload, 0, payload.length);
						if (fin) {
							String message = partial.toString(StandardCharsets.UTF_8);
							partial.reset();
							try {
								this.onMessage(conn, message);
							} catch (RuntimeException e) {
								Passthrough.LOG.warn("host link: message handler failed", e);
							}
						}
					}
					case 0x8 -> conn.close();
					case 0x9 -> conn.out.offer(frame(0xA, payload));
					default -> {
					}
				}
			}
		} catch (IOException ignored) {
			// the host went away
		} finally {
			boolean was = this.connections.remove(conn);
			conn.close();
			if (was) {
				this.onClose(conn);
			}
		}
	}

	private static void write(final Conn conn, final OutputStream out) {
		try {
			while (conn.open) {
				byte[] frame = conn.out.take();
				if (frame.length == 0) {
					continue;
				}

				out.write(frame);
				// coalesce whatever else is waiting into the same flush
				for (byte[] more; (more = conn.out.poll()) != null;) {
					if (more.length > 0) {
						out.write(more);
					}
				}

				out.flush();
			}
		} catch (IOException | InterruptedException ignored) {
		} finally {
			conn.close();
		}
	}

	private static boolean handshake(final DataInputStream in, final OutputStream out) throws IOException {
		String key = null;
		StringBuilder line = new StringBuilder();
		int total = 0;
		while (true) {
			int c = in.read();
			if (c < 0 || ++total > 16384) {
				return false;
			}

			if (c == '\r') {
				continue;
			}

			if (c != '\n') {
				line.append((char) c);
				continue;
			}

			if (line.isEmpty()) {
				break;
			}

			int colon = line.indexOf(":");
			if (colon > 0 && line.substring(0, colon).trim().equalsIgnoreCase("Sec-WebSocket-Key")) {
				key = line.substring(colon + 1).trim();
			}

			line.setLength(0);
		}

		if (key == null) {
			out.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
			out.flush();
			return false;
		}

		String accept;
		try {
			accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.US_ASCII)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IOException(e);
		}

		out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n")
			.getBytes(StandardCharsets.US_ASCII));
		out.flush();
		return true;
	}

	/** A final, unmasked (server to client) frame. */
	private static byte[] frame(final int opcode, final byte[] payload) {
		int n = payload.length;
		int header = n < 126 ? 2 : n < 65536 ? 4 : 10;
		byte[] f = new byte[header + n];
		f[0] = (byte) (0x80 | opcode);
		if (n < 126) {
			f[1] = (byte) n;
		} else if (n < 65536) {
			f[1] = 126;
			f[2] = (byte) (n >>> 8);
			f[3] = (byte) n;
		} else {
			f[1] = 127;
			for (int i = 0; i < 8; i++) {
				f[2 + i] = (byte) ((long) n >>> (8 * (7 - i)));
			}
		}

		System.arraycopy(payload, 0, f, header, n);
		return f;
	}
}

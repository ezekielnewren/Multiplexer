package com.github.ezekielnewren.net.multiplexer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

public class Multiplexer {

	private final Multiplexer home = this;
	private final DataInputStream input;
	private final DataOutputStream output;
	private final byte[] recvBuffer = new byte[8+0xffff+4];
	private final byte[] sendBuffer = new byte[8+4+1];
	private final CRC32 sendCRC = new CRC32();
	private final BitSet channelBound = new BitSet();
	
	private boolean closed = false;
	
	final Demultiplexer segregator;
	final Object mutex = this;
	
	static final int FLAG_NULL = 0x0;
	static final int FLAG_SYN = 0x1;
	static final int FLAG_RST = 0x2;
	static final int FLAG_OCL = 0x4;
	static final int FLAG_ICL = 0x8;
	static final int FLAG_IOCL = FLAG_ICL|FLAG_OCL;
	static final int FLAG_KAL = 0x10;
	static final int FLAG_MCL = 0x20;
	static final int FLAG_CLI = 0x40;

	private static int i = 0;
	static final long STATE_UNBOUND = (1<<i++);
	static final long STATE_BOUND = (1<<i++);
	static final long STATE_LISTENING = (1<<i++);
	static final long STATE_PRE_PASV_OPEN = (1<<i++);
	static final long STATE_ACCEPTING = (1<<i++);
	static final long STATE_ACCEPTED = (1<<i++);
	static final long STATE_CONNECTING = (1<<i++);
	static final long STATE_CONNECTED = (1<<i++);
	static final long STATE_ESTABLISHED = STATE_ACCEPTED|STATE_CONNECTED;
	static final long STATE_INPUT_CLOSING = (1<<i++);
	static final long STATE_INPUT_CLOSED = (1<<i++);
	static final long STATE_OUTPUT_CLOSING = (1<<i++);
	static final long STATE_OUTPUT_CLOSED = (1<<i++);
	static final long STATE_CHANNEL_CLOSING = (1<<i++);
	static final long STATE_CHANNEL_CLOSED = STATE_INPUT_CLOSED|STATE_OUTPUT_CLOSED;
	
	public Multiplexer(InputStream is, OutputStream os, int... prePasvOpen) throws IOException {
		input = (is instanceof DataInputStream)?(DataInputStream)is:new DataInputStream(is);
		output = (os instanceof DataOutputStream)?(DataOutputStream)os:new DataOutputStream(os);
		
		segregator = null;
		
	}

	void writePacket(int channel, int processed, byte[] b, int off, int len, int flags) throws IOException {
		synchronized(mutex) {
			// 2B_len, 2B_channel, 3B_proc, 1B_flags, ...B_payload, 4B_crc
			if (b!=null) {
				if ((off < 0) || (off > b.length) || (len < 0) ||
						((off + len) > b.length) || ((off + len) < 0) ||
						(len>0xffff)) {
					throw new IndexOutOfBoundsException();
				}
			} else {
				if ((off|len)!=0) throw new IllegalArgumentException("len and off must be 0 if b is null");
			}

			// header
			numberToBytes(len, sendBuffer, 0, 2);
			numberToBytes(channel, sendBuffer, 2, 2);
			numberToBytes(processed, sendBuffer, 4, 3);
			sendBuffer[7] = (byte) flags;

			// trailer
			sendCRC.reset();
			sendCRC.update(sendBuffer, 0, 8+len);
			numberToBytes(sendCRC.getValue(), sendBuffer, 8+len, 4);

			try {
				output.write(sendBuffer, 0, 8);
				if (b!=null) output.write(b, 0, len);
				output.write(sendBuffer, 8, 4);
			} catch (IOException ioe) {
				closeQuietly();
				throw ioe;
			}
		}
	}

	void writePacketWithOneByte(int channel, int processed, int byteValue) throws IOException {
		synchronized(mutex) {
			sendBuffer[12] = (byte) byteValue;
			writePacket(channel, processed, sendBuffer, 12, 1, FLAG_NULL);
		}
	}

	void writePacket(int channel, int processed, int flags) throws IOException {
		writePacket(channel, processed, null, 0, 0, flags);
	}

	class Demultiplexer implements Runnable {

		final Multiplexer home;
		final AtomicBoolean signal;
		
		public Demultiplexer(Multiplexer parent) {
			this.home = parent;
			signal = new AtomicBoolean();
			Thread handle = new Thread(this);
			handle.setName(Thread.currentThread().getName()+"segregator");
			handle.setDaemon(true);
			handle.start();
		}

		@Override
		public void run() {
			try {
				// read packet
				CRC32 crc = new CRC32();
				
				while (!isClosed()) {
					// 2B_len, 2B_channel, 3B_proc, 1B_flags, ...B_payload, 4B_crc
					crc.reset();
					input.readFully(recvBuffer, 0, 8);
					int len = (int) bytesToNumber(recvBuffer, 0, 2);
					int channel = (int) bytesToNumber(recvBuffer, 2, 2);
					int proc = (int) bytesToNumber(recvBuffer, 4, 3);
					int flags = (int) bytesToNumber(recvBuffer, 7, 1);

					input.readFully(recvBuffer, 8, len+4);
					long packetCRC = bytesToNumber(recvBuffer, 8+len, 4);

					crc.update(recvBuffer, 0, 8+len);
					long realCRC = crc.getValue();
					if (packetCRC!=realCRC) throw new IOException("Malformed packet");

					// process packet
					synchronized(mutex) {
					}
				}
			} catch (IOException ioe) {
				closeQuietly();
			}
		}
	}

	public boolean isClosed() {
		return closed;
	}

	private void closeQuietly() {
		
	}
	
	

//		static void validChannel(int channel) {
//			if (!(0<=channel&&channel<=0xffff)) throw new IllegalArgumentException();
//		}
//
//		static void validBufferSize(int recvBufferSize) {
//			if (!(0<recvBufferSize&&recvBufferSize<=0xffffff)) throw new IllegalArgumentException("bufferSize must be between 1 and 16777215 inclusive");
//		}
//
//		static void valid(int channel, int recvBufferSize) {
//			validChannel(channel);
//			validBufferSize(recvBufferSize);
//		}


	// read/write numbers
	private static long bytesToNumber(byte[] num, int off, int len) {
		if (off+len > num.length) throw new IndexOutOfBoundsException("byte array len="+num.length+" off="+off+" len="+len);
		if (len < 1 || len > 8) throw new IndexOutOfBoundsException("Improper length "+len);
		long out = 0;
		for (int i=0; i<len; i++) {
			out += num[off+i]&0xFF;
			if (i<len-1) out <<= 8;
		}
		return out;
	}

	private static void numberToBytes(long num, byte[] b, int off, int len) {
		if (off+len > b.length) throw new IndexOutOfBoundsException("byte array len="+b.length+" off="+off+" len="+len);
		if (len < 1 || len > 8) throw new IndexOutOfBoundsException("Improper length "+len);
		for (int i=0; i<len; i++) {
			b[off+i] = (byte) ((num>>>(len-1-i)*8)&0xFF);
		}
	}
}

package com.github.ezekielnewren.net.multiplexer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

public class Multiplexer implements ClientMultiplexer, ServerMultiplexer {

	private final Multiplexer home = this;
	private final DataInputStream input;
	private final DataOutputStream output;
	private final byte[] recvBuffer = new byte[8+0xffff+4];
	private final byte[] sendBuffer = new byte[8+4+1];
	private final CRC32 sendCRC = new CRC32();
	private final BitSet channelBound = new BitSet();
	private final ChannelMetadata[] cmMeta = new ChannelMetadata[0x10000];
	
	private boolean closed = false;
	
	final Demultiplexer segregator;
	final Object mutex = new Object();
	
	static final int FLAG_NULL = 0x0;
	static final int FLAG_SYN = 0x1;
	static final int FLAG_CLI = 0x2;
	static final int FLAG_MSG = 0x4; // message mode
	static final int FLAG_RST = 0x8;
	static final int FLAG_KAL = 0x10;
	static final int FLAG_OCL = 0x20;
	static final int FLAG_ICL = 0x40;
	static final int FLAG_IOCL = FLAG_ICL|FLAG_OCL;
	static final int FLAG_MCL = 0x80;

	private static int i = 0;
	static final long STATE_UNBOUND = (1<<i++);
	static final long STATE_BOUND = (1<<i++);
	static final long STATE_LISTENING = (1<<i++);
	static final long STATE_ACCEPTING = (1<<i++);
	static final long STATE_CONNECTING = (1<<i++);
	static final long STATE_ESTABLISHED = (1<<i++);
	static final long STATE_CHANNEL_CLOSING = (1<<i++);
	static final long STATE_CHANNEL_CLOSED = (1<<i++);

	static final long LINGERING_STATES = STATE_CONNECTING|STATE_ACCEPTING|STATE_LISTENING|STATE_CHANNEL_CLOSING;
	
	public static final long DEFAULT_ACCEPT_TIMEOUT = Long.MAX_VALUE;
	public static final long DEFAULT_CONNECTION_TIMEOUT = 60000;
	
	public Multiplexer(InputStream is, OutputStream os, int... prePasvOpen) throws IOException {
		input = (is instanceof DataInputStream)?(DataInputStream)is:new DataInputStream(is);
		output = (os instanceof DataOutputStream)?(DataOutputStream)os:new DataOutputStream(os);
		
		for (int i=0; i<cmMeta.length; i++) {
			cmMeta[i] = new ChannelMetadata();
		}

		synchronized(mutex) {
			for (int i=0; i<prePasvOpen.length; i++) {
				bind(prePasvOpen[i]);
				getCM(prePasvOpen[i]).state = STATE_LISTENING;
			}
		}
		
		segregator = new Demultiplexer();
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
	
	private boolean isBound(int channel) {
		return channelBound.get(channel);
	}

	private void bind(int channel) throws IOException {
		// check the arguments and state
		ChannelMetadata cmMeta = getCM(channel);

		assert(Thread.holdsLock(mutex));
		if (channelBound.get(channel)) throw new ChannelBindException();
		if (cmMeta.state != STATE_UNBOUND) throw new IllegalStateException();
		
		
		cmMeta.clear();
		channelBound.set(channel);
		cmMeta.state = STATE_BOUND;
	}
	
	private void unbind(int channel) throws IOException {
		ChannelMetadata cmMeta = getCM(channel);

		assert(Thread.holdsLock(mutex));
		if (cmMeta.state!=STATE_CHANNEL_CLOSED) throw new IllegalStateException();
		
		channelBound.clear(channel);
		cmMeta.clear();
	}
	
	private Channel connect(int channel, int recvBufferSize, long timeout, boolean messageMode) throws IOException {
		ChannelMetadata cmMeta = getCM(channel);
		validBufferSize(recvBufferSize);
		if (timeout<0) throw new IllegalArgumentException("timeout cannot be negative");
		
		synchronized(mutex) {
			try {
				bind(channel);
				cmMeta.state = STATE_CONNECTING;
				
				// send synchronize
				writePacket(channel, recvBufferSize, FLAG_SYN|FLAG_CLI);
				
				// wait for response
				final AtomicLong timer = new AtomicLong();
				while (timer.get()<timeout && !cmMeta.nextPacketRead.compareAndSet(true, false)) {
					linger(timeout, timer);
				}
				
				// if timedout throw and error
				if (timer.get()>timeout) {
					cmMeta.state = STATE_CHANNEL_CLOSED;
					unbind(channel);
					throw new ChannelTimeoutException();
				}
				
				if (cmMeta.reset) {
					cmMeta.state = STATE_CHANNEL_CLOSED;
					unbind(channel);
					throw new ChannelResetException();
				}
				
				Channel cm;
				if (messageMode) {
					cm = new DatagramPacketChannel(this, channel, recvBufferSize, cmMeta.proc);
				} else {
					cm = new StreamChannel(this, channel, recvBufferSize, cmMeta.proc);
				}
				cmMeta.ptr = cm;
				cmMeta.state = STATE_ESTABLISHED;
				
				
				return cm;
			} finally {
				mutex.notifyAll();
			}
		}
	}
	
	private Channel accept(int channel, int recvBufferSize, long timeout, boolean messageMode, boolean recurring) throws IOException {
		ChannelMetadata cmMeta = getCM(channel);
		validBufferSize(recvBufferSize);
		if (timeout<0) throw new IllegalArgumentException();
		
		synchronized(mutex) {
			try {
				if (cmMeta.state!=STATE_LISTENING) {
					bind(channel);
				}
				cmMeta.state = STATE_ACCEPTING;
	
				Channel cm;
				if (messageMode) {
					cm = new DatagramPacketChannel(this, channel, recvBufferSize, cmMeta.proc);
				} else {
					cm = new StreamChannel(this, channel, recvBufferSize, cmMeta.proc);
				}
				
				return cm;
			} finally {
				cmMeta.nextPacketRead.set(true);
				mutex.notifyAll();
			}
		}
	}
	
	public StreamChannel connectStreamChannel(int channel, int recvBufferSize, long timeout) throws IOException {
		return (StreamChannel) connect(channel, recvBufferSize, timeout, false);
	}
	
	class Demultiplexer implements Runnable {

		//final AtomicBoolean signal = new AtomicBoolean();
		
		public Demultiplexer() {
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
					
					// header fields
					int len = (int) bytesToNumber(recvBuffer, 0, 2);
					int channel = (int) bytesToNumber(recvBuffer, 2, 2);
					int proc = (int) bytesToNumber(recvBuffer, 4, 3);
					int flags = (int) bytesToNumber(recvBuffer, 7, 1);

					// payload+crc
					input.readFully(recvBuffer, 8, len+4);
					long packetCRC = bytesToNumber(recvBuffer, 8+len, 4);

					// verify header and payload with trailer
					crc.update(recvBuffer, 0, 8+len);
					long realCRC = crc.getValue();
					if (packetCRC!=realCRC) throw new IOException("Malformed packet");

					ChannelMetadata cmMeta = getCM(channel);
					
					// process packet
					synchronized(mutex) {
						try {
							cmMeta.proc = proc;
							
							if ( (flags&FLAG_MCL)!=0 ) {
								close();
							}
							
//							static final int FLAG_NULL = 0x0;
//							static final int FLAG_SYN = 0x1;
//							static final int FLAG_CLI = 0x2;
//							static final int FLAG_MSG = 0x4; // message mode
//							static final int FLAG_RST = 0x8;
//							static final int FLAG_KAL = 0x10;
//							static final int FLAG_OCL = 0x20;
//							static final int FLAG_ICL = 0x40;
//							static final int FLAG_IOCL = FLAG_ICL|FLAG_OCL;
//							static final int FLAG_MCL = 0x80;
							
							switch (flags) {
							case FLAG_SYN:
							case FLAG_SYN|FLAG_CLI:
							case FLAG_SYN|FLAG_MSG:
							case FLAG_SYN|FLAG_CLI|FLAG_MSG:
								break;
							
							case FLAG_RST:
								break;
								
							case FLAG_KAL:
								break;
								
							case FLAG_OCL:
								
								break;
								
							case FLAG_ICL:
								
								break;
							
							case FLAG_IOCL:
								
								break;
								
							case FLAG_MCL:
								break;
								
							default:
								assert(false):"unknown flag combination";
								break;
							}
							
							
							if (cmMeta.state == STATE_ESTABLISHED) {
								cmMeta.ptr.feed(recvBuffer, 8, len);
							}
							
							
							
							final AtomicLong timer = new AtomicLong();
							if ((cmMeta.state&LINGERING_STATES)!=0) {
								cmMeta.nextPacketRead.set(true);
								while ((cmMeta.state&LINGERING_STATES) != 0) {
									linger(0, timer);
								}
							}
							
						} finally {
							mutex.notifyAll();
						}
					}
				}
			} catch (IOException ioe) {
				closeQuietly();
			}
		}
		
		void resetChannel(int channel) {
			ChannelMetadata cmMeta = getCM(channel);
			
			assert(Thread.holdsLock(mutex));
			cmMeta.reset = true;
			if (cmMeta.ptr!=null) cmMeta.ptr.closeQuietly();
		}
		
		void sendReset(int channel) throws IOException {
			getCM(channel);
			
			assert(Thread.holdsLock(mutex));
			writePacket(channel, 0, FLAG_RST);
		}
		
	}

	public boolean isClosed() {
		synchronized(mutex) {
			return closed;
		}
	}

	private void closeQuietly() {
		closed = true;
	}
	
	public void close() throws IOException {
		synchronized(mutex) {
			if (closed) return;
			
			
			closed = true;
		}
	}
	
	class ChannelMetadata {
		final AtomicBoolean nextPacketRead = new AtomicBoolean();
		Channel ptr;
		long state = STATE_UNBOUND;
		boolean reset;
		int proc;
		
		void clear() {
			nextPacketRead.set(false);
			ptr = null;
			state = STATE_UNBOUND;
			reset = false;
			proc = 0;
		}
	}

	ChannelMetadata getCM(int index) {
		 
		ChannelMetadata cm = cmMeta[index];
		if (cm==null) throw new NullPointerException();
		return cm;
	}

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
	
	private void validChannel(int channel) {
		if (!(0<=channel&&channel<cmMeta.length)) throw new IllegalArgumentException("invalid channel "+channel);
	}
	
	private static void validBufferSize(int recvBufferSize) {
		if (!(0<recvBufferSize&&recvBufferSize<=0xffffff)) throw new IllegalArgumentException("bufferSize must fall within 1 and 16777215 inclusive");
	}

	long linger(long waitForMillis, final AtomicLong timer) {
		if (timer.get()<0) throw new IllegalArgumentException();
		if (timer.get()>waitForMillis) return 0;
		if (waitForMillis!=0) waitForMillis -= timer.get();
		long beg = System.nanoTime();
		try {
			mutex.wait(waitForMillis);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
		long time = (System.nanoTime()-beg)/1000000;
		timer.addAndGet(time);
		return time;
	}

	@Override
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel,
			int bufferSize, long timeout, boolean recurring) throws IOException {
		
		return (DatagramPacketChannel) accept(channel, bufferSize, timeout, true, recurring);
	}

	@Override
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel,
			int bufferSize, boolean recurring) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel,
			int bufferSize, long timeout) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel,
			int bufferSize) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public StreamChannel acceptStreamChannel(int channel, int bufferSize,
			long timeout, boolean recurring) throws IOException {
		return (StreamChannel) accept(channel, bufferSize, timeout, false, recurring);
	}

	@Override
	public StreamChannel acceptStreamChannel(int channel, int bufferSize,
			boolean recurring) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public StreamChannel acceptStreamChannel(int channel, int bufferSize,
			long timeout) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public StreamChannel acceptStreamChannel(int channel, int bufferSize)
			throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DatagramPacketChannel connectDatagramPacketChannel(int channel,
			int recvBufferSize, long timeout) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public DatagramPacketChannel connectDatagramPacketChannel(int channel,
			int bufferSize) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

	@Override
	public StreamChannel connectStreamChannel(int channel, int bufferSize)
			throws IOException {
		// TODO Auto-generated method stub
		return null;
	}

}

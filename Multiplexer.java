package com.github.ezekielnewren.net.multiplexer;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32;

public class Multiplexer implements ClientMultiplexer, ServerMultiplexer {

	static boolean DEBUG = false;
	static {
		assert(DEBUG=true);
	}
	
	// constants
	public static final long DEFAULT_ACCEPT_TIMEOUT = 0;
	public static final long DEFAULT_CONNECTION_TIMEOUT = 60000;
	public static final int MAX_BUFFER_SIZE = 0xffffff;
	public static final int MAX_PAYLOAD_SIZE = 0xffff;
	  
	static final int FLAG_NULL = 0x0;
	static final int FLAG_SYN = 0x1;
	static final int FLAG_CLI = 0x2;
	static final int FLAG_MSG = 0x4;
	static final int FLAG_RST = 0x8;
	static final int FLAG_KAL = 0x10;
	static final int FLAG_OCL = 0x20;
	static final int FLAG_ICL = 0x40;
	static final int FLAG_IOCL = FLAG_ICL|FLAG_OCL;
	static final int FLAG_MCL = 0x80;

	// Channel states
	private static int i = 0;
	static final long STATE_UNBOUND = (1<<i++);
	static final long STATE_BOUND = (1<<i++);
	static final long STATE_LISTENING = (1<<i++);
	static final long STATE_ACCEPTING = (1<<i++);
	static final long STATE_CONNECTING = (1<<i++);
	static final long STATE_ESTABLISHED = (1<<i++);
	static final long STATE_CHANNEL_IN_CLOSING_METHOD = (1<<i++);
//	static final long STATE_INPUT_CLOSED = (1<<i++);
//	static final long STATE_OUTPUT_CLOSED = (1<<i++);
	static final long STATE_CHANNEL_CLOSED = (1<<i++);

	static final long LINGERING_STATES = STATE_CONNECTING|STATE_ACCEPTING|STATE_LISTENING|STATE_CHANNEL_IN_CLOSING_METHOD;

	// Multiplexer states
	static final long STATE_OPEN = (1<<i++);
	static final long STATE_CLOSING = (1<<i++);
	static final long STATE_CLOSED = (1<<i++);
	
	// per instance constants
	private final Multiplexer home = this;
	private final Demultiplexer segregator;
	private final DataInputStream input;
	private final OutputStream output;
	private final byte[] recvBuffer = new byte[8+0xffff+4];
	private final byte[] sendBuffer = new byte[8+4+1];
	private final CRC32 sendCRC = new CRC32();
	private final BitSet channelBound = new BitSet();
	private final ChannelMetadata[] cmMeta = new ChannelMetadata[0x10000];
	private final Object mutex = new Object();

	private long state = STATE_OPEN;
	
	public Multiplexer(InputStream is, OutputStream os, int... prePasvOpen) throws IOException {
		input = (is instanceof DataInputStream)?(DataInputStream)is:new DataInputStream(is);
		output = os;
		
		for (int i=0; i<cmMeta.length; i++) {
			cmMeta[i] = new ChannelMetadata();
		}

		synchronized(mutex) {
			for (int i=0; i<prePasvOpen.length; i++) {
				listen(prePasvOpen[i]);
			}
		}
		
		segregator = new Demultiplexer();
	}

	void writePacket(int channel, int credit, byte[] b, int off, int len, int flags) throws IOException {
		// 2B_len, 2B_channel, 3B_credit, 1B_flags, ...B_payload, 4B_crc
		if (b!=null) {
			if ((off < 0) || (off > b.length) || (len < 0) ||
					((off + len) > b.length) || ((off + len) < 0) ||
					(len>0xffff)) {
				throw new IndexOutOfBoundsException();
			}
		} else {
			if ((off|len)!=0) throw new IllegalArgumentException("len and off must be 0 if b is null");
		}
		synchronized(mutex) {

			// header
			numberToBytes(len, sendBuffer, 0, 2);
			numberToBytes(channel, sendBuffer, 2, 2);
			numberToBytes(credit, sendBuffer, 4, 3);
			sendBuffer[7] = (byte) flags;

			// trailer
			sendCRC.reset();
			sendCRC.update(sendBuffer, 0, 8);			// header
			if (b!=null) sendCRC.update(b, off, len);	// payload
			numberToBytes(sendCRC.getValue(), sendBuffer, 8, 4);

			try {
				output.write(sendBuffer, 0, 8);			// send header
				if (b!=null) output.write(b, off, len);	// send payload
				output.write(sendBuffer, 8, 4);			// send trailer
				output.flush();
			} catch (IOException ioe) {
				closeQuietly();
				throw ioe;
			}
		}
	}

	void writePacketWithOneByte(int channel, int credit, int byteValue) throws IOException {
		synchronized(mutex) {
			sendBuffer[12] = (byte) byteValue;
			writePacket(channel, credit, sendBuffer, 12, 1, FLAG_NULL);
		}
	}

	void writePacket(int channel, int credit, int flags) throws IOException {
		writePacket(channel, credit, null, 0, 0, flags);
	}
	
	private void bind(int channel, boolean recurring) throws IOException {
		// check the arguments and state
		ChannelMetadata cmMeta = getCM(channel);

		assert(Thread.holdsLock(mutex));
		if (channelBound.get(channel)) throw new ChannelBindException();
		if (cmMeta.state != STATE_UNBOUND) throw new IllegalStateException();
		
		
		cmMeta.clear();
		channelBound.set(channel);
		cmMeta.setState(STATE_BOUND);
		if (recurring) {
			cmMeta.recurring = true;
			cmMeta.setState(STATE_LISTENING);
		}
	}
	
	private void unbind(int channel) throws IOException {
		ChannelMetadata cmMeta = getCM(channel);

		assert(Thread.holdsLock(mutex));
		if (cmMeta.state!=STATE_CHANNEL_CLOSED) throw new IllegalStateException();
		
		channelBound.clear(channel);
		if (cmMeta.recurring) {
			bind(channel, true);
		} else {
			cmMeta.clear();
		}
	}
	
	private Channel connect(int channel, int recvBufferSize, long timeout, boolean messageMode) throws IOException {
		ChannelMetadata cmMeta = getCM(channel);
		validBufferSize(recvBufferSize);
		if (timeout<0) throw new IllegalArgumentException("timeout cannot be negative");
		
		synchronized(mutex) {
			try {
				bind(channel, false);
				cmMeta.setState(STATE_CONNECTING);
				cmMeta.messageMode = messageMode;
				
				// send synchronize
				writePacket(channel, recvBufferSize, FLAG_SYN|FLAG_CLI|( messageMode?FLAG_MSG:FLAG_NULL ));
				
				// wait for response
				final AtomicLong timer = new AtomicLong();
				while (timer.get()<timeout && !cmMeta.nextPacketRead.compareAndSet(true, false)) linger(timeout, timer);
				
				// if timedout throw and error
				if (timer.get()>timeout&&timeout>0) {
					cmMeta.setState(STATE_CHANNEL_CLOSED);
					unbind(channel);
					throw new ChannelTimeoutException();
				}
				
				if (cmMeta.reset) {
					cmMeta.setState(STATE_CHANNEL_CLOSED);
					unbind(channel);
					throw new ChannelResetException();
				}
				
				if (messageMode) {
					cmMeta.ptr = new DatagramPacketChannel(this, channel, recvBufferSize, cmMeta.credit, mutex);
				} else {
					cmMeta.ptr = new StreamChannel(this, channel, recvBufferSize, cmMeta.credit, mutex);
				}
				cmMeta.setState(STATE_ESTABLISHED);
				
				assert(cmMeta.ptr!=null); return cmMeta.ptr;
			} finally {
				mutex.notifyAll();
				
				// if this is true something has gone wrong
				if (cmMeta.state!=STATE_ESTABLISHED) {
					cmMeta.setState(STATE_CHANNEL_CLOSED);
					unbind(channel);
				}
			}
		}
	}
	
	public void listen(int channel) throws IOException {
		ChannelMetadata cmMeta = getCM(channel);
		
		synchronized(mutex) {
			bind(channel, false);
			cmMeta.state = STATE_LISTENING;
		}
	}
	
	private Channel accept(int channel, int recvBufferSize, long timeout, boolean messageMode, boolean recurring) throws IOException {
		ChannelMetadata cmMeta = getCM(channel);
		validBufferSize(recvBufferSize);
		if (timeout<0) throw new IllegalArgumentException();
		
		synchronized(mutex) {
			try {
				if (cmMeta.state!=STATE_LISTENING) {
					bind(channel, recurring);
				}
				cmMeta.setState(STATE_ACCEPTING);
				cmMeta.messageMode = messageMode;
				
				final AtomicLong timer = new AtomicLong();
				while (!cmMeta.nextPacketRead.compareAndSet(true, false)) linger(timeout, timer);
				
				// if timedout throw and error
				if (timer.get()>timeout&&timeout>0) {
					cmMeta.setState(STATE_CHANNEL_CLOSED);
					unbind(channel);
					throw new ChannelTimeoutException();
				}
				
				if (cmMeta.reset) {
					cmMeta.setState(STATE_CHANNEL_CLOSED);
					unbind(channel);
					throw new ChannelResetException();
				}
				
				writePacket(channel, recvBufferSize, FLAG_SYN|( messageMode?FLAG_MSG:FLAG_NULL ));
				
				if (messageMode) {
					cmMeta.ptr = new DatagramPacketChannel(this, channel, recvBufferSize, cmMeta.credit, mutex);
				} else {
					cmMeta.ptr = new StreamChannel(this, channel, recvBufferSize, cmMeta.credit, mutex);
				}
				cmMeta.setState(STATE_ESTABLISHED);
				
				assert(cmMeta.ptr!=null); return cmMeta.ptr;
			} finally {
				mutex.notifyAll();
				
				// if this is true something has gone wrong
				if (cmMeta.state!=STATE_ESTABLISHED) {
					cmMeta.setState(STATE_CHANNEL_CLOSED);
					unbind(channel);
				}
			}
		}
	}
	
	class Demultiplexer implements Runnable {

		final Thread handle;
		
		public Demultiplexer() {
			handle = new Thread(this);
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
					// 2B_len, 2B_channel, 3B_credit, 1B_flags, ...B_payload, 4B_crc
					crc.reset();
					input.readFully(recvBuffer, 0, 8);
					
					// header fields
					int len = (int) bytesToNumber(recvBuffer, 0, 2);
					int channel = (int) bytesToNumber(recvBuffer, 2, 2);
					int credit = (int) bytesToNumber(recvBuffer, 4, 3);
					int flags = (int) bytesToNumber(recvBuffer, 7, 1);

					// payload+crc
					input.readFully(recvBuffer, 8, len+4);
					long packetCRC = bytesToNumber(recvBuffer, 8+len, 4);

					// verify header and payload with trailer
					crc.update(recvBuffer, 0, 8+len);
					long realCRC = crc.getValue();
					if (packetCRC!=realCRC) throw new IOException("Malformed packet");

					ChannelMetadata cmMeta = getCM(channel);
					
//					static final int FLAG_NULL = 0x0;
//					static final int FLAG_SYN = 0x1;
//					static final int FLAG_CLI = 0x2;
//					static final int FLAG_MSG = 0x4; // message mode
//					static final int FLAG_RST = 0x8;
//					static final int FLAG_KAL = 0x10;
//					static final int FLAG_OCL = 0x20;
//					static final int FLAG_ICL = 0x40;
//					static final int FLAG_IOCL = FLAG_ICL|FLAG_OCL;
//					static final int FLAG_MCL = 0x80;
					
					// process packet
					synchronized(mutex) {
						try {
							cmMeta.credit = credit;
							
							if (!channelBound.get(channel)) {
								sendReset(channel);
								continue;
							}
							
							switch (flags) {
							case FLAG_NULL:
								break;
							
							case FLAG_SYN:
							case FLAG_SYN|FLAG_CLI:
							case FLAG_SYN|FLAG_MSG:
							case FLAG_SYN|FLAG_CLI|FLAG_MSG:
								// user has not yet called accept
								if (cmMeta.state == STATE_LISTENING) {
									linger();
								}
								// near side type vs far side type
								if (cmMeta.messageMode != ((flags&FLAG_MSG)!=0) ) {
									resetChannel(channel);
									sendReset(channel);
								}
								// check for connect/accept mismatch
								if ( !((cmMeta.state==STATE_ACCEPTING&&(flags&FLAG_CLI)!=0) 
										|| (cmMeta.state==STATE_CONNECTING&&(flags&FLAG_CLI)==0)) ) {
									resetChannel(channel);
									sendReset(channel);
								}
								
								break;
							
							case FLAG_RST:
								resetChannel(channel);
								break;
								
							case FLAG_KAL:
								break;
								
							case FLAG_OCL:
								cmMeta.nextPacketRead.set(true);
								mutex.notifyAll();
								cmMeta.ptr.dealWithFarsideOutputClosing();
								break;
								
							case FLAG_ICL:
								cmMeta.nextPacketRead.set(true);
								mutex.notifyAll();
								cmMeta.ptr.dealWithFarsideInputClosing();
								break;
							
							case FLAG_IOCL:
								cmMeta.nextPacketRead.set(true);
								mutex.notifyAll();
								cmMeta.ptr.dealWithFarsideClosing();
								break;
								
							case FLAG_MCL:
								home.close();
								break;
								
							default:
								if (DEBUG) System.err.println("unknown flag combination");
								sendReset(channel);
								break;
							}
							
							if (cmMeta.state==STATE_ESTABLISHED) {
								cmMeta.ptr.feed(recvBuffer, 8, len);
								cmMeta.ptr.depositCredit(credit);
							}
							
							
							
							if ((cmMeta.state&LINGERING_STATES)!=0) {
								cmMeta.nextPacketRead.set(true);
								mutex.notifyAll();
								while ((cmMeta.state&LINGERING_STATES) != 0) linger();
							}
							
							if (cmMeta.state==STATE_CHANNEL_CLOSED) {
								unbind(channel);
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
			if (cmMeta.ptr!=null&&cmMeta.state!=STATE_CHANNEL_IN_CLOSING_METHOD) cmMeta.ptr.closeQuietly();
		}
		
		void sendReset(int channel) throws IOException {
			validChannel(channel);
			
			assert(Thread.holdsLock(mutex));
			writePacket(channel, 0, FLAG_RST);
		}
		
	}

	public boolean isClosed() {
		synchronized(mutex) {
			return state==STATE_CLOSED;
		}
	}

	private void closeQuietly() {
		synchronized(mutex) {
			state = STATE_CLOSED;
		}
		
		try {input.close();} catch (IOException e){}
		try {output.close();} catch (IOException e){}
	}
	
	public void close() throws IOException {
		synchronized(mutex) {
			//final AtomicLong timer = new AtomicLong();
			while (state==STATE_CLOSING) linger();
			
			if (state==STATE_CLOSED) return;
			state = STATE_CLOSING;
			try {

				
				
			} finally {
				mutex.notifyAll();
				state = STATE_CLOSED;
				
				IOException ioe = new IOException();
				try {input.close();} catch (IOException e){ioe.addSuppressed(e);}
				try {output.close();} catch (IOException e){ioe.addSuppressed(e);}
				if (ioe.getSuppressed().length>0) throw ioe;
			}
		}
	}
	
	class ChannelMetadata {
		final AtomicBoolean nextPacketRead = new AtomicBoolean();
		Channel ptr;
		long state = STATE_UNBOUND;
		boolean messageMode;
		boolean reset;
		boolean recurring;
		int credit;
		
		void setState(long newState) {
			state = newState;
			mutex.notifyAll();
		}
		
		void clear() {
			nextPacketRead.set(false);
			ptr = null;
			state = STATE_UNBOUND;
			messageMode = false;
			reset = false;
			recurring = false;
			credit = 0;
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
		if (recvBufferSize<1) throw new IllegalArgumentException("recvBufferSize must be at least 1");
		if (recvBufferSize>MAX_BUFFER_SIZE) throw new IllegalArgumentException("max recvBufferSize "+MAX_BUFFER_SIZE);
	}

	void linger(long waitForMillis, final AtomicLong timer) throws IOException {
		if ((waitForMillis|timer.get())<0) throw new IllegalArgumentException(); 
		if (timer.get()>waitForMillis) return;
		if (waitForMillis!=0) waitForMillis -= timer.get();
		if (isClosed()||!segregator.handle.isAlive()) throw new IOException("Multiplexer is closed or Demultiplexer has died");
		long beg = System.nanoTime();
		try {
			mutex.wait(waitForMillis);
		} catch (InterruptedException ie) {
			Thread.currentThread().interrupt();
		}
		timer.addAndGet((System.nanoTime()-beg)/1000000);
	}

	void linger() throws IOException {
		linger(0, new AtomicLong());
	}
	
	// acceptDatagramPacketChannel
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel,
			int bufferSize) throws IOException {
		return (DatagramPacketChannel) accept(channel, bufferSize, DEFAULT_ACCEPT_TIMEOUT, true, false);
	}
	
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel,
			int bufferSize, boolean recurring) throws IOException {
		return (DatagramPacketChannel) accept(channel, bufferSize, DEFAULT_ACCEPT_TIMEOUT, true, recurring);
	}
	
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel,
			int bufferSize, long timeout) throws IOException {
		return (DatagramPacketChannel) accept(channel, bufferSize, DEFAULT_ACCEPT_TIMEOUT, true, false);
	}
	
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel,
			int bufferSize, long timeout, boolean recurring) throws IOException {
		return (DatagramPacketChannel) accept(channel, bufferSize, timeout, true, recurring);
	}

	// acceptStreamChannel
	public StreamChannel acceptStreamChannel(int channel, int bufferSize)
			throws IOException {
		return (StreamChannel) accept(channel, bufferSize, DEFAULT_ACCEPT_TIMEOUT, false, false);
	}

	public StreamChannel acceptStreamChannel(int channel, int bufferSize,
			boolean recurring) throws IOException {
		return (StreamChannel) accept(channel, bufferSize, DEFAULT_ACCEPT_TIMEOUT, false, false);
	}

	public StreamChannel acceptStreamChannel(int channel, int bufferSize,
			long timeout) throws IOException {
		return (StreamChannel) accept(channel, bufferSize, timeout, false, false);
	}
	
	public StreamChannel acceptStreamChannel(int channel, int bufferSize,
			long timeout, boolean recurring) throws IOException {
		return (StreamChannel) accept(channel, bufferSize, timeout, false, recurring);
	}

	// connetDatagramChannel
	public DatagramPacketChannel connectDatagramPacketChannel(int channel,
			int bufferSize) throws IOException {
		return (DatagramPacketChannel) connect(channel, bufferSize, DEFAULT_CONNECTION_TIMEOUT, true);
	}
	
	public DatagramPacketChannel connectDatagramPacketChannel(int channel,
			int recvBufferSize, long timeout) throws IOException {
		return (DatagramPacketChannel) connect(channel, recvBufferSize, timeout, true);
	}

	// connectStreamChannel
	public StreamChannel connectStreamChannel(int channel, int bufferSize)
			throws IOException {
		return (StreamChannel) connect(channel, bufferSize, DEFAULT_CONNECTION_TIMEOUT, false);
	}
	
	public StreamChannel connectStreamChannel(int channel, int recvBufferSize, long timeout) throws IOException {
		return (StreamChannel) connect(channel, recvBufferSize, timeout, false);
	}

}

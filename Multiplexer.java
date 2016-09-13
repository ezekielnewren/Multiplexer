package com.github.ezekielnewren.net.multiplexer;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.BitSet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.CRC32;

public class Multiplexer implements ClientMultiplexer, ServerMultiplexer {

	private final Multiplexer home = this;
	private final DataInputStream input;
	private final DataOutputStream output;
	private final byte[] recvBuffer = new byte[8+0xffff+4];
	private final byte[] sendBuffer = new byte[8+4+1];
	private final CRC32 sendCRC = new CRC32();
	private final ChannelParameter[] cp = new ChannelParameter[0x10000];
	private final BitSet channelBound = new BitSet();
	
	private final AtomicBoolean remoteClosed = new AtomicBoolean();
	private final static int MULTIPLEXER_OPEN = 0;
	private final static int MULTIPLEXER_CLOSING = 1;
	private final static int MULTIPLEXER_CLOSED = 2;
	private volatile int closed = MULTIPLEXER_OPEN;

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

	public static final long DEFAULT_CONNECTION_TIMEOUT = 60000;
	public static final long DEFAULT_CLOSE_TIMEOUT = 3000;

	public Multiplexer(InputStream is, OutputStream os, int... prePasvOpen) throws IOException {
		//MuxDriver.log("constructor start");
		// initialize input and output
		input = (is instanceof DataInputStream)?(DataInputStream)is:new DataInputStream(is);
		output = (os instanceof DataOutputStream)?(DataOutputStream)os:new DataOutputStream(os);

		try {
			// initialize parameters of every channel
			for (int i=0; i<cp.length; i++) cp[i]=new ChannelParameter();
	
			// listen on specific channels beforehand
			for (int i=0; i<prePasvOpen.length; i++) {
				ChannelParameter.validChannel(prePasvOpen[i]);
				ChannelParameter cmParam = getCP(prePasvOpen[i]);
				listen(prePasvOpen[i], false);
				cmParam.state = STATE_PRE_PASV_OPEN;
			}
	
			// start the demultiplexer to switch incoming packets
			segregator = new Demultiplexer(this);
			//MuxDriver.log("constructor end");
		} catch (RuntimeException re) {
			input.close();
			output.close();
			throw re;
		}
	}

	// TODO note: private helper functions

	/**
	 * Turns a byte array into a long. The byte array must be in network byte order with
	 * num[0] being the most significant and num[len-1] being the least significant.
	 * @param num the byte array holding the number to be converted.
	 * @param off starting byte
	 * @param len defines the size of the scalar to be converted. Valid range is [1-8] inclusive.
	 * @return
	 */
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

	/**
	 * Turns an integer of any length into a byte array of network byte order. 
	 * @param num the integer to be converted. Even though the parameter defines a long 
	 * smaller scalars types can passed into the function as well.
	 * @param b the byte array holding the result
	 * @param off starting byte
	 * @param len defines the size of the scalar to be converted. Valid range is [1-8] inclusive.
	 */
	private static void numberToBytes(long num, byte[] b, int off, int len) {
		if (off+len > b.length) throw new IndexOutOfBoundsException("byte array len="+b.length+" off="+off+" len="+len);
		if (len < 1 || len > 8) throw new IndexOutOfBoundsException("Improper length "+len);
		for (int i=0; i<len; i++) {
			b[off+i] = (byte) ((num>>>(len-1-i)*8)&0xFF);
		}
	}
	
	void writePacket(int channel, int processed, byte[] b, int off, int len, int flags) throws IOException {
		ChannelParameter.validChannel(channel);
		if (processed!=0) ChannelParameter.validBufferSize(processed);
		if (b!=null) {
			if ((off < 0) || (off > b.length) || (len < 0) ||
					((off + len) > b.length) || ((off + len) < 0) ||
					(len>0xffff)) {
				throw new IndexOutOfBoundsException();
			}
		} else {
			if ((off|len)!=0) throw new IllegalArgumentException("len and off must be 0 if b is null");
		}
		if (flags>0xff) throw new IllegalArgumentException();
		if (isClosed()) throw new IOException("Multiplexer Closed");
		synchronized(mutex) {
			// 2B_len, 2B_channel, 3B_proc, 1B_flags, ...B_payload, 4B_crc

			// header
			numberToBytes(len, sendBuffer, 0, 2);
			numberToBytes(channel, sendBuffer, 2, 2);
			numberToBytes(processed, sendBuffer, 4, 3);
			sendBuffer[7] = (byte) flags;

			// trailer
			sendCRC.reset();
			sendCRC.update(sendBuffer, 0, 8);
			if (b!=null) sendCRC.update(b, off, len);
			numberToBytes(sendCRC.getValue(), sendBuffer, 8, 4);

			try {
				output.write(sendBuffer, 0, 8);
				if (b!=null) output.write(b, 0, len);
				output.write(sendBuffer, 8, 4);
			} catch (IOException ioe) {
				//MuxDriver.log("failed to write a packet");
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

	/**
	 * Resets everything to defaults and creates a new Channel Object.
	 * @param channel
	 * @throws IOException
	 */
	void bind(int channel, boolean recurring) throws IOException {
		synchronized(mutex) {
			ChannelParameter cmParam = getCP(channel);
			
			if (isBound(channel)) throw new ChannelBindException("Channel in use");
			cmParam.recurring = recurring;
			channelBound.set(channel);
			cmParam.state = STATE_BOUND;
		}
	}

	boolean isBound(int channel) {
		synchronized(mutex) {
			return channelBound.get(channel);
		}
	}

	void unbind(int channel) throws IOException {
		ChannelParameter.validChannel(channel);
		synchronized(mutex) {
			ChannelParameter cmParam = getCP(channel);
			
			assert((cmParam.state&(STATE_ACCEPTING|STATE_CONNECTING|STATE_CHANNEL_CLOSED))!=0);
			
			cmParam.channel = null;
			cmParam.reset = false;
			channelBound.clear(channel);
			cmParam.state = STATE_UNBOUND;
			if (cmParam.recurring) listen(channel, true);
		}
	}
	
	/**
	 * sets the listening flag to true and recurring 
	 * @param channel
	 * @param recurring
	 * @throws IOException
	 */
	void listen(int channel, boolean recurring) throws IOException {
		//ChannelParameter.validChannel(channel);
		synchronized(mutex) {
			ChannelParameter cmParam = getCP(channel);
			
			if ((cmParam.state&(STATE_ACCEPTING|STATE_ESTABLISHED))!=0) throw new ChannelListenException("Cannot listen on the same channel twice");
			if (cmParam.state!=STATE_PRE_PASV_OPEN) bind(channel, recurring);
			cmParam.state = STATE_LISTENING;
		}
	}
	
	// TODO note: public access functions
	
	public Channel connect(int channel, int bufferSize) throws IOException {return connect(channel, bufferSize, DEFAULT_CONNECTION_TIMEOUT);}
	public Channel connect(int channel, int bufferSize, long timeout) throws IOException {
		ChannelParameter.valid(channel, bufferSize);
		if (timeout<0) throw new IllegalArgumentException(timeout+"");
		if (isClosed()) throw new IOException("Multiplexer Closed");
		synchronized(mutex) {
			// get parameters for channel
			//MuxDriver.log("connecting");
			
			ChannelParameter cmParam = getCP(channel);
			
			// bind
			bind(channel, false);
			cmParam.state = STATE_CONNECTING;
			
			// request connection and wait for the response
			writePacket(channel, bufferSize, FLAG_SYN|FLAG_CLI);
			if (await(cmParam.signal, timeout)>timeout) {
				//cmParam.state = STATE_CHANNEL_CLOSED; // legal because the channel doesn't exist yet
				unbind(channel);
				throw new ChannelTimeoutException("Channel timed out");
			}
			
			try {
				// act on data provided from the Demultiplexer
				if (cmParam.reset) {
					//cmParam.state = STATE_CHANNEL_CLOSED; // legal because the channel doesn't exist yet
					unbind(channel);
					throw new ChannelResetException("Connection refused");
				}
				Channel cm = (cmParam.channel=new Channel(home, channel, bufferSize, cmParam.send));
				cmParam.state = STATE_ESTABLISHED;
				//MuxDriver.log("connected");
				return cm;
			} finally {
				// tell the Demultiplexer that he can resume packet-switching
				signal(segregator.signal);
				
				
			}
		}
	}

	
	public Channel accept(int c, int b) throws IOException {return accept(c, b, DEFAULT_CONNECTION_TIMEOUT, false);}
	public Channel accept(int c, int b, boolean r) throws IOException {return accept(c, b, DEFAULT_CONNECTION_TIMEOUT, r);}
	public Channel accept(int c, int b, long t) throws IOException {return accept(c, b, t, false);}
	public Channel accept(int channel, int bufferSize, long timeout, boolean recurring) throws IOException {
		//MuxDriver.log("accept called");
		ChannelParameter.valid(channel, bufferSize);
		if (timeout<0) throw new IllegalArgumentException(timeout+"");
		if (isClosed()) throw new IOException("Multiplexer Closed");
		synchronized(mutex) {
			// get parameters for channel
			//MuxDriver.log("accepting");
			
			ChannelParameter cmParam = getCP(channel);
			assert(cmParam!=null);
			
			// bind
			listen(channel, recurring);
			cmParam.state = STATE_ACCEPTING;
			
			
			if (cmParam.send==0&&await(cmParam.signal, DEFAULT_CONNECTION_TIMEOUT)>timeout) {
				//MuxDriver.log("timeout");
				//cmParam.state = STATE_CHANNEL_CLOSED; // legal because the channel doesn't exist yet
				unbind(channel);
				throw new ChannelTimeoutException("Time limit reached");
			}
			
			try {
				if (cmParam.reset) {
					//cmParam.state = STATE_CHANNEL_CLOSED; // legal because the channel doesn't exist yet
					unbind(channel);
					throw new ChannelResetException("Channel reset");
				}
				Channel cm = (cmParam.channel=new Channel(home, channel, bufferSize, cmParam.send));
				
				writePacket(channel, bufferSize, FLAG_SYN);
				cmParam.state = STATE_ESTABLISHED;
				//MuxDriver.log("accepted");
				return cm;
			} finally {
				signal(segregator.signal);
			}
			
		}
		
	}
	
	
	public boolean isClosed() {
		return closed==MULTIPLEXER_CLOSED;
	}
	
	// TODO close
	@Override
	public void close() throws IOException {close(DEFAULT_CLOSE_TIMEOUT);}
	public void close(long timeout) throws IOException {
		if (timeout<0) throw new IllegalArgumentException("timeout cannot be negative");
		timeout = timeout==0?Long.MAX_VALUE:timeout;
		synchronized(mutex) {
			while (closed==MULTIPLEXER_CLOSING) {
				//MuxDriver.log("Another thread is closing going to wait");
				try{mutex.wait(100);}catch(InterruptedException e){Thread.currentThread().interrupt();}
			}
			try {
				if (closed==MULTIPLEXER_CLOSED) return;
				closed = MULTIPLEXER_CLOSING;
				
				//MuxDriver.log("closing");
				
				IOException ioe = null;
				try {
					writePacket(0, 0, FLAG_MCL);
					//MuxDriver.log("waiting for close reply");
					await(remoteClosed, timeout);
				} catch(IOException e) {
					ioe=e;
				}
				
				try{Thread.sleep(50);}catch(InterruptedException ie){Thread.currentThread().interrupt();}
				
				try{input.close();}catch(IOException e){if(ioe==null)ioe=e;else ioe.addSuppressed(e);}
				try{output.close();}catch(IOException e){if(ioe==null)ioe=e;else ioe.addSuppressed(e);}
				if (ioe!=null) throw ioe;
			} finally {
				//MuxDriver.log("Multiplexer Closed");
				closed = MULTIPLEXER_CLOSED;
				mutex.notifyAll();
			}
		}
	}
	
	/**
	 * This function is called if something goes wrong with the underlying
	 * Input and Output streams.
	 */
	private void closeQuietly() {
		synchronized(mutex) {
			try {
				if (closed==MULTIPLEXER_CLOSED) return;
				closed = MULTIPLEXER_CLOSING;
				
				try{input.close();}catch(IOException e){}
				try{output.close();}catch(IOException e){}
			} finally {
				closed = MULTIPLEXER_CLOSED;
				remoteClosed.set(true);
				mutex.notifyAll();
			}
		}
	}
	
	// TODO note: packet switching class
	class Demultiplexer implements Runnable {

		final Multiplexer home;
		final AtomicBoolean signal;
		final Thread handle;
		
		public Demultiplexer(Multiplexer parent) {
			this.home = parent;
			signal = new AtomicBoolean();
			handle = new Thread(this);
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
						ChannelParameter cmParam = getCP(channel);
						Channel cm = cmParam.channel;

						if ((flags&FLAG_MCL)!=0) {
							signal(remoteClosed);
							home.close(DEFAULT_CLOSE_TIMEOUT);
							break;
						}
						
						if (!isBound(channel)) {
							writePacket(channel, 0, FLAG_RST);
							continue;
						}

						switch(flags) {
						case 0: break;
						case FLAG_SYN|FLAG_CLI:
						case FLAG_SYN:
//							if ( ((flags&FLAG_CLI)!=0&&(cmParam.state&STATE_CONNECTING)!=0) ||
//									((flags&FLAG_CLI)==0&&(cmParam.state&STATE_ACCEPTING)!=0) ) {
//								writePacket(channel, 0, FLAG_RST);
//								continue;
//							}
//							cmParam.send = proc;
//							if ((cmParam.state&(STATE_CONNECTING|STATE_ACCEPTING))!=0) {
//								signal(cmParam.signal);
//								await(this.signal);
//							}
							continue;
							
						case FLAG_RST:
//							cmParam.reset = true;
//							if ((cmParam.state&(STATE_CONNECTING|STATE_ACCEPTING))!=0) {
//								signal(cmParam.signal);
//								await(this.signal);
//							} else if ((cmParam.state&(STATE_ESTABLISHED))!=0) {
//								cm.closeQuietly();
//							}
							continue;
						case FLAG_OCL:
//							cm.input.setEOF();
//							cm.fieldLock.notifyAll();
//							if (cm.localInputClosed) 
							break;
						case FLAG_ICL:
							cm.output.close();
						case (FLAG_ICL|FLAG_OCL):
//							cm.remoteOutputClosed = true;
//							cm.close(false);
//							signal(cmParam.signal);
//							cm.fieldLock.notifyAll();
							break;
						case FLAG_KAL:

							break;
						default:
							assert(false);
							break;
						}
						if (len>0) {
							try{cm.input.appendData(recvBuffer, 8, len);}catch(IOException e){assert(false);}
						}
						if (proc>0) {
							cm.output.incWriteable(proc);
						}
					}
				}
			} catch (IOException ioe) {
				closeQuietly();
			}
		}
	}

	
	// TODO note: flowchar/fsm to communicate the state of a channel between threads.
	private static int i = 0;
	static final long STATE_UNBOUND = (1<<i++);
	static final long STATE_BOUND = (1<<i++);
	static final long STATE_LISTENING = (1<<i++);
	static final long STATE_PRE_PASV_OPEN = (1<<i++);
	static final long STATE_ACCEPTING = (1<<i++);
	static final long STATE_CONNECTING = (1<<i++);
	static final long STATE_ESTABLISHED = (1<<i++);
	static final long STATE_INPUT_CLOSED = (1<<i++);
	static final long STATE_OUTPUT_CLOSED = (1<<i++);
	static final long STATE_CHANNEL_IN_CLOSING_FUNCTION = (1<<i++);
	static final long STATE_CHANNEL_CLOSED = STATE_INPUT_CLOSED|STATE_OUTPUT_CLOSED;

	ChannelParameter getCP(int channel) {
		return cp[channel];
	}
	
	static class ChannelParameter {

		final AtomicBoolean signal = new AtomicBoolean();
		Channel channel;
		boolean recurring;

		// error
		boolean reset;

		long state = STATE_UNBOUND;
		int send;
		
		static void validChannel(int channel) {
			if (!(0<=channel&&channel<=0xffff)) throw new IllegalArgumentException();
		}

		static void validBufferSize(int recvBufferSize) {
			if (!(0<recvBufferSize&&recvBufferSize<=0xffffff)) throw new IllegalArgumentException("bufferSize must be between 1 and 16777215 inclusive");
		}

		static void valid(int channel, int recvBufferSize) {
			validChannel(channel);
			validBufferSize(recvBufferSize);
		}

	}

	void signal(AtomicBoolean ab) {
		ab.set(true);
		mutex.notifyAll();
	}
	
	long await(AtomicBoolean ab, long millis) {
		millis = (millis==0?millis=Long.MAX_VALUE:millis);
		long beg=System.nanoTime(),time;
		while ((time=(System.nanoTime()-beg)/1000000)<millis&&!ab.compareAndSet(true, false)) {
			try{mutex.wait(millis);}catch(InterruptedException e){Thread.currentThread().interrupt();}
		}
		return time;
	}
	
//	long await(AtomicBoolean ab) {
//		return await(ab, Long.MAX_VALUE);
//	}
}

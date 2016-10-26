package com.github.ezekielnewren.net.multiplexer;

import java.io.Closeable;
import java.io.IOException;

abstract class Channel implements Closeable {

	
	final Multiplexer home;
	final Channel parent = this;
	final Object mutex;
	private final int channel;
	private final int recvBufferSize;
	private final int sendBufferSize;
	private final Multiplexer.ChannelMetadata cmMeta;
	
	final ByteArrayCircularBuffer window;
	
	boolean localInputClosed = false;
	boolean localOutputClosed = false;
	boolean remoteOutputClosed = false;

	private long state;
	private int read = 0;
	private int written = 0;
	
	Channel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize, final Object mutex) {
		this.home = inst;
		this.channel = channel;
		this.mutex = mutex;
		this.recvBufferSize = recvBufferSize;
		this.sendBufferSize = sendBufferSize;
		window = new ByteArrayCircularBuffer(recvBufferSize);
		state = Multiplexer.STATE_ESTABLISHED;
		cmMeta = home.getCM(channel);
	}

	// private helper methods
	private int clearRead() {
		assert(Thread.holdsLock(mutex));
		
		int x = read;
		read = 0;
		return x;
	}
	
	private void depositCredit(int amount) {
		assert(Thread.holdsLock(mutex));
		
		written -= amount;
	}
	
	private void withdrawCredit(int amount) {
		assert(Thread.holdsLock(mutex));
		
		written += amount;
	}

	// demultiplexer shared methods
	void feed(byte[] b, int off, int len) throws IOException {
		assert(Thread.holdsLock(mutex));
		try {
			window.write(b, off, len);
			depositCredit(len);
		} finally {
			mutex.notifyAll();
		}
	}
	
	void dealWithFarsideInputClosing() {
		assert(Thread.holdsLock(mutex));
		try {
			close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	void dealWithFarsideOutputClosing() {
		assert(Thread.holdsLock(mutex));
		try {
			close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	void dealWithFarsideClosing() {
		assert(Thread.holdsLock(mutex));
		try {
			close();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}
	
	// subclasses of Channel helpher methods
	void writePacketWithOneByte(int b) throws IOException {
		assert(Thread.holdsLock(mutex));
		
		home.writePacketWithOneByte(channel, clearRead(), b);
		withdrawCredit(1);
	}
	
	void writePacket(byte[] b, int off, int len) throws IOException {
		assert(Thread.holdsLock(mutex));
		
		home.writePacket(channel, clearRead(), b, off, len, Multiplexer.FLAG_NULL);
		withdrawCredit(len);
	}
	
	void flushRead() throws IOException {
		assert(Thread.holdsLock(mutex));
		
		if (read>=window.getBufferSize()/2) {
			home.writePacket(channel, clearRead(), Multiplexer.FLAG_NULL);
		}
	}
	
	int getCredit() {
		assert(Thread.holdsLock(mutex));
		
		return sendBufferSize-written;
	}
	
	void incRead(int amount) {
		assert(Thread.holdsLock(mutex));
		
		read += amount;
	}

	long getState() {
		return state;
	}
	
	void setState(long newState) {
		assert(Thread.holdsLock(mutex));
		if (state==Multiplexer.STATE_CHANNEL_CLOSED) return;

		cmMeta.state = newState;
		state = newState;
	}

	void closeQuietly() {
		synchronized(mutex) {
			localInputClosed = true;
			localOutputClosed = true;
			remoteOutputClosed = true;
			setState(Multiplexer.STATE_CHANNEL_CLOSED);
		}
	}

	
	
	
	
	// TODO public methods
	public int getChannelID() {
		return channel;
	}
	
	public boolean isConnectionClosed() {
		synchronized(mutex) {
			return localOutputClosed&&remoteOutputClosed;
		}
	}
	
	public boolean isClosed() {
		synchronized(mutex) {
			return localInputClosed&&localOutputClosed&&remoteOutputClosed;
		}
	}
	
	public int getSendBufferSize() {
		return sendBufferSize;
	}
	
	public int getReceiveBufferSize() {
		return recvBufferSize;
	}
	
	public void closeInput() throws IOException {
		window.closeInput();
		close();
	}
	
	public void closeOutput() throws IOException {
		close();
	}
	
	@Override
	public void close() throws IOException {
		synchronized(mutex) {
			try {
				while (state==Multiplexer.STATE_CHANNEL_IN_CLOSING_METHOD) home.linger();
				
				if (isClosed()) return;
				setState(Multiplexer.STATE_CHANNEL_IN_CLOSING_METHOD);
				
				localInputClosed = true;
				localOutputClosed = true;
				
				home.writePacket(channel, clearRead(), Multiplexer.FLAG_IOCL);
				while (!cmMeta.nextPacketRead.compareAndSet(true, false)) home.linger();
				
				remoteOutputClosed = true;
				
				window.closeOutput();
				
				setState(Multiplexer.STATE_CHANNEL_CLOSED);
			} finally {
				mutex.notifyAll();
			}
		}
	}
	
	
	
	
	
	
	
	
}








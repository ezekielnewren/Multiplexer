package com.github.ezekielnewren.net.multiplexer;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

abstract class Channel implements Closeable {

	long state;
	
	final Multiplexer home;
	final Channel parent = this;
	final int channel;
	final Object mutex;
	final int recvBufferSize;
	final int sendBufferSize;
	final Multiplexer.ChannelMetadata cmMeta;
	
	final ByteArrayCircularBuffer window;
	
	boolean localInputClosed = false;
	boolean localOutputClosed = false;
	boolean remoteOutputClosed = false;

	int written = 0;
	int credit = 0;
	
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
			return state==Multiplexer.STATE_CHANNEL_CLOSED;
			//return localInputClosed&&localOutputClosed&&remoteOutputClosed;
		}
	}
	
	int getCredit() {
		assert(Thread.holdsLock(mutex));
		
		return sendBufferSize-written;
	}
	
	void withdrawCredit(int amount) {
		assert(Thread.holdsLock(mutex));
		
		if (amount<0) throw new IllegalArgumentException();
		if (written+amount>sendBufferSize) throw new IllegalArgumentException();
		written += amount;
	}
	
	void depositCredit(int amount) {
		assert(Thread.holdsLock(mutex));
		
		written -= amount;
	}
	
	void incProcessed(int amount) {
		assert(Thread.holdsLock(mutex));
		
		credit += amount;
	}
	
	int clearProcessed() {
		assert(Thread.holdsLock(mutex));
		
		int x = credit;
		credit = 0;
		return x;
	}
	
	void feed(byte[] b, int off, int len) throws IOException {
		assert(Thread.holdsLock(mutex));
		
		window.write(b, off, len);
	}
	
	void writePacket(byte[] b, int off, int len) throws IOException {
		assert(Thread.holdsLock(mutex));
		
		home.writePacket(channel, clearProcessed(), b, off, len, Multiplexer.FLAG_NULL);
	}
	
	void setState(long newState) {
		assert(Thread.holdsLock(mutex));
		
		state = newState;
		if (newState!=Multiplexer.STATE_CHANNEL_CLOSED) cmMeta.state = newState;
	}

	public void closeInput() throws IOException {
		
	}
	
	void closeInputQuietly() {
		
	}
	
	public void closeOutput() throws IOException {
		
	}
	
	void closeOutputQuietly() {
		
	}
	
	@Override
	public void close() throws IOException {
		synchronized(mutex) {
			try {
				while (state==Multiplexer.STATE_CHANNEL_CLOSING) home.linger();
				
				if (state==Multiplexer.STATE_CHANNEL_CLOSED) return;
				state = Multiplexer.STATE_CHANNEL_CLOSING;
				
				
				
				
				
				
				state = Multiplexer.STATE_CHANNEL_CLOSED;
			} finally {
				mutex.notifyAll();
			}
		}
	}

	void closeQuietly() {
		localInputClosed = true;
		localOutputClosed = true;
		remoteOutputClosed = true;
		synchronized(mutex) {
			setState(Multiplexer.STATE_CHANNEL_CLOSED);
		}
	}

	void dealWithFarsideInputClosing() {
		localOutputClosed = true;
	}

	void dealWithFarsideOutputClosing() {
		remoteOutputClosed = true;
	}
	
	void dealWithFarsideClosing() {
		localOutputClosed = true;
		remoteOutputClosed = true;
	}
	
}








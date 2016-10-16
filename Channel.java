package com.github.ezekielnewren.net.multiplexer;

import java.io.Closeable;
import java.io.IOException;

abstract class Channel implements Closeable {

	private long state;
	
	final Multiplexer home;
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
	int processed = 0;
	
	Channel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize) {
		this.home = inst;
		this.channel = channel;
		mutex = home.mutex;
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
	
	protected void incWritten(int amount) {
		assert(Thread.holdsLock(mutex));
		
		if (amount<0) throw new IllegalArgumentException();
		if (written+amount>sendBufferSize) throw new IllegalArgumentException();
		written += amount;
	}
	
	void decWritten(int amount) {
		assert(Thread.holdsLock(mutex));
		
		written -= amount;
	}
	
	protected void incProcessed(int amount) {
		assert(Thread.holdsLock(mutex));
		
		processed += amount;
	}
	
	private int clearProcessed() {
		assert(Thread.holdsLock(mutex));
		
		int x = processed;
		processed = 0;
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
	
	void setState(int newState) {
		assert(Thread.holdsLock(mutex));
		
		state = newState;
		if (newState!=Multiplexer.STATE_CHANNEL_CLOSED) cmMeta.state = newState;
	}
	
	@Override
	public void close() {
		
	}
	
}








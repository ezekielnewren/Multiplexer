package com.github.ezekielnewren.net.multiplexer;

import java.io.Closeable;
import java.io.IOException;

class Channel implements Closeable {

	final Multiplexer home;
	final int channel;
	final Object mutex;
	final int recvBufferSize;
	final int sendBufferSize;
	
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
	
	protected void incWritten(int amount) {
		synchronized(mutex) {
			if (amount<0) throw new IllegalArgumentException();
			if (written+amount>sendBufferSize) throw new IllegalArgumentException();
			written += amount;
		}
	}
	
	void decWritten(int amount) {
		synchronized(mutex) {
			written -= amount;
		}
	}
	
	protected void incProcessed(int amount) {
		synchronized(mutex) {
			processed += amount;
		}
	}
	
	private int clearProcessed() {
		synchronized(mutex) {
			int x = processed;
			processed = 0;
			return x;
		}
	}
	
	void feed(byte[] b, int off, int len) throws IOException {
		window.write(b, off, len);
	}
	
	void writePacket(byte[] b, int off, int len) throws IOException {
		home.writePacket(channel, clearProcessed(), b, off, len, Multiplexer.FLAG_NULL);
	}
	
	
	@Override
	public void close() {
		
	}
	
}








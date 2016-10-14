package com.github.ezekielnewren.net.multiplexer;

import java.io.Closeable;

class Channel implements Closeable {

	final Multiplexer home;
	final int channel;
	final Object mutex;

	boolean localInputClosed = false;
	boolean localOutputClosed = false;
	boolean remoteOutputClosed = false;

	Channel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize) {
		this.home = inst;
		this.channel = channel;
		mutex = home.mutex;
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
	
	@Override
	public void close() {
		
	}
	
}








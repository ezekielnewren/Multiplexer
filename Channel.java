package com.github.ezekielnewren.net.multiplexer;

import java.io.Closeable;

public class Channel implements Closeable {

	private final Channel inst = this;
	
	final Multiplexer home;
	final int channel;
	final Object fieldLock;
	final ChannelInputStream input;
	final ChannelOutputStream output;

	boolean localInputClosed = false;
	boolean localOutputClosed = false;
	boolean remoteInputClosed = false;
	boolean remoteOutputClosed = false;

	public Channel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize) {
		this.home = inst;
		this.channel = channel;
		fieldLock = home.mutex;
		input = new ChannelInputStream(recvBufferSize);
		output = new ChannelOutputStream(sendBufferSize);
	}

	class ChannelInputStream {
		ChannelInputStream(int recvBufferSize) {
			
		}
	}

	class ChannelOutputStream {
		ChannelOutputStream(int sendBufferSize) {
			
		}
	}
	
	@Override
	public void close() {
		
	}
	
}








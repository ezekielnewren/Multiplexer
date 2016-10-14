package com.github.ezekielnewren.net.multiplexer;

public class StreamChannel extends Channel {

	final ChannelInputStream input;
	final ChannelOutputStream output;
	
	StreamChannel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize) {
		super(inst, channel, recvBufferSize, sendBufferSize);
		input = new ChannelInputStream(recvBufferSize);
		output = new ChannelOutputStream(sendBufferSize);
	}

	class ChannelInputStream {
		final ByteArrayCircularBuffer window;
		
		ChannelInputStream(int recvBufferSize) {
			window = new ByteArrayCircularBuffer(recvBufferSize);
		}
		
	}

	class ChannelOutputStream {
		
		private final int sendBufferSize;
		private int writeable;
		
		ChannelOutputStream(int sendBufferSize) {
			this.sendBufferSize = writeable = sendBufferSize;
		}
	}
	
}

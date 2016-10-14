package com.github.ezekielnewren.net.multiplexer;

public class DatagramPacketChannel extends Channel {

	DatagramPacketChannel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize) {
		super(inst, channel, recvBufferSize, sendBufferSize);
	}

	
	
}

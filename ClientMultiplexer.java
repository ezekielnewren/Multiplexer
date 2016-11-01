package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;

public interface ClientMultiplexer extends CoreMultiplexer {

	public DatagramPacketChannel connectDatagramPacketChannel(int channel, int recvBufferSize, long timeout) throws IOException;
	public DatagramPacketChannel connectDatagramPacketChannel(int channel, int bufferSize) throws IOException;
	
	public StreamChannel connectStreamChannel(int channel, int recvBufferSize, long timeout) throws IOException;
	public StreamChannel connectStreamChannel(int channel, int bufferSize) throws IOException;
	
}

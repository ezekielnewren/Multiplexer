package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;

public interface ClientMultiplexer extends CoreMultiplexer {

	public Channel connectDatagramPacketChannel(int channel, int recvBufferSize, long timeout) throws IOException;
	public Channel connectDatagramPacketChannel(int channel, int bufferSize) throws IOException;
	
	public Channel connectStreamChannel(int channel, int recvBufferSize, long timeout) throws IOException;
	public Channel connectStreamChannel(int channel, int bufferSize) throws IOException;
	
}

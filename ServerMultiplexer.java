package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;

public interface ServerMultiplexer extends CoreMultiplexer {
	
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel, int bufferSize, long timeout, boolean recurring) throws IOException;
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel, int bufferSize, boolean recurring) throws IOException;
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel, int bufferSize, long timeout) throws IOException;
	public DatagramPacketChannel acceptDatagramPacketChannel(int channel, int bufferSize) throws IOException;
	
	public StreamChannel acceptStreamChannel(int channel, int bufferSize, long timeout, boolean recurring) throws IOException;
	public StreamChannel acceptStreamChannel(int channel, int bufferSize, boolean recurring) throws IOException;
	public StreamChannel acceptStreamChannel(int channel, int bufferSize, long timeout) throws IOException;
	public StreamChannel acceptStreamChannel(int channel, int bufferSize) throws IOException;
	
}

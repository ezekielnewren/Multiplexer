package net.multiplexer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ServerMultiplexer {
	
	private final Multiplexer m;
	
	public ServerMultiplexer(InputStream is, OutputStream os, int... pre) throws IOException {
		m = new Multiplexer(is, os, pre);
	}
	
	public Channel accept(int channel, int recvBufferSize, int timeout, boolean recurring) throws IOException {
		return m.accept(channel, recvBufferSize, timeout, true);
	}
	
	public Channel accept(int channel, int bufferSize, int timeout) throws IOException {
		return m.accept(channel, bufferSize, timeout);
	}
	
	public Channel accept(int channel, int bufferSize) throws IOException {
		return m.accept(channel, bufferSize);
	}
	
	public boolean isClosed() {
		return m.isClosed();
	}
	
	public void close() throws IOException {
		m.close();
	}
}

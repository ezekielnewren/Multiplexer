package net.multiplexer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ClientMultiplexer {

	private final Multiplexer m;
	
	public ClientMultiplexer(InputStream is, OutputStream os, int... pre) throws IOException {
		m = new Multiplexer(is, os, pre);
	}
	
	public Channel connect(int channel, int recvBufferSize, int timeout) throws IOException {
		return m.connect(channel, recvBufferSize, timeout);
	}
	
	public Channel connect(int channel, int bufferSize) throws IOException {
		return m.connect(channel, bufferSize);
	}
	
	public boolean isClosed() {
		return m.isClosed();
	}
	
	public void close() throws IOException {
		m.close();
	}
}

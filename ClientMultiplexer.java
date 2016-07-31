package net.multiplexer;

import java.io.Closeable;
import java.io.IOException;

public interface ClientMultiplexer extends Closeable {

	public Channel connect(int channel, int recvBufferSize, long timeout) throws IOException;
	public Channel connect(int channel, int bufferSize) throws IOException;
	
	public boolean isClosed();
	
}

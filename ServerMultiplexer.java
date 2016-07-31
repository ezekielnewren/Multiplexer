package net.multiplexer;

import java.io.Closeable;
import java.io.IOException;

public interface ServerMultiplexer extends Closeable {
	
	public Channel accept(int channel, int bufferSize, long timeout, boolean recurring) throws IOException;
	public Channel accept(int channel, int bufferSize, boolean recurring) throws IOException;
	public Channel accept(int channel, int bufferSize, long timeout) throws IOException;
	public Channel accept(int channel, int bufferSize) throws IOException;
	
	public boolean isClosed();
	
}

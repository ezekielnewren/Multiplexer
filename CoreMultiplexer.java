package net.multiplexer;

import java.io.Closeable;

interface CoreMultiplexer extends Closeable {
	
	public boolean isClosed();
	
}

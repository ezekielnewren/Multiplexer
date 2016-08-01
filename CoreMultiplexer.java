package com.github.ezekielnewren.net.multiplexer;

import java.io.Closeable;

interface CoreMultiplexer extends Closeable {
	
	public boolean isClosed();
	
}

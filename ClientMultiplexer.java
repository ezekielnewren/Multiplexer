package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;

public interface ClientMultiplexer extends CoreMultiplexer {

	public Channel connect(int channel, int bufferSize, long timeout) throws IOException;
	public Channel connect(int channel, int bufferSize) throws IOException;
	
}

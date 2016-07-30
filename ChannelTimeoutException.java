package net.multiplexer;

import java.io.IOException;

public class ChannelTimeoutException extends IOException {

	private static final long serialVersionUID = 1L;

	public ChannelTimeoutException() {
		super();
	}
	
	public ChannelTimeoutException(String message) {
		super(message);
	}
	
}

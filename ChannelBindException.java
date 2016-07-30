package net.multiplexer;

import java.io.IOException;

public class ChannelBindException extends IOException {

	private static final long serialVersionUID = 1L;

	public ChannelBindException() {
		super();
	}
	
	public ChannelBindException(String message) {
		super(message);
	}
	
}

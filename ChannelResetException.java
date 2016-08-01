package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;

public class ChannelResetException extends IOException {
	private static final long serialVersionUID = 1L;

	public ChannelResetException() {
		super();
	}
	
	public ChannelResetException(String message) {
		super(message);
	}
	
}

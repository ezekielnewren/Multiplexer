package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class StreamChannel extends Channel {

	final ChannelInputStream input;
	final ChannelOutputStream output;
	
	StreamChannel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize, final Object mutex) {
		super(inst, channel, recvBufferSize, sendBufferSize, mutex);
		input = new ChannelInputStream();
		output = new ChannelOutputStream();
	}

	public InputStream getInputStream() {
		return input;
	}
	
	public OutputStream getOutputStream() {
		return output;
	}
	
	class ChannelInputStream extends InputStream {

		@Override
		public synchronized int available() throws IOException {
			if (localInputClosed) throw new IOException("StreamChannel Closed");
			return window.available();
		}
		
		@Override
		public synchronized long skip(long skip) throws IOException {
			if (localInputClosed) throw new IOException("StreamChannel Closed");
			return window.skip(skip);
		}
		
		@Override
		public synchronized int read() throws IOException {
			if (localInputClosed) throw new IOException("StreamChannel Closed");
			synchronized(mutex) {
				try {
					int read = window.read();
					if (read<0) return read;
					incRead(1);
					flushRead();
					return read;
				} finally {
					mutex.notifyAll();
				}
			}
		}
		
		@Override
		public synchronized int read(byte[] b, int off, int len) throws IOException {
			if (localInputClosed) throw new IOException("StreamChannel Closed");
			synchronized(mutex) {
				try {
					while (window.available()==0) home.linger();
					int read = window.read(b, off, len);
					if (read<0) return read;
					incRead(read);
					flushRead();
					return read;
				} finally {
					mutex.notifyAll();
				}
			}
		}
		
		@Override
		public void close() throws IOException {
			closeInput();
		}
		
	}

	class ChannelOutputStream extends OutputStream {

		@Override
		public synchronized void write(int b) throws IOException {
			if (localOutputClosed) throw new IOException("StreamChannel Closed");
			synchronized(mutex) {
				try {
					while (getCredit()==0) home.linger();
					
					writePacketWithOneByte(b);
					
				} finally {
					mutex.notifyAll();
				}
			}
		}
		
		@Override
		public synchronized void write(byte[] b, int off, int len) throws IOException {
			if (localOutputClosed) throw new IOException("StreamChannel Closed");
			synchronized(mutex) {
				try {
					int total = 0;
					while (total<len) {
						int write = 0;
						while (( write=Math.min(getCredit(), Math.min(0xffff, len-total)) )==0) home.linger();
						
						writePacket(b, off+total, write);
						
						total += write;
					}
				} finally {
					mutex.notifyAll();
				}
			}
		}
		
		
		@Override
		public void close() throws IOException {
			closeOutput();
		}
		
		
	}
	
}

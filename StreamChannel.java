package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicLong;

public class StreamChannel extends Channel {

	final ChannelInputStream input;
	final ChannelOutputStream output;
	
	StreamChannel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize, final Object mutex) {
		super(inst, channel, recvBufferSize, sendBufferSize, mutex);
		input = new ChannelInputStream();
		output = new ChannelOutputStream();
	}

	class ChannelInputStream extends InputStream {

		@Override
		public synchronized int read() throws IOException {
			if (localInputClosed) throw new IOException("StreamChannel Closed");
			synchronized(mutex) {
				try {
					int read = window.read();
					if (read<0) return read;
					parent.incProcessed(1);
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
					int read = window.read(b, off, len);
					if (read<0) return read;
					parent.incProcessed(read);
					return read;
				} finally {
					mutex.notifyAll();
				}
			}
		}
		
		@Override
		public void close() throws IOException {
			parent.close();
		}
		
	}

	class ChannelOutputStream extends OutputStream {

		@Override
		public synchronized void write(int b) throws IOException {
			synchronized(mutex) {
				try {
					final AtomicLong timer = new AtomicLong();
					while (parent.getCredit()==0) home.linger(0, timer);
					
					home.writePacketWithOneByte(channel, parent.clearProcessed(), b);
					parent.withdrawCredit(1);
					
					
					
				} finally {
					mutex.notifyAll();
				}
			}
		}
		
		@Override
		public synchronized void write(byte[] b, int off, int len) throws IOException {
			synchronized(mutex) {
				try {
					
					
					
					
				} finally {
					mutex.notifyAll();
				}
			}
		}
		
		
		@Override
		public void close() throws IOException {
			parent.close();
		}
		
		
	}
	
}

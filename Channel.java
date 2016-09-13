package com.github.ezekielnewren.net.multiplexer;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public class Channel implements Closeable {

	private long state;
	private static final int DEFAULT_CLOSE_TIMEOUT = 3000;

	final Multiplexer home;
	final int channel;
	final Object mutex;
	final Multiplexer.ChannelParameter cmParam;
	final AtomicBoolean signal;
	final ChannelInputStream input;
	final ChannelOutputStream output;

	boolean localInputClosed = false;
	boolean localOutputClosed = false;
	boolean remoteOutputClosed = false;

	Channel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize) {
		this.home = inst;
		this.channel = channel;
		mutex = home.mutex;
		cmParam = home.getCP(channel);
		signal = cmParam.signal;
		input = new ChannelInputStream(recvBufferSize);
		output = new ChannelOutputStream(sendBufferSize);
		state = Multiplexer.STATE_ESTABLISHED;
	}

	public boolean isConnectionClosed() {
		synchronized(mutex) {
			return localOutputClosed&&remoteOutputClosed;
		}
	}

	public boolean isClosed() {
		synchronized(mutex) {
			return localInputClosed&&localOutputClosed;
		}
	}
	
	public int getChannelNumber() throws IOException {
		if (isClosed()) throw new IOException("Channel closed");
		return channel;
	}

	public InputStream getInputStream() throws IOException {
		if (isClosed()) throw new IOException("Channel closed");
		return input;
	}

	public OutputStream getOutputStream() throws IOException {
		if (isClosed()) throw new IOException("Channel closed");
		return output;
	}

	class ChannelInputStream extends InputStream {

		final int procThreshold;
		final ByteArrayCircularBuffer cbuff;
		private int proc;
		
		ChannelInputStream(int cbuffSize) {
			cbuff = new ByteArrayCircularBuffer(cbuffSize);
			procThreshold = cbuff.getBufferSize()/2;
		}
		
		private void flushProc() throws IOException {
			if (proc>procThreshold) {
				int ack = clearProcessed();
				if (ack>0) home.writePacket(channel, ack, Multiplexer.FLAG_NULL);
			}
		}
		
		public synchronized int available() throws IOException {
			if (localInputClosed) throw new IOException("Stream Closed");
			return cbuff.available();
		}

		public synchronized int read() throws IOException {
			if (localInputClosed) throw new IOException("Stream Closed");
			int read = cbuff.read();
			if (read<0) return read;
			updateProcessed(1);
			flushProc();
			return read;
		}

		public synchronized int read(byte[] b, int off, int len) throws IOException {
			if (localInputClosed) throw new IOException("Stream Closed");
			int read = cbuff.read(b, off, len);
			if (read<0) return read;
			updateProcessed(read);
			flushProc();
			//MuxDriver.log("reading data");
			return read;
		}

		void appendData(byte[] b, int off, int len) throws IOException {
			cbuff.write(b, off, len);
		}
		
		void setEOF() {
			remoteOutputClosed = true;
			cbuff.closeOutput();
		}
		
		int clearProcessed() {
			synchronized(mutex) {
				int x = proc;
				proc = 0;
				mutex.notifyAll();
				return x;
			}
		}

		void updateProcessed(int amount) {
			synchronized(mutex) {
				assert(0<=amount&&proc+amount<=cbuff.getBufferSize());
				proc += amount;
				mutex.notifyAll();
			}
		}

		void closeQuietly() {
			localInputClosed = true;
		}
		
		@Override
		public void close() throws IOException {
			synchronized(mutex) {
				while (state==Multiplexer.STATE_CHANNEL_IN_CLOSING_FUNCTION) {
					try{mutex.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
				}
				if (localInputClosed) return;
				
				try {
					setState(Multiplexer.STATE_CHANNEL_IN_CLOSING_FUNCTION);
				
					if (!remoteOutputClosed) {
						home.writePacket(channel, clearProcessed(), Multiplexer.FLAG_ICL);
						home.await(signal, DEFAULT_CLOSE_TIMEOUT);
					}
				
					cbuff.closeInput();
					if (isConnectionClosed()) home.unbind(channel);
					if (localOutputClosed) {
						setState(Multiplexer.STATE_CHANNEL_CLOSED);
					} else {
						setState(Multiplexer.STATE_INPUT_CLOSED);
					}
				} finally {
					localInputClosed = true;
					home.signal(home.segregator.signal);
				}
			}
		}
	}

	class ChannelOutputStream extends OutputStream {

		private final int sendBufferSize;
		private int writeable;
		
		public ChannelOutputStream(int sendBufferSize) {
			incWriteable(this.sendBufferSize=sendBufferSize);
		}
		
		public synchronized void write(int b) throws IOException {
			if (localOutputClosed) throw new IOException("Stream Closed");
			home.writePacketWithOneByte(channel, input.clearProcessed(), b);
			decWriteable(1);
		}

		public synchronized void write(byte[] b, int off, int len) throws IOException {
			if (b == null) {
				throw new NullPointerException();
	        } else if ((off < 0) || (off > b.length) || (len < 0) ||
	                   ((off + len) > b.length) || ((off + len) < 0)) {
	            throw new IndexOutOfBoundsException();
	        }
			if (localOutputClosed) throw new IOException("Stream Closed");
			int total = 0;
			while (total<len) {
				int write;
				synchronized(mutex) {
					while ((write=Math.min(writeable, Math.min(0xffff, len-total)))==0&&!localOutputClosed) {
						try{mutex.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
					}
					if (localOutputClosed) throw new IOException("Stream Closed");
				}

				home.writePacket(channel, 0, b, off+total, write, Multiplexer.FLAG_NULL);
				decWriteable(write);
				total += write;
			}
			//MuxDriver.log("writing data");
		}

		private void decWriteable(int amount) {
			assert(amount>=0);
			synchronized(mutex) {
				assert(writeable-amount>=0);
				writeable -= amount;
				mutex.notifyAll();
			}
		}

		void incWriteable(int amount) {
			assert(amount>=0);
			assert(writeable+amount<=sendBufferSize);
			writeable += amount;
			mutex.notifyAll();
		}

		void closeQuietly() {
			localOutputClosed = true;
		}
		
		@Override
		public void close() throws IOException {
			synchronized(mutex) {
				while (state==Multiplexer.STATE_CHANNEL_IN_CLOSING_FUNCTION) {
					try{mutex.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
				}
				if (localOutputClosed) return;

				try {
					setState(Multiplexer.STATE_CHANNEL_IN_CLOSING_FUNCTION);
					
					home.writePacket(channel, input.clearProcessed(), Multiplexer.FLAG_OCL);
					if (isConnectionClosed()) {
						setState(Multiplexer.STATE_CHANNEL_CLOSED);
						home.unbind(channel);
					}
					setState(Multiplexer.STATE_OUTPUT_CLOSED);
				} finally {
					localOutputClosed = true;
					home.signal(signal);
				}
				
			}
		}
	}

	@Override
	public void close() throws IOException {
		synchronized(mutex) {
			while (state==Multiplexer.STATE_CHANNEL_IN_CLOSING_FUNCTION) {
				try{mutex.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
			}
			if (isConnectionClosed()) return;
			
			try {
				
				if (!localInputClosed&&!localOutputClosed) {
					input.close();
					output.close();
					home.writePacket(channel, input.clearProcessed(), Multiplexer.FLAG_IOCL);
				} else if (!localInputClosed^!localOutputClosed) {
					input.close();
					output.close();
				}
	
				if (!remoteOutputClosed&&home.await(signal, DEFAULT_CLOSE_TIMEOUT)>DEFAULT_CLOSE_TIMEOUT) {
					throw new IOException("close timeout other side may not be closed");
				}
				
				setState(Multiplexer.STATE_CHANNEL_CLOSED);
				home.unbind(channel);
			} finally {
				setState(Multiplexer.STATE_CHANNEL_CLOSED);
				home.signal(signal);
			}
		}
	}
	
	void closeQuietly() {
		synchronized(mutex) {
			if (localInputClosed&&localOutputClosed&&remoteOutputClosed) return;
			localInputClosed = true;
			localOutputClosed = true;
			remoteOutputClosed = true;
			setState(Multiplexer.STATE_CHANNEL_CLOSED);
			mutex.notifyAll();
		}
	}
	
	void setState(long newState) {
		if (state!=Multiplexer.STATE_CHANNEL_CLOSED) {
			cmParam.state = newState;
		}
		state = newState;
	}
	
	
}








package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ByteArrayCircularBuffer {

	// locks
	private final Object readLock = new Object();
	private final Object writeLock = new Object();
	private final Object fieldLock = new Object();
	
	// data
	private final int FIRST_INDEX;
	private final int LAST_INDEX;
	private final byte[] cbuff;
	private boolean isUsingSharedBuffer;
	
	// pointers
	private final ByteArrayCircularBuffer inst;
	private int rp;
	private int wp;
	private int readable = 0;

	// flags
	private boolean inputClosed = false;
	private boolean outputClosed = false;

	// streams
	private final InputStream is;
	private final OutputStream os;

	public ByteArrayCircularBuffer() {
		this(1<<20);
	}

	public ByteArrayCircularBuffer(int cbuffSize) {
		this(new byte[cbuffSize], 0, cbuffSize-1);
		isUsingSharedBuffer = false;
	}

	public ByteArrayCircularBuffer(byte[] reusableByteArray, int firstIndex, int lastIndex) {
		if (reusableByteArray==null) throw new NullPointerException();
		if (!( (0<=firstIndex&&firstIndex<reusableByteArray.length)
				&& (firstIndex<=lastIndex&&lastIndex<reusableByteArray.length)
				)) throw new IndexOutOfBoundsException();
		if (lastIndex-firstIndex+1<1) throw new IllegalArgumentException("buffer size must be at least 1");
		cbuff = reusableByteArray;
		FIRST_INDEX = firstIndex;
		LAST_INDEX = lastIndex;
		rp = wp = FIRST_INDEX;
		is = new CircularBufferInputStream();
		os = new CircularBufferOutputStream();
		inst = this;
		isUsingSharedBuffer = true;
	}
	
	public ByteArrayCircularBuffer(byte[] reusableByteArray) {
		this(reusableByteArray, 0, reusableByteArray.length-1);
	}
	
	public int available() throws IOException {
		if (inputClosed) throw new IOException("InputStream Closed");
		synchronized(fieldLock) {
			return readable;
		}
	}

	public int free() throws IOException {
		if (outputClosed) throw new IOException("OutputStream Closed");
		synchronized(fieldLock) {
			return getBufferSize()-readable;
		}
	}

	public long skip(long skip) throws IOException {
		synchronized(fieldLock) {
			if (skip<0) throw new IllegalArgumentException();
			int amount = (int) Math.min(skip, readable);
			decReadable(amount);
			return amount;
		}
	}
	
	public int getBufferSize() {
		return LAST_INDEX-FIRST_INDEX+1;
	}

	public int read() throws IOException {
		if (inputClosed) throw new IOException("InputStream Closed");
		synchronized(readLock) {
			synchronized(fieldLock) {
				while (available() == 0) {
					if (inputClosed) throw new IOException("InputStream Closed");
					if (outputClosed) return -1;
					try{fieldLock.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
				}
			}

			int read = cbuff[rp]&0xFF;
			decReadable(1);
			return read;
		}
	}

	public int read(byte[] b, int off, int len) throws IOException {
		if (b == null) {
			throw new NullPointerException();
		} else if (off < 0 || len < 0 || len > b.length - off) {
			throw new IndexOutOfBoundsException();
		} else if (len == 0) {
			return 0;
		}

		if (inputClosed) throw new IOException("InputStream Closed");
		synchronized(readLock) {
			int avai = 0;
			synchronized(fieldLock) {
				while ((avai = available()) == 0) {
					if (inputClosed) throw new IOException("InputStream Closed");
					if (outputClosed) return -1;
					try{fieldLock.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
				}
			}
			
			int rp = this.rp;
			int read = Math.min(avai, len);
	
			int end = cbuff.length-rp;
			if (read <= end) {
				System.arraycopy(cbuff, rp, b, off, read);
			} else {
				System.arraycopy(cbuff, rp, b, off, end);
				System.arraycopy(cbuff, 0, b, off+end, read-end);
			}
			decReadable(read);
			return read;
		}
	}

	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}
	
	public void write(int b) throws IOException {
		if (inputClosed) closeOutput();
		if (outputClosed) throw new IOException("OutputStream Closed");
		synchronized(writeLock) {
			synchronized(fieldLock) {
				while (free() == 0) {
					if (inputClosed) closeOutput();
					if (outputClosed) throw new IOException("OutputStream Closed");
					try{fieldLock.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
				}
			}
			
			cbuff[wp] = (byte) b;
			incReadable(1);
		}
	}

	public void write(byte[] b, int off, int len) throws IOException {
		if (b == null) {
			throw new NullPointerException();
		} else if ((off < 0) || (off > b.length) || (len < 0) ||
				((off + len) > b.length) || ((off + len) < 0)) {
			throw new IndexOutOfBoundsException();
		} else if (len == 0) {
			return;
		}

		if (inputClosed) closeOutput();
		if (outputClosed) throw new IOException("OutputStream Closed");
		synchronized(writeLock) {
			int written = 0;
			while (written < len) {
				int free=0;
				synchronized(fieldLock) {
					while ((free = free()) == 0) {
						if (inputClosed) closeOutput();
						if (outputClosed) throw new IOException("OutputStream Closed");
						try{fieldLock.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
					}
				}
				
				int wp = this.wp;
				int write = Math.min(free, len-written);
				
				int end = LAST_INDEX-wp+1;
				if (write <= end) {
					System.arraycopy(b, off+written, cbuff, wp, write);
				} else {
					System.arraycopy(b, off+written, cbuff, wp, end);
					System.arraycopy(b, off+written+end, cbuff, 0, -end+write);
				}
				incReadable(write);
				written += write;
			}
		}
	}

	public void write(byte[] b) throws IOException {
		write(b, 0, b.length);
	}
	
	public InputStream getInputStream() {
		return is;
	}

	public OutputStream getOutputStream() {
		return os;
	}


	private class CircularBufferInputStream extends InputStream {
		public int available() throws IOException {
			return inst.available();
		}

		public long skip(long amount) throws IOException {
			return inst.skip(amount);
		}
		
		public int read() throws IOException {
			return inst.read();
		}

		public int read(byte[] b, int off, int len) throws IOException {
			return inst.read(b, off, len);
		}

		public void close() throws IOException {
			inst.closeInput();
		}
	}

	private class CircularBufferOutputStream extends OutputStream {
		public void write(int b) throws IOException {
			inst.write(b);
		}

		public void write(byte[] b, int off, int len) throws IOException {
			inst.write(b, off, len);
		}

		public void close() throws IOException {
			inst.closeOutput();
		}
	}


	public void closeInput() {
		synchronized(fieldLock) {
			inputClosed = true;
			fieldLock.notifyAll();
		}
	}

	public void closeOutput() {
		synchronized(fieldLock) {
			outputClosed = true;
			fieldLock.notifyAll();
		}
	}

	public void close() {
		closeOutput();
		closeInput();
	}

	private void incReadable(int amount) {
		synchronized(fieldLock) {
			wp = FIRST_INDEX+(((wp-FIRST_INDEX)+amount)%getBufferSize());
			readable += amount;
			fieldLock.notifyAll();
		}
	}
	
	private void decReadable(int amount) {
		synchronized(fieldLock) {
			rp =  FIRST_INDEX+(((rp-FIRST_INDEX)+amount)%getBufferSize());
			readable -= amount;
			fieldLock.notifyAll();
		}
	}
}




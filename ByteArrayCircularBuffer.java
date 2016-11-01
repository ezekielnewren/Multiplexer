package com.github.ezekielnewren.net.multiplexer;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class ByteArrayCircularBuffer {

	// constants
	private final ByteArrayCircularBuffer inst;
	private final int FIRST_INDEX;
	private final int LAST_INDEX;
	private final byte[] cbuff;
	private final boolean isUsingSharedBuffer;
	
	// streams
	private final InputStream is;
	private final OutputStream os;

	// locks
	private final Object readLock = new Object();
	private final Object writeLock = new Object();
	private final Object fieldLock = new Object();
	
	// data
	private int rp;
	private int wp;
	private int readable = 0;

	// flags
	volatile private boolean inputClosed = false;
	volatile private boolean outputClosed = false;

	// TODO master constructor
	private ByteArrayCircularBuffer(byte[] reusableByteArray, int firstIndex, int lastIndex, boolean shared) {
		if (reusableByteArray==null) throw new NullPointerException();
		if (!( (0<=firstIndex&&firstIndex<reusableByteArray.length)
				&& (firstIndex<=lastIndex&&lastIndex<reusableByteArray.length)
				)) throw new IndexOutOfBoundsException();
		if (getBufferSize()<1) throw new IllegalArgumentException("buffer size must be at least 1");
		cbuff = reusableByteArray;
		FIRST_INDEX = firstIndex;
		LAST_INDEX = lastIndex;
		rp = wp = FIRST_INDEX;
		is = new CircularBufferInputStream();
		os = new CircularBufferOutputStream();
		inst = this;
		isUsingSharedBuffer = shared;
	}
	
	// TODO shared buffer constructors
	public ByteArrayCircularBuffer(byte[] reusableByteArray, int firstIndex, int lastIndex) {
		this(reusableByteArray, firstIndex, lastIndex, true);
	}
	
	public ByteArrayCircularBuffer(byte[] reusableByteArray) {
		this(reusableByteArray, 0, reusableByteArray.length-1);
	}

	// TODO non-shared buffer constructors
	public ByteArrayCircularBuffer(int cbuffSize) {
		this(new byte[cbuffSize], 0, cbuffSize-1, false);
	}
	
	public ByteArrayCircularBuffer() {
		this(1<<20);
	}

	// TODO internal modifiers
	private void incReadable(int amount) {
		assert(Thread.holdsLock(fieldLock));	
		wp = FIRST_INDEX+(((wp-FIRST_INDEX)+amount)%getBufferSize());
		readable += amount;
		fieldLock.notifyAll();
	}
	
	private void decReadable(int amount) {
		assert(Thread.holdsLock(fieldLock));
		rp =  FIRST_INDEX+(((rp-FIRST_INDEX)+amount)%getBufferSize());
		readable -= amount;
		fieldLock.notifyAll();
	}
	
	private int available0() throws IOException {
		assert(Thread.holdsLock(fieldLock));
		return readable;
	}
	
	private int free0() throws IOException {
		assert(Thread.holdsLock(fieldLock));
		return getBufferSize()-readable;
	}
	
	// TODO public informing methods
	public int getBufferSize() {
		return LAST_INDEX-FIRST_INDEX+1;
	}

	public int available() throws IOException {
		if (inputClosed) throw new IOException("InputStream Closed");
		synchronized(fieldLock) {
			return available0();
		}
	}

	public int free() throws IOException {
		if (outputClosed) throw new IOException("OutputStream Closed");
		synchronized(fieldLock) {
			return free0();
		}
	}

	public boolean isUsingSharedBuffer() {
		return isUsingSharedBuffer;
	}
	
	public long skip(long skip) throws IOException {
		if (inputClosed) throw new IOException("InputStream Closed");
		synchronized(fieldLock) {
			if (skip<0) throw new IllegalArgumentException();
			int amount = (int) Math.min(skip, readable);
			decReadable(amount);
			return amount;
		}
	}
	

	// TODO read methods
	public int read() throws IOException {
		synchronized(readLock) {
			synchronized(fieldLock) {
				while (available0() == 0 && !inputClosed && !outputClosed) {
					try{fieldLock.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
				}
				if (inputClosed) throw new IOException("InputStream Closed");
				if (outputClosed&&available0()==0) return -1;

				int read = cbuff[rp]&0xFF;
				decReadable(1);
				return read;
			}
		}
	}

	private int read0(byte[] b, int off, int len) throws IOException {
		assert(Thread.holdsLock(fieldLock));
		
		int read;
		while ((read=Math.min(available0(), len)) == 0 && !inputClosed && !outputClosed) {
			try{fieldLock.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
		}
		if (inputClosed) throw new IOException("InputStream Closed");
		if (outputClosed&&available0()==0) return -1;

		int remaining = LAST_INDEX-rp+1;
		if (read <= remaining) {
			System.arraycopy(cbuff, rp, b, off, read);
		} else {
			System.arraycopy(cbuff, rp, b, off, remaining);
			System.arraycopy(cbuff, FIRST_INDEX, b, off+remaining, read-remaining);
		}
		decReadable(read);
		return read;
	}
	
	public int read(byte[] b, int off, int len) throws IOException {
		if (b == null) {
			throw new NullPointerException();
		} else if (off < 0 || len < 0 || len > b.length - off) {
			throw new IndexOutOfBoundsException();
		} else if (len == 0) {
			return 0;
		}

		synchronized(readLock) {
			synchronized(fieldLock) {
				return read0(b, off, len);
			}
		}
	}

	public void readFully(byte[] b, int off, int len) throws IOException {
		if (b == null) {
			throw new NullPointerException();
		} else if (off < 0 || len < 0 || len > b.length - off) {
			throw new IndexOutOfBoundsException();
		} else if (len == 0) {
			return;
		}

		synchronized(readLock) {
			synchronized(fieldLock) {
				int total = 0;
				while (total < len) {
					int read = read0(b, off+total, len-total);
					if (read<0) throw new EOFException();
					total += read;
				}
			}
		}
	}
	
	public int read(byte[] b) throws IOException {
		return read(b, 0, b.length);
	}
	
	// TODO write methods
	public void write(int b) throws IOException {
		synchronized(writeLock) {
			synchronized(fieldLock) {
				while (free0() == 0 && !inputClosed && !outputClosed) {
					try{fieldLock.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
				}
				if (inputClosed) closeOutput();
				if (outputClosed) throw new IOException("OutputStream Closed");
			
				cbuff[wp] = (byte) b;
				incReadable(1);
			}
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

		synchronized(writeLock) {
			synchronized(fieldLock) {
				int written = 0;
				while (written < len) {
					int write;
					while ((write=Math.min(free0(), len-written)) == 0 && !inputClosed && !outputClosed) {
						try{fieldLock.wait();}catch(InterruptedException ie){Thread.currentThread().interrupt();}
					}
					if (inputClosed) closeOutput();
					if (outputClosed) throw new IOException("OutputStream Closed");
					
					// how many can we touch before we run off
					int remaining = LAST_INDEX-wp+1;
					if (write <= remaining) {
						System.arraycopy(b, off+written, cbuff, wp, write);
					} else {
						System.arraycopy(b, off+written, cbuff, wp, remaining);
						System.arraycopy(b, off+written+remaining, cbuff, FIRST_INDEX, write-remaining);
					}
					incReadable(write);
					written += write;
				}
			}
		}
	}

	public void write(byte[] b) throws IOException {
		write(b, 0, b.length);
	}
	
	// TODO IOStreams
	public InputStream getInputStream() {
		return is;
	}

	public OutputStream getOutputStream() {
		return os;
	}

	// TODO IOStreams definitions
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

	// TODO close functions
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

	
}




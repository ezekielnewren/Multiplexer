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
	private final byte[] cbuff;
	
	// pointers
	private final ByteArrayCircularBuffer inst;
	private int rp = 0;
	private int wp = 0;
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
		this(new byte[cbuffSize]);
	}

	public ByteArrayCircularBuffer(byte[] reusableByteArray) {
		if (reusableByteArray==null) throw new NullPointerException();
		cbuff = reusableByteArray;
		is = new CircularBufferInputStream();
		os = new CircularBufferOutputStream();
		inst = this;
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
			return cbuff.length-readable;
		}
	}

	public int getBufferSize() {
		return cbuff.length;
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
				
				int end = cbuff.length-wp;
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
			wp = (wp+amount)%cbuff.length;
			readable += amount;
			fieldLock.notifyAll();
		}
	}
	
	private void decReadable(int amount) {
		synchronized(fieldLock) {
			rp = (rp+amount)%cbuff.length;
			readable -= amount;
			fieldLock.notifyAll();
		}
	}
}

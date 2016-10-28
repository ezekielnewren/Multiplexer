package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;
import java.net.DatagramPacket;

public class DatagramPacketChannel extends Channel {

	final FastQueue packetList = new FastQueue();
	
	DatagramPacketChannel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize, final Object mutex) {
		super(inst, channel, recvBufferSize, sendBufferSize, mutex);
	}

	public void receive(DatagramPacket p) throws IOException {
		if (p.getLength()<getMinRecvLength()) throw new IllegalArgumentException("DatagramPacket length too small");
		if (localInputClosed) throw new IOException("DatagramPacketChannel Closed");
		
		synchronized(mutex) {
			while(packetList.size()==0) home.linger();
			
			int read = window.read(p.getData(), p.getOffset(), packetList.popInt());
			p.setLength(read);
			incRead(read);
		}
	}
	
	public void send(DatagramPacket p) throws IOException {
		if (p.getLength()>getMaxSendLength()) throw new IllegalArgumentException("DatagramPacket too large");
		if (localInputClosed) throw new IOException("DatagramPacketChannel Closed");
		
		synchronized(mutex) {
			if (getCredit()<p.getLength()) home.linger();
			writePacket(p.getData(), p.getOffset(), p.getLength());
		}
	}
	
	public int availablePackets() {
		synchronized(mutex) {
			return packetList.size();
		}
	}
	
	public int getMinRecvLength() {
		return Math.min(Multiplexer.MAX_PAYLOAD_SIZE, getReceiveBufferSize());
	}
	
	public int getMaxSendLength() {
		return Math.min(Multiplexer.MAX_PAYLOAD_SIZE, getSendBufferSize());
	}
	
	@Override
	void feed(byte[] b, int off, int len) throws IOException {
		assert(Thread.holdsLock(mutex));
		
		super.feed(b, off, len);
		packetList.push(len);
	}
	
}


















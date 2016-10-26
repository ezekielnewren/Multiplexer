package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;
import java.net.DatagramPacket;

public class DatagramPacketChannel extends Channel {

	final FastQueue packetList = new FastQueue();
	
	DatagramPacketChannel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize, final Object mutex) {
		super(inst, channel, recvBufferSize, sendBufferSize, mutex);
	}

	public void receive(DatagramPacket p) throws IOException {
		synchronized(mutex) {
			while(packetList.size()==0) home.linger();

			int packLen = packetList.popInt();
			int loRead = Math.min(packLen, p.getLength());
			int theRest = packLen-loRead;
			window.read(p.getData(), p.getOffset(), loRead);
			window.skip(theRest);
			
			incRead(packLen);
		}
	}
	
	public void send(DatagramPacket p) throws IOException {
		synchronized(mutex) {
			writePacket(p.getData(), p.getOffset(), p.getLength());
		}
	}
	
	@Override
	void feed(byte[] b, int off, int len) throws IOException {
		assert(Thread.holdsLock(mutex));
		
		parent.feed(b, off, len);
		packetList.push(len);
	}
	
}


















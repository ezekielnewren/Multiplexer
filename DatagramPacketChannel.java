package com.github.ezekielnewren.net.multiplexer;

import java.io.IOException;
import java.net.DatagramPacket;

public class DatagramPacketChannel extends Channel {

	final FastQueue packetList = new FastQueue();
	
	DatagramPacketChannel(Multiplexer inst, int channel, int recvBufferSize, int sendBufferSize) {
		super(inst, channel, recvBufferSize, sendBufferSize);
	}

	public void receive(DatagramPacket p) throws IOException {
		synchronized(mutex) {
			while(packetList.size()==0) {
				try{mutex.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();}
			}
			
			int packLen = packetList.popInt();
			int loRead = Math.min(packLen, p.getLength());
			int theRest = packLen-loRead;
			window.read(p.getData(), p.getOffset(), loRead);
			window.getInputStream().skip(8);
			window.skip(theRest);
			
		}
	}
	
	public void send(DatagramPacket p) throws IOException {
		synchronized(mutex) {
			super.writePacket(p.getData(), p.getOffset(), p.getLength());
		}
	}
	
	@Override
	void feed(byte[] b, int off, int len) throws IOException {
		super.feed(b, off, len);
		packetList.push(len);
	}
	
}

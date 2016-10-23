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
			while(packetList.size()==0) {
				try{mutex.wait();}catch(InterruptedException e){Thread.currentThread().interrupt();}
			}

			int packLen = packetList.popInt();
			int loRead = Math.min(packLen, p.getLength());
			int theRest = packLen-loRead;
			window.read(p.getData(), p.getOffset(), loRead);
			window.skip(theRest);
			
			super.incProcessed(packLen);
		}
	}
	
	public void send(DatagramPacket p) throws IOException {
		synchronized(mutex) {
			super.writePacket(p.getData(), p.getOffset(), p.getLength());
			super.withdrawCredit(p.getLength());
		}
	}
	
	public int getSendBufferSize() {
		return super.sendBufferSize;
	}
	
	public int getReceiveBufferSize() {
		return super.recvBufferSize;
	}
	
	@Override
	void feed(byte[] b, int off, int len) throws IOException {
		synchronized(mutex) {
			super.feed(b, off, len);
			packetList.push(len);
			mutex.notifyAll();
		}
	}
	
}


















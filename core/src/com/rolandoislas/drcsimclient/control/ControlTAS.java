package com.rolandoislas.drcsimclient.control;

import java.util.concurrent.*;
import java.io.*;
import java.nio.Buffer;
import java.nio.ByteBuffer;

import com.badlogic.gdx.Gdx;
import com.rolandoislas.drcsimclient.data.Constants;
import com.rolandoislas.drcsimclient.stage.StageControl;
import com.rolandoislas.drcsimclient.Client;
import com.rolandoislas.drcsimclient.util.logging.Logger;
import static com.rolandoislas.drcsimclient.Client.sockets;

public class ControlTAS implements Control {
	private static final int BYTES_PER_COMMAND = 2;
	public static ControlTAS controlTAS;
	//operation mode: 0=nothing, 1=dump, 2=replay
	private byte mode;
	private FileOutputStream outStream = null;
	private FileInputStream inStream = null;
	private int fileIndex = 0;
	private Object buttonsLock;
	private short buttons = 0;
	private byte numUpdates = 0;
	private ScheduledExecutorService threadPool;

	public ControlTAS() {
		//I feel really bad doing this, but I think it's safe
		ControlTAS.controlTAS = this;
		buttonsLock = new Object();
	}
	
	@Override
	public void init(StageControl stage) {
		if(!(Client.args.dumpPath.isEmpty()))
			mode |= 1;
		if(!(Client.args.readPath.isEmpty()))
			mode |= 2;
		
		if(mode > 0) {
			if(mode == 1) {
				try {
					outStream = new FileOutputStream(Client.args.dumpPath);
				}
				catch (IOException e) {
					Logger.warn("Could not create controller dump file");
					mode = 0;
					return;
				}
			}
			if(mode == 2) {
				try {
					inStream = new FileInputStream(Client.args.readPath);
				}
				catch (IOException e) {
					Logger.warn("Could not read input file");
					mode = 0;
					return;
				}
			}
			
			threadPool = Executors.newScheduledThreadPool(1);
			Tick tick = new Tick();
			threadPool.scheduleAtFixedRate(tick, 100000000, (1000000000 / 60), TimeUnit.NANOSECONDS);
		}
	}

	private class Tick implements Runnable {
		@Override
		public void run() {
			long start = System.nanoTime();
			ByteBuffer buffer = ByteBuffer.allocate(BYTES_PER_COMMAND);
			if(mode == 1) {
				synchronized(buttonsLock) {
					buffer.putShort(buttons);
					if(numUpdates > 0) {
						numUpdates = 0;
						buttons = 0;
					}
				}
				try {
					outStream.write(buffer.array());
				}
				catch (IOException e) {
					Logger.warn("Error writing bytes to dump file");
				}
			}
			if(mode == 2) {
				byte[] read = new byte[BYTES_PER_COMMAND];
				try {
					if(inStream.available() == 0) {
						Logger.info("Reached end of input file");
						threadPool.shutdown();
						inStream.close();
						return;
					}
					inStream.read(read, fileIndex, BYTES_PER_COMMAND);
					buffer.put(read);
					buffer.position(0);
					short buttons = buffer.getShort();
					sockets.sendButtonInput(buttons);
				}
				catch (IOException e) {
					Logger.warn("Error reading bytes from file");
				}
			}
			//System.out.printf("%8d\n", System.nanoTime() - start);
		}
	}

	@Override
	public void update() { }
	
	public void dumpHookButtons(short buttons) {
		if(mode == 0)
			return;
		synchronized(buttonsLock) {
			this.buttons |= buttons;
			numUpdates++;
		}
	}

	@Override
	public void vibrate(int milliseconds) { }

	public void dispose() {
		if(mode > 0) {
			threadPool.shutdown();
			try {
				threadPool.awaitTermination(1, TimeUnit.SECONDS);
			}
			catch (InterruptedException e) {
				Logger.warn("Dumper not shutdown in time");
				threadPool.shutdownNow();
			}
			if(mode == 1) {
				try {
					outStream.flush();
					outStream.close();
				}
				catch (IOException e) {
					Logger.warn("Error closing dump file");
				}
			}
		}
	}
}

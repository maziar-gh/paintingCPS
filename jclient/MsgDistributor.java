/*************************************************
#
# Purpose: "Message Distributor" aims to distribute the message
            received through the unique GUID
# Author.: Zihong Zheng
# Version: 0.1
# License: 
#
*************************************************/

import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.Semaphore;
import java.util.Queue;
import java.util.HashMap; 
import java.util.LinkedList;
import edu.rutgers.winlab.jmfapi.*;

public class MsgDistributor {
	private
		boolean debug = true;
		int BUFFER_SIZE = 1024;
		int mfsockid;
		Lock sendLock;
		Lock recvLock;
		Lock idLock;
		Lock mapLock;
		Semaphore connectSem;
		Semaphore acceptSem;
		Queue<Integer> connectQueue;
		HashMap<Integer, Semaphore> semMap;
		HashMap<Integer, Queue<byte[]>> queueMap;
		HashMap<Integer, Integer> statusMap;
		GUID srcGUID;
		GUID dstGUID;
		String scheme = "basic";
		JMFAPI handler;

	public MsgDistributor() {
		mfsockid = -1;
    }

    public int init(int srcGUID, int dstGUID, boolean debug) {
    	if (mfsockid != -1) {
    		System.out.println("ERROR: Dont reinit MsgDistributor!");
    		return -1;
    	}

    	mfsockid = 0;
    	this.debug = debug;

    	sendLock = new ReentrantLock();
    	recvLock = new ReentrantLock();
    	idLock = new ReentrantLock();
    	mapLock = new ReentrantLock();
    	connectSem = new Semaphore(0);
    	acceptSem = new Semaphore(0);
    	connectQueue = new LinkedList<Integer>();
    	semMap = new HashMap<Integer, Semaphore>();
    	queueMap = new HashMap<Integer, Queue<byte[]>>();
    	statusMap = new HashMap<Integer, Integer>();

    	// init the mfapi
    	System.out.println("Start to initialize the mf.");
		this.srcGUID = new GUID(srcGUID);
		this.dstGUID = new GUID(dstGUID);
		handler = new JMFAPI();
		try {
			handler.jmfopen(scheme, this.srcGUID);		
    		System.out.println("Finished.");
		}
		catch (JMFException e) {
			mfsockid = -1;
			System.out.println(e.toString());
		}

    	return 0;
    }

    public int listen() {
    	if (mfsockid == -1) {
    		System.out.println("ERROR: Init MsgDistributor first!");
    		return -1;
    	}

    	byte[] buf = new byte[BUFFER_SIZE];
		int ret;

		try {
			ret = handler.jmfrecv_blk(null, buf, BUFFER_SIZE);
			if(ret < 0)
		    {
		        System.out.printf("mfrec error\n"); 
		        return -1;
		    }
			String bufString = new String(buf);
			String delims = "[,]";
			String[] tokens = bufString.split(delims);
			System.out.println("receive new message, header: " + tokens[0]);
			if (tokens[0].equals("create")) {
		    	acceptSem.release();
			}
			else if (tokens[0].equals("accepted")) {
				int createdID = Integer.parseInt(tokens[1]);
				connectQueue.offer(createdID);
		    	connectSem.release();
			}
			else if (tokens[0].equals("sock")) {
				int sockID = Integer.parseInt(tokens[1]);
		        if (!semMap.containsKey(sockID)) {
    	            System.out.printf("ERROR: Socket ID not exist\n");
		        	return -1;
		        }
				int idLen = 1, divisor = 10;
				while (sockID / divisor != 0)
		        {
		            divisor *= 10;
		            ++idLen;
		        }

		        int contentLen = BUFFER_SIZE - idLen - 6;

		        byte[] content = {0x00};

		        Queue<byte[]> idQueue = queueMap.get(sockID);
		        idQueue.offer(content);
		        Semaphore idSem = semMap.get(sockID);
		        idSem.release();
			}
			else if (tokens[0].equals("close")) {
				int sockID = Integer.parseInt(tokens[1]);
				if (debug) System.out.println("peer closed the connection");
				this.close(sockID, 1);
			}
			else {
		        System.out.printf("unable to classify this message, discard\n");
		        return -1;
			}
		}
		catch (JMFException e) {
			System.out.println(e.toString());
		}
    	
    	return 0;
    }

    // init a new mf socket and return the new created mf socket id, intiative side
    public int connect() {
    	if (mfsockid == -1) {
    		System.out.println("ERROR: Init MsgDistributor first!");
    		return -1;
    	}
    	try {    		
	    	int ret = 0;
	    	String headerString = String.format("create");
	    	byte[] header = headerString.getBytes("UTF-8");
	    	byte[] headerSend = new byte[BUFFER_SIZE];
	    	for (int i = 0; i < header.length; ++i) {
	    		headerSend[i] = header[i];
	    	}
	    	// send the connect request
	    	sendLock.lock();
	    	ret = handler.jmfsend(headerSend, BUFFER_SIZE, dstGUID);
	    	if(ret < 0)
		    {
		        System.out.printf ("mfsendmsg error\n");
		        sendLock.unlock();
		        return -1;
		    }
	    	sendLock.unlock();

	    	// wait for the response
	    	if (debug) System.out.println("wait for the connect response");
	    	idLock.lock();
	    	connectSem.acquire();

	    	if (debug) System.out.println("got response");
	    	int newID = connectQueue.poll();
	    	if (debug) System.out.println("accepted id: " + newID);

	    	// create new queue and semaphore for the new conncection
	    	Semaphore newConnectSem = new Semaphore(0);
	    	Queue<byte[]> newConnectQueue = new LinkedList<byte[]>();
	    	mapLock.lock();
	    	semMap.put(newID, newConnectSem);
	    	queueMap.put(newID, newConnectQueue);
	    	statusMap.put(newID, 1);
	    	if (debug) System.out.println("create and put addrs of queue, semaphore and status into maps");
	    	mapLock.unlock();
	    	idLock.unlock();
    	}
    	catch (Exception e) {
			e.printStackTrace();
        }

    	return 0;
    }

    // accept a new connection, return the socket id
    public int accept() {
    	if (mfsockid == -1) {
    		System.out.println("ERROR: Init MsgDistributor first!");
    		return -1;
    	}

    	try {
	    	acceptSem.acquire();
	    	if (debug) System.out.println("new connection needed to accept");
	    	idLock.lock();
    	    // got a new connection, need to accpet, create a new id for it
    	    ++mfsockid;
		    while (semMap.containsKey(mfsockid))
		    {
		        ++mfsockid;
		    }
		    int newID = mfsockid;
	    	idLock.unlock();
	    	if (debug) System.out.println("create new id: " + newID);
	    	int ret = 0;
	    	String headerString = String.format("accepted,%d", newID);
	    	byte[] header = headerString.getBytes("UTF-8");
	    	byte[] headerSend = new byte[BUFFER_SIZE];
	    	for (int i = 0; i < header.length; ++i) {
	    		headerSend[i] = header[i];
	    	}
	    	sendLock.lock();
	    	ret = handler.jmfsend(headerSend, BUFFER_SIZE, dstGUID);
	    	if(ret < 0)
		    {
		        System.out.printf ("mfsendmsg error\n");
		        sendLock.unlock();
		        return -1;
		    }
	    	sendLock.unlock();

	    	// create new queue and semaphore for the new conncection
	    	Semaphore newConnectSem = new Semaphore(0);
	    	Queue<byte[]> newConnectQueue = new LinkedList<byte[]>();
	    	mapLock.lock();
	    	semMap.put(newID, newConnectSem);
	    	queueMap.put(newID, newConnectQueue);
	    	statusMap.put(newID, 1);
	    	if (debug) System.out.println("create and put addrs of queue, semaphore and status into maps");
	    	mapLock.unlock();
    	} 
    	catch (Exception e) {
			e.printStackTrace();
        }

    	return 0;
    }

	public int send(int sockID, char[] buf, int size) {
    	if (mfsockid == -1) {
    		System.out.println("ERROR: Init MsgDistributor first!");
    		return -1;
    	}


    	
    	return 0;
    }

	public int recv(int sockID, char[] buf, int size) {
    	if (mfsockid == -1) {
    		System.out.println("ERROR: Init MsgDistributor first!");
    		return -1;
    	}


    	
    	return 0;
    }

	public int close(int sockID, int mode) {
    	if (mfsockid == -1) {
    		System.out.println("ERROR: Init MsgDistributor first!");
    		return -1;
    	}
    	if (!semMap.containsKey(sockID)) {
    		System.out.println("ERROR: Socket ID not exist");
    		return -1;
    	}

    	if (debug) System.out.println("now close the socket: " + sockID);
    	Semaphore closeSem = semMap.get(sockID);
    	// Queue<char[]> closeQueue = queueMap.get(sockID);
    	mapLock.lock();
    	// change the status from 1 to 0, means closed
    	statusMap.put(sockID, 0);
    	// signal the recv to end
    	closeSem.release();
    	semMap.remove(sockID);
    	queueMap.remove(sockID);
    	mapLock.unlock();

    	if (mode == 0) {
    		// if not in passive mode, then notify the peer to close the connection
    		try {
	    		int ret = 0;
	    		String headerString = String.format("close,%d", sockID);
		    	byte[] header = headerString.getBytes("UTF-8");
		    	byte[] headerSend = new byte[BUFFER_SIZE];
		    	for (int i = 0; i < header.length; ++i) {
		    		headerSend[i] = header[i];
		    	}
		    	sendLock.lock();
		    	if (debug) System.out.println("send close command");
		    	ret = handler.jmfsend(headerSend, BUFFER_SIZE, dstGUID);
		    	if(ret < 0)
			    {
			        System.out.printf ("mfsendmsg error\n");
			        sendLock.unlock();
			        return -1;
			    }
		    	if (debug) System.out.println("after send close command");
		    	sendLock.unlock();
    		}
    		catch (Exception e) {
				e.printStackTrace();
        	}
    	}

    	return 0;
    }
}
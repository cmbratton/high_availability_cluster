package hac_client_server;

import hac_backbone.HAC;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;

/**
 * Client service to connect to a server. Is repeatedly reported
 * to about the states of all other nodes connected to the network.
 * Reverts to a server node in case of server failure (Failover)
 * @author Colby Bratton and Paul Ramberg
 *
 */
public class HACClient{

	/*
	 * HAC node with supporting information, such as local IP and
	 * port addresses, as well as server information, such as server's
	 * IP and port addresses
	 */
	private HAC clientNode;
	private String localIP;
	private int localPort;
	private String serverIP;
	private int serverPort;
	private InetAddress serverNode;
	
	// Variables to control execution of sending and receiving threads
	private boolean continueSending;
	private boolean continueReceiving;
	
	// Timer and Task to check clients connection to server
	// If server is unavailable, begin Failover process
	private Timer checkConnections;
	private TimerTask checkConnectionsTask;
	
	// Max interval to wait before updating server
	private final static int MAXUPDATEINTERVAL = 30
			* 1000;
	
	// Interval to wait before checking connection with the server
	private final static int CHECKCONNECTIONSINTERVAL = 45
			* 1000;
	
	// Interval to wait before a connection may be deemed unreachable
	private final static int TIMEOUTINTERVAL = 10
			* 1000;
	
	// Interval to wait for new server to come online (Failover)
	private final static int WAITFORSERVER = 15
			* 1000;
	
	public HACClient() {}
	
	/**
	 * Sends update packets to server at random intervals between 0 and 30
	 * seconds. If a server port is unknown, and server has the client
	 * as a previously connected node, then server's IP and port is
	 * received via HAC protocol
	 * @param localIP local IP address of client node
	 * @param localPort local port number of client node
	 * @param serverIP IP address of connected server node
	 * @param serverPort port number of connected server node
	 */
	public void begin(String localIP, int localPort, String serverIP, int serverPort)
	{
		try
		{
			this.localIP = localIP;
			this.localPort = localPort;
			
			// Create HAC node and configure it as a client connection
			clientNode = new HAC(this.localIP, localPort, HAC.CLIENT_P2P);
			
			/*
			 *  If a server's IP is not known, and client was once on the
			 *  network, wait for server to respond then retrieve its
			 *  IP and port addresses from protocol
			 */
			if (serverIP == null)
			{
				clientNode.receiveUpdatePacket();
				this.serverIP = clientNode.getRemoteIP();
				this.serverPort = clientNode.getRemotePort();
			}
			else
			{
				this.serverIP = serverIP;
				this.serverPort = serverPort;
			}
			
			// Report that client may start sending and receiving packets
			continueSending = true;
			continueReceiving = true;
			
			// Begin supporting thread to receive packets
			receivePackets.start();
			
			Random randomInterval = new Random();
			while (continueSending)
			{
				// Send packet to the server and then wait
				clientNode.updateNode(this.serverIP, this.serverPort);
				System.out.println("Sent packet");
				Thread.sleep(randomInterval.nextInt(MAXUPDATEINTERVAL));
			}
		}
		catch (InterruptedException ie)
		{
			ie.printStackTrace();
		}
	}
	
	/**
	 * Supporting thread to run continuously to receive packets from
	 * server node. Periodically calls a function to check the connection
	 * to the server node, and starts the Failover process is server is
	 * not reachable.
	 */
	private Thread receivePackets = new Thread() 
	{
		public void run()
		{	
			while (continueReceiving)
			{
				// Create and begin timer to check connection to server
				checkConnections = new Timer();
				checkConnectionsTask = new Helper();
				checkConnections.schedule(checkConnectionsTask,
						CHECKCONNECTIONSINTERVAL);
				
				// If allowed, receive packet from server
				if (continueReceiving)
				{
					clientNode.receiveUpdatePacket();
					System.out.println("Received packet");
				}
				
				checkConnections.cancel();
				checkConnections.purge();
					
				// If a new server is found, get its IP and port addresses
				// for HAC protocol 
				if (!serverIP.equals(clientNode.getRemoteIP()))
				{
					serverIP = clientNode.getRemoteIP();
					serverPort = clientNode.getRemotePort();
				}
			}
		}
	};
	
	/**
	 * If Failover handling begins, find and ping a new server
	 * node and register it the NEW current server. If this
	 * client is to become a server, stop the client's processes
	 * and roll over to become a server. 
	 */
	@SuppressWarnings("deprecation")
	private void findAndPingNewServer()
	{
		// Reports if a new server has been found
		boolean foundServer = false;
		int node = 0;
		while (!foundServer)
		{	
			try
			{
				serverNode = 
						InetAddress.getByName(clientNode.getNodeAddress(node));
				
				// If the client node to be checked is reachable, mark it as the new server
				if (serverNode.isReachable(TIMEOUTINTERVAL))
				{
					// Report that new server has been found
					foundServer = true;
					
					// If this client node is slated to be the new server, stop all
					// processes in this node
					if (clientNode.getNodeAddress(node).compareTo(localIP) == 0 &&
						clientNode.getNodePort(node) == localPort)
					{
						continueSending = false;
						receivePackets.stop();
						clientNode.terminateNode();
						clientNode = null;
					}
					// Otherwise, ping new server and reset this node for new communication
					else
					{
						System.out.println("found server");
						
						// Wait for new server to come online
						Thread.sleep(WAITFORSERVER);
						
						// Retrieve new server's information and update it
						serverIP = clientNode.getNodeAddress(node);
						serverPort = 9876;
						System.out.println("updating server");
						clientNode.updateNode(this.serverIP, 9876);
						
						// Reset client to initial state for more communication
						clientNode.clearAllNodes();
						continueReceiving = true;
						receivePackets.run();
					}
				}
				
				// If a client node is unreachable, check the next one
				node++;
			}
			catch (UnknownHostException uhe)
			{
				uhe.printStackTrace();
			}
			catch (IOException ioe)
			{
				ioe.printStackTrace();
			}
			catch (InterruptedException ie)
			{
				ie.printStackTrace();
			}
		}
	}
	
	/**
	 * Supporting timer to periodically check the connection
	 * of the client to the server, if necessary. If server is
	 * not active, the Failover process begins.
	 * @author Colby Bratton and Paul Ramberg
	 *
	 */
	private class Helper extends TimerTask
	{
		public void run()
		{
			// If the server has not updated the client in 30 seconds
			// or less, begin the failover process
			if (clientNode.getTotalNodeCount() > 0 &&
					clientNode.getActiveNodeCount() == 0)
			{
				/*
				 * Stop the client from receiving packets and 
				 * cancel this time so it is not repeated during
				 * this process. Then find a new server.
				 */
				continueReceiving = false;
				receivePackets.interrupt();
				checkConnections.cancel();
				checkConnections.purge();
				findAndPingNewServer();
			}
		}
	}
	
	public static void main(String[] args)
	{
		// Local IP of local machine
		String localIP = "192.168.0.39";
		// IP address of newly found server
		String newServer;
		
		/*
		 * Create a client and initialize it with the IP
		 * and port of the local machine, as well as provide
		 * the IP address of the server and its port
		 */
		HACClient client = new HACClient();
		client.begin(localIP, 6789, "192.168.0.39", 9876);
		client = null;
		
		/*
		 * Failover begins
		 */
		while (true)
		{
			System.out.println("Moving to server");
			
			/*
			 * Create a server and initialize it with the IP
			 * of the local machine and the standard port number
			 * for servers (in this case, it is 9876)
			 */
			HACServer failoverServer = new HACServer();
			newServer = failoverServer.begin(localIP, 9876);
			failoverServer = null;
			
			System.out.println("Moving to client");
			
			client = new HACClient();
			client.begin(localIP, 6789, newServer, 9876);
			client = null;
		}
	}
}

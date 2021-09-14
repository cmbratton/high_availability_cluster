package hac_client_server;

import hac_backbone.HAC;
import java.io.IOException;
import java.net.InetAddress;
import java.net.SocketException;
import java.net.UnknownHostException;

/**
 * Server service utilizing HAC protocol to inform Client
 * nodes of all nodes in the network, including those
 * which are currently active. Periodically checks if
 * server is connected to other nodes, and reverts to
 * a client if failover takes place.
 * @author Colby Bratton and Paul Ramberg
 *
 */
public class HACServer{
	
	// HAC Protocol object
	HAC serverNode;
		
	// Reports if server may remain active
	private boolean continueAsServer;
	// Reports if local server is the only active server
	private boolean onlyServer;
	// Used during failover, reports when a new server is located
	private boolean foundNewServer;
	// IP address of new found server, returned to main during failover
	private String newServer;
	
	// Interval at which server updates all clients, in seconds
	private final static int UPDATEINTERVAL = 30
			* 1000;
	// Interval at which server checks its connection to the network
	private final static int CHECKTIMEOUTINTERVAL = 10
			* 1000;
	// Timeout limit during connected node checks
	private final static int TIMEOUTINTERVAL = 30
			* 1000;
	
	public HACServer() {}
	
	/**
	 * Sends node update packets to each node in the network
	 * at constant intervals. Once failover occurs, checks for
	 * another server and returns server IP to main to be 
	 * intercepted by new client
	 * @param localIP IP address of the local server node
	 * @param port port number of the local server node
	 * @return IP address of new server
	 */
	public String begin(String localIP, int port)
	{
		try
		{
			// Create HAC node and initialize it as a server node
			serverNode = new HAC(localIP, port, HAC.SERVER);
			
			// Begin threads to receive packets and to periodically
			// check server's connection to the network
			receivePackets.start();
			checkConnection.start();
			
			// Report that server may remain active
			continueAsServer = true;
			
			while (continueAsServer)
			{
				Thread.sleep(UPDATEINTERVAL);
				
				// Update each node that has previously reported to the server
				for (int node = 0; node < serverNode.getTotalNodeCount(); node++)
				{
					serverNode.updateNode(serverNode.getNodeAddress(node),
							serverNode.getNodePort(node));
					System.out.println("Sent packet");
				}
				serverNode.sortNodeAndPortLists();
				serverNode.reportActiveNodes();
				serverNode.clearActiveNodes();
			}
			
			/* 
			 * Beginning of Failover Handler
			 */
			// Report that the new server has not yet been found
			foundNewServer = false;
			int nodesToPing = serverNode.getTotalNodeCount();
			while (!foundNewServer)
			{
				/*
				 *  For all nodes in network, check if they are available
				 *  to become a new server. Checks nodes in ascending
				 *  numerical order
				 */
				
				newServer = null;
				for (int node = 0; node < nodesToPing; node++)
				{
					InetAddress potentialServer =
							InetAddress.getByName(serverNode.getNodeAddress(node));
										
					// If another server has been found and may be connected to
					if (newServer == null &&
							potentialServer.isReachable(TIMEOUTINTERVAL))
					{
						// Get new server address and report it as found (stop server execution)
						newServer = serverNode.getNodeAddress(node);
						foundNewServer = true;
						
						// Terminate the current server node
						serverNode.terminateNode();
						serverNode = null;
					}
				}
			}
			
		}
		catch (InterruptedException ie)
		{
			ie.printStackTrace();
		}
		catch(IOException ioe)
		{
			ioe.printStackTrace();
		}

		return newServer;
	}
	
	/**
	 * Thread to continuously receive packets from all active client nodes
	 */
	private Thread receivePackets = new Thread() 
	{
		@SuppressWarnings("deprecation")
		public void run()
		{
			while (continueAsServer)
			{
				// Receive packet from another node, ensure that it
				// is NOT a server node
				onlyServer = serverNode.receiveUpdatePacket();
				System.out.println("Received packet");
				
				// If another server is found, stop normal execution of the
				// server and its supporting threads
				if (!onlyServer)
				{
					continueAsServer = false;
					receivePackets.stop();
					checkConnection.stop();
				}
			}
		}
	};
	
	/**
	 * Checks the connection of the server node to the network at
	 * regular intervals. If connection is lost, begin the Failover
	 * handling process (see main)
	 */
	private Thread checkConnection = new Thread() 
	{
		@SuppressWarnings("deprecation")
		public void run()
		{			
			while (continueAsServer)
			{
				try
				{
					Thread.sleep(CHECKTIMEOUTINTERVAL);
					
					int currentNodeCount = serverNode.getTotalNodeCount();
					int timeoutCounter = 0; // counter to keep track of the nodes that
											// may not be reached by the server
					
					// Check all nodes that have been connected to the network
					for (int node = 0; node < serverNode.getTotalNodeCount(); node++)
					{
						InetAddress currentNode = 
								InetAddress.getByName(serverNode.getNodeAddress(node));
						
						// If the remote node cannot be reached, state as such
						if (!currentNode.isReachable(TIMEOUTINTERVAL)) 
						{
							timeoutCounter++;
						}
					}
					
					/*
					 *  If no nodes may be reached, assume that the server has lost
					 *  connection to the network. Even if this isn't true, a reboot
					 *  of the network nodes might be necessary anyways
					 */
					
					if (timeoutCounter >= currentNodeCount &&
							currentNodeCount > 0)
					{
						// Stop normal server execution and all supporting threads
						continueAsServer = false;
						receivePackets.stop();
						checkConnection.stop();
					}
				}
				catch (SocketException se)
				{
					se.printStackTrace();
					
					// Stop normal server execution and all supporting threads
					continueAsServer = false;
					receivePackets.stop();
					checkConnection.stop();
				}
				catch (InterruptedException ie)
				{
					ie.printStackTrace();
				}
				catch (UnknownHostException uhe)
				{
					uhe.printStackTrace();
				}
				catch (IOException ioe)
				{
					ioe.printStackTrace();
				}
			}
		}
	};	
	
	public static void main(String[] args)
	{
		// IP address of the local machine
		String localIP = "192.168.0.39";
		// IP address of a newly found server, if applicable
		String newServer;
		while (true)
		{	
			/*
			 * Create a server node and initialize it on the current IP.
			 * Server nodes will always use the same port number. In this
			 * case, that is 9876 (see HACClient)
			 */
			HACServer server = new HACServer();
			newServer = server.begin(localIP, 9876);
			// If necessary, begin Failover migration
			server = null;
			
			System.out.println("Moving to client");
			
			/*
			 * Create a client node and initialize it on the current IP
			 * with an arbitrary port number. Provide the IP and port of
			 * the new server node that was created.
			 */
			HACClient rebootClient = new HACClient();
			rebootClient.begin(localIP, 2345, newServer, 9876);
			// If necessary, close client and turn back into server
			rebootClient = null;
			
			System.out.println("Moving to server");
		}
	}
}

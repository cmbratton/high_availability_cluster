package hac_backbone;

import java.awt.Dimension;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.StringTokenizer;
import java.util.Timer;
import java.util.TimerTask;
import javax.swing.*;
import javax.swing.table.DefaultTableModel;

/**
 * 
 * @author Colby Bratton and Paul Ramberg
 * @version 3/20/2021
 * Public class to implement a High Availability protocol that focuses on clusters of nodes
 * in either Peer-to-Peer format or Server-Client format. This implementation is neutral
 * and may be utilized to effectively used to implement both formats as stated above.
 * This protocol has small additionally implementations to make implement both functions
 * more simple for the developer, but does reduce the neutrality of the protocol.
 *
 */
public class HAC {

	// Socket for communication between nodes
	private DatagramSocket HACSocket = null;
	
	// Elements for node reporting GUI
	private DefaultTableModel nodeModel;
	private JTable nodeTable;
	private JScrollPane nodeListScrollPane;
	private JFrame nodeListWindow;
	private String[] columnNames = {"IP Address",
									"Port Number",
									"Status"};
	
	// Constants to refer to the configuration of current node
	// Determines if a node is a server or otherwise, and provides
	// additional functions depending on the configuration selection
	public static final int SERVER = 1;
	public static final int CLIENT_P2P = 0;
	private static int configuration;
	
	private String localIP;
	private int localPort;
	private String remoteIP;
	
	// Number of total nodes and currently active nodes in network
	private int totalNodes;
	private int activeNodes;
	
	// Holds the IP and port addresses of all nodes in network,
	// and differentiates between active and inactive nodes
	private List<String> totalNodeList;
	private List<Integer> totalPortList;
	private List<String> activeNodeList;
	private List<Integer> activePortList;
	
	// Timer and Task to update GUI with all currently active
	// and inactive nodes
	private Timer activeListTimer;
	private TimerTask clearActiveListTask;
	
	// Time at which timer's task will first execute
	private static final int STARTTIME = 30 *
			1000;
	// Interval at which timer's task will execute after initial delay
	private static final int UPDATEINTERVAL = 30 *
			1000;
	
	/**
	 * Initializes all values necessary for operation of a node,
	 * including initialization of a socket, generation of node lists,
	 * and establishing the config type of the node.
	 * @param localIP local IP address of the node
	 * @param localPort local port address of the node
	 * @param config configuration type of the current node (server or client/P2P)
	 */
	public HAC(String localIP, int localPort, int config)
	{
		try
		{
			HACSocket = new DatagramSocket(localPort);

			this.localIP = localIP;
			this.localPort = localPort;
			this.remoteIP = null;
			
			totalNodes = 0;
			activeNodes = 0;
			
			totalNodeList = new ArrayList<>();
			totalPortList = new ArrayList<>();
			activeNodeList = new ArrayList<>();
			activePortList = new ArrayList<>();
			
			if (config == SERVER)
			{
				configuration = SERVER;
			}
			else
			{
				configuration = CLIENT_P2P;
				
				// Initializes and starts timer to update GUI-based node list
				activeListTimer = new Timer();
				clearActiveListTask = new Helper();
				activeListTimer.schedule(clearActiveListTask, STARTTIME, UPDATEINTERVAL);
			}
			
			// Creates GUI-based node list on screen
			createActiveNodeWindow();
		}
		catch (SocketException se)
		{
			se.printStackTrace();
		}
	}
	
	/**
	 * Sends HAC-protocol structured packet to the requested receiver.
	 * Packet consists of packet length, sender's configuration (server or 
	 * client/P2P), the number of total nodes, the number of active nodes,
	 * and the lists of both the total nodes (IPs and ports) and the
	 * active nodes connected to the network. Packet is filled with the
	 * data of a String in order to transport data between nodes.
	 * @param receiverIP IP address of the receiving node
	 * @param receiverPort port address of the receiving node
	 */
	public void updateNode(String receiverIP, int receiverPort)
	{
		try
		{
			// Loads configuration, total node, and active node info into String
			String packetInfo = Integer.toString(configuration) + "\r\n" +
                    Integer.toString(totalNodes) + "\r\n" +
		            Integer.toString(activeNodes) + "\r\n";
			
			if (configuration == SERVER)
			{
				// Add IPs of all nodes to packet's initial String
				for (int node = 0; node < totalNodes; node++)
				{
					packetInfo = packetInfo +
							totalNodeList.get(node) + "\r\n";
				}
	
				// Add ports of all nodes
				for (int port = 0; port < totalNodes; port++)
				{
					packetInfo = packetInfo +
							Integer.toString(totalPortList.get(port)) + "\r\n";
				}
	
				// Add IPs of all ACTIVE nodes
				for (int node = 0; node < activeNodes; node++)
				{
					packetInfo = packetInfo +
							activeNodeList.get(node) + "\r\n";
				}
	
				// Add ports of all ACTIVE nodes
				for (int port = 0; port < activeNodes; port++)
				{
					packetInfo = packetInfo +
							Integer.toString(activePortList.get(port)) + "\r\n";
				}
			}

			// Get length of the packet and store it in packet String
			int packetLength = packetInfo.getBytes().length;
			packetInfo = Integer.toString(packetLength) + "\r\n" + 
			             packetInfo;

			// Get binary data of packet String
			byte[] packetData = packetInfo.getBytes();
			
			// Create address variable of remote node
			InetAddress remote = InetAddress.getByName(receiverIP);
	
			// Create a UDP packet using the packet information and receivers information
			// and send packet to recipient.
			DatagramPacket informNodesPacket = new DatagramPacket(packetData,
					packetData.length, remote, receiverPort);
			HACSocket.send(informNodesPacket);
		}
		catch (IOException ioe)
		{
			ioe.printStackTrace();
		}
	}
	
	/**
	 * Receives HAC-protocol structured packet from socket, assuming one was
	 * sent by a remote node. Contains information such as packet length,
	 * the sender's configuration setting, the number of total nodes and 
	 * active nodes in the network, and list of all total nodes and all
	 * active nodes, if applicable. Received packet is taken is as a String
	 * and tokenized accordingly.
	 * @return server exclusivity. If the packet was received and parsed
	 *         successfully (intervention by no other servers), then true
	 *         is returned. If there are two or more servers active at once,
	 *         false is returned for the servers that are intended to close.
	 */
	public boolean receiveUpdatePacket()
	{
		try
		{
			// Allocate memory to hold new packet
			byte[] incomingData = new byte[1024];
			
			// Create an empty UDP packet and receive packet from wire
			DatagramPacket incomingPacket = new DatagramPacket(incomingData,
					incomingData.length);
			HACSocket.receive(incomingPacket);
												
			// Retrieve the sender's IP and port address from packet
			String sendingIP = incomingPacket.getAddress().getHostAddress();
			int sendingPort = incomingPacket.getPort();
			
			// Create tokenizer to parse packet String
			String packetInfo = new String(incomingPacket.getData());
			StringTokenizer tokenizer = new StringTokenizer(packetInfo, "\r\n");
			
			@SuppressWarnings("unused")
			// Packet length is unused in this implementation, may be utilized in
			// future variations
			int packetLength = Integer.parseInt(tokenizer.nextToken());
			
			// Parse out the configuration setting of the sender
			int senderConfig = Integer.parseInt(tokenizer.nextToken());
			
			if (configuration == SERVER &&
					senderConfig == SERVER)
			{
				// If two servers are active, close the server whose IP address
				// is lower on the node list
				if (sendingIP.compareTo(localIP) < 0)
				{
					return false; // NO server exclusivity
				}
			}
			
			// If receiving a packet from a server
			if (senderConfig == SERVER)
			{
				// Set remote IP as server's IP
				remoteIP = sendingIP;
				
				// Parse total and active nodes from packet
				totalNodes = Integer.parseInt(tokenizer.nextToken());
				activeNodes = Integer.parseInt(tokenizer.nextToken());
				
				// List to hold updated total node information
				List<String> newTotalNodeIPs = new ArrayList<>();
				List<Integer> newTotalNodePorts = new ArrayList<>();
				
				// Parse IPs of all nodes into temporary list
				for (int i = 0; i < totalNodes; i++)
				{
					String nodeIP = tokenizer.nextToken();
					newTotalNodeIPs.add(nodeIP);
				}
				
				// Copy temporary IP list to permanent IP list
				totalNodeList = new ArrayList<>(newTotalNodeIPs);
				
				// Parse ports of all nodes into temporary list
				for (int i = 0; i < totalNodes; i++)
				{
					int nodePort = Integer.parseInt(tokenizer.nextToken());
					newTotalNodePorts.add(nodePort);
				}
				
				// Copy temporary port list to permanent port list
				totalPortList = new ArrayList<>(newTotalNodePorts);
				
				// Parse IPs of active nodes into temporary list
				for (int i = 0; i < activeNodes; i++)
				{
					String nodeIP = tokenizer.nextToken();
					activeNodeList.add(nodeIP);
				}
				
				// Parse ports of active nodes into temporary list
				for (int i = 0; i < activeNodes; i++)
				{
					int nodePort = Integer.parseInt(tokenizer.nextToken());
					activePortList.add(nodePort);
				}
			}
			
			// If sender's IP address and port number are not in list of active nodes,
			// then add it and report the extra node
			if (!activeNodeList.contains(sendingIP) ||
				 activeNodeList.contains(sendingIP) && !activePortList.contains(sendingPort))
			{
				activeNodeList.add(sendingIP);
				activePortList.add(sendingPort);
				activeNodes++;
						
				// If sender's IP address and port number are not in list of total
				// nodes, then addit and report extra node
				if (!totalNodeList.contains(sendingIP) ||
					 totalNodeList.contains(sendingIP) && !totalPortList.contains(sendingPort))
				{
					totalNodeList.add(sendingIP);
					totalPortList.add(sendingPort);
					totalNodes++;
				}
				else if (senderConfig == SERVER)
				{
					// If receiving information from a server node, increment totalNodes
					// as servers do NOT report themselves in total or active node lists
					// (IP and port information received via embedded packet info)
					totalNodes++;
				}
			}
			
			// Sort both total and active IP/port lists
			this.sortNodeAndPortLists();
		}
		catch (IOException ioe)
		{
			ioe.printStackTrace();
		}
		
		// If packet was received and parsed successfully, and another server was not
		// located, report as such.
		return true;
	}
	
	/**
	 * Clears GUI-based node list and fill it with information about all
	 * nodes connected to the network and inform user if the node is active
	 * or not (Online/Offline)
	 */
	public void reportActiveNodes()
	{
		// Clear all previous information from table
		if (nodeModel.getRowCount() > 0)
		{
			for (int rows = nodeModel.getRowCount() - 1; rows > -1; rows--)
			{
				nodeModel.removeRow(rows);
			}
		}
		
		// tracks the number of nodes that are currently offline
		int offlineNodes = 0;
		
		// Report all nodes as active or inactive
		for (int node = 0; node < totalNodes; node++)
		{
			// If a node is located in the active node list, report it as active
			if (activeNodeList.contains(totalNodeList.get(node)) &&
				activePortList.get(node - offlineNodes).equals(totalPortList.get(node)))
			{	
				// If the current node is associated with the local machine, 
				// report as such
				if (localIP.compareTo(totalNodeList.get(node)) == 0 &&
					localPort == totalPortList.get(node))
				{
					Object[] currentNodeInfo = {totalNodeList.get(node),
							                    totalPortList.get(node),
	                                            "Online - Local"};

					nodeModel.addRow(currentNodeInfo);
				}
				else
				{
					Object[] currentNodeInfo = {totalNodeList.get(node),
												totalPortList.get(node),
						                        "Online"};
				
					nodeModel.addRow(currentNodeInfo);
				}
			}
			// If a node is NOT in the active node list, but is in total node list,
			// report it as inactive
			else
			{
				Object[] currentNodeInfo = {totalNodeList.get(node),
											totalPortList.get(node),
											"Offline"};
				
				nodeModel.addRow(currentNodeInfo);
				
				// Report that this node is currently offline
				offlineNodes++;
			}			
		}
		
		// Update GUI-based node list
		nodeTable.setModel(nodeModel);
	}
	
	/**
	 * Used for Debugging ONLY!
	 * Reports the number of total nodes and active nodes in the network,
	 * as well as the IP and port addresses of all active nodes
	 */
	public void reportActiveNodesAndPorts()
	{
		System.out.println("Currently Active Nodes:");
		for (int node = 0; node < activeNodes; node++)
		{
			System.out.println(totalNodes);
			System.out.println(activeNodes);
			System.out.println(activeNodeList.get(node));
			System.out.println(activePortList.get(node));
		}
	}
	
	/**
	 * Sorts both total and active node lists (and their respective
	 * port lists) in ascending order. Utilizes bubble sort
	 */
	public void sortNodeAndPortLists()
	{
		// Sort total node list
		for (int node = 0; node < totalNodes; node++)
		{
			for (int secNode = 0; secNode < totalNodes - node - 1; secNode++)
			{
				// If current node in list is larger than next node in list, swap them
				if (totalNodeList.get(secNode).compareTo(totalNodeList.get(secNode + 1)) > 0)
				{
					Collections.swap(totalNodeList, secNode, secNode + 1);
					Collections.swap(totalPortList, secNode, secNode + 1);
				}
				// If port of current node in list is larger than next port of the node, swap them
				else if (totalNodeList.get(secNode).compareTo(totalNodeList.get(secNode + 1)) == 0 &&
						 totalPortList.get(secNode) > totalPortList.get(secNode + 1))
				{
					Collections.swap(totalPortList, secNode, secNode + 1);
				}
			}
		}
		
		// Sort active node list
		for (int node = 0; node < activeNodes; node++)
		{
			for (int secNode = 0; secNode < activeNodes - node - 1; secNode++)
			{
				// If current node in list is larger than next node in list, swap them
				if (activeNodeList.get(secNode).compareTo(activeNodeList.get(secNode + 1)) > 0)
				{
					Collections.swap(activeNodeList, secNode, secNode + 1);
					Collections.swap(activePortList, secNode, secNode + 1);
				}
				// If port of current node in list is larger than next port of the node, swap them
				else if (activeNodeList.get(secNode).compareTo(activeNodeList.get(secNode + 1)) == 0 &&
						 activePortList.get(secNode) > activePortList.get(secNode + 1))
				{
					Collections.swap(activePortList, secNode, secNode + 1);
				}
			}
		}
	}
	
	/**
	 * Generates a GUI-based table used to report all nodes connected
	 * to the network, and reports their information and if they are
	 * connected or not connected
	 */
	private void createActiveNodeWindow()
	{
		// Generate table model
		nodeModel = new DefaultTableModel(columnNames, 0);
		
		// Generates table with current table model (empty table)
		nodeTable = new JTable(nodeModel);
		nodeTable.setPreferredScrollableViewportSize(new Dimension(500, 150));
		nodeTable.setFillsViewportHeight(true);
		
		// Place new table in frame component
		nodeListScrollPane = new JScrollPane(nodeTable);
		
		// Report nodes IP, and if it is a server, in the frame's header
		if (configuration == SERVER)
		{
			nodeListWindow = new JFrame("Active Nodes for Server: " + localIP);
		}
		else
		{
			nodeListWindow = new JFrame("Active Nodes for: " + localIP);
		}
		nodeListWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		
		// Place GUI on the screen
		nodeListScrollPane.setOpaque(true);
		nodeListWindow.setContentPane(nodeListScrollPane);
		
		nodeListWindow.pack();
		nodeListWindow.setVisible(true);
	}
	
	/**
	 * Clears active node and port lists and sets active node
	 * value to zero
	 */
	public void clearActiveNodes()
	{
		activeNodes = 0;
		activeNodeList.clear();
		activePortList.clear();
	}
	
	/**
	 * Clears total and active node and port lists and sets
	 * node values to zero 
	 */
	public void clearAllNodes()
	{
		totalNodes = 0;
		totalNodeList.clear();
		totalPortList.clear();
		activeNodes = 0;
		activeNodeList.clear();
		activePortList.clear();
	}
	
	/**
	 * Shuts down a node by closing current socket and
	 * removing all GUI components from screen. Also
	 * terminates any active timers 
	 */
	public void terminateNode()
	{
		HACSocket.close();
		
		if (configuration == CLIENT_P2P)
		{
			activeListTimer.cancel();
			activeListTimer.purge();
			activeListTimer = null;
			clearActiveListTask = null;
		}
		
		nodeListWindow.dispose();
		nodeModel = null;
		nodeTable = null;
		nodeListScrollPane = null;
		nodeListWindow = null;
	}
	
	/**
	 * Adds requested IP address and port number to total
	 * node list
	 * @param ipAddress IP address to be added to list
	 * @param port port number to be added to list
	 */
	public void addNodeToTotalNodes(String ipAddress, int port)
	{
		totalNodeList.add(ipAddress);
		totalPortList.add(port);
		totalNodes++;
	}
	
	/**
	 * Returns IP of remote node (server node or otherwise)
	 * @return IP address of current remote node
	 */
	public String getRemoteIP()
	{
		return remoteIP;
	}
	
	/**
	 * Returns port of remote node (server node or otherwise)
	 * @return port number of current remote node
	 */
	public int getRemotePort()
	{
		int portIndex = activeNodeList.indexOf(remoteIP);
		return activePortList.get(portIndex);
	}
	
	/**
	 * Returns total node count of current HAC node
	 * @return total node count
	 */
	public int getTotalNodeCount()
	{
		return totalNodes;
	}
	
	/**
	 * Returns active node count of current HAC node
	 * @return active node count
	 */
	public int getActiveNodeCount()
	{
		return activeNodes;
	}
	
	/**
	 * Returns IP address of requested element of total node list
	 * @param index index of IP address to be returned
	 * @return IP address at specified index
	 */
	public String getNodeAddress(int index)
	{
		return totalNodeList.get(index);
	}
	
	/**
	 * Returns port number of requested element of total node list
	 * @param index index of port number to be returned
	 * @return port number at specified index
	 */
	public int getNodePort(int index)
	{
		return totalPortList.get(index);
	}
	
	/**
	 * Returns IP address of requested element of active node list
	 * @param index index of IP address to be returned
	 * @return IP address at specified index
	 */
	public String getActiveNodeAddress(int index)
	{
		return activeNodeList.get(index);
	}
	
	/**
	 * Returns port number of requests element of active node list
	 * @param index index of port number to be returned
	 * @return IP address at specified index
	 */
	public int getActivePortAddress(int index)
	{
		return activePortList.get(index);
	}
	
	/**
	 * Extends TimerTask, used to periodically update GUI-based
	 * node list, sort node lists, and reset active nodes
	 * @author Colby Bratton and Paul Ramberg
	 *
	 */
	private class Helper extends TimerTask
	{
		public void run()
		{
			sortNodeAndPortLists();
			reportActiveNodes();
			clearActiveNodes();
		}
	}
}

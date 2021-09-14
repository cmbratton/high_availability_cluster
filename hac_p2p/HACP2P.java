package hac_p2p;

import hac_backbone.HAC;
import java.io.File;
import java.io.FileNotFoundException;
import java.util.Random;
import java.util.Scanner;
import java.util.StringTokenizer;

/**
 * P2P service using HAC protocol. Informs all nodes
 * provided in a configuration file of the nodes activity.
 * Provides this update at random intervals between 0 and
 * 30 seconds. Reports the activity of all subsequent nodes as well.
 * @author Colby Bratton and Paul Ramberg
 *
 * NOTE: The configuration file has a particular layout in order
 * to be used with this service. Each line must contain, first,
 * the IP address of a node and, second, its port number. These
 * must be separated by a single comma and must NOT contain spaces.
 * The next line will contain information for the next node, and so on.
 * See the provided P2Pclients.txt file for examples of this. Modify it
 * appropriately to work on your system.
 */
public class HACP2P{

	private HAC P2PNode;
		
	private final static int MAXUPDATEINTERVAL = 30 * 1000;
	
	public HACP2P() {}
		
	public void begin(String localIP, int port)
	{
		try
		{
			// Initialize node
			this.P2PNode = new HAC(localIP, port, HAC.CLIENT_P2P);
			
			// Read info from the config file and add them to the list of nodes
			File nodeFile = new File("P2Pclients.txt");
			Scanner scanner = new Scanner(nodeFile);
			while(scanner.hasNext()) {
				String line = scanner.nextLine();
				StringTokenizer tokenizer = new StringTokenizer(line, ",");
				
				String nodeIPAddress = tokenizer.nextToken();
				int nodePort = Integer.parseInt(tokenizer.nextToken());
				P2PNode.addNodeToTotalNodes(nodeIPAddress, nodePort);
			}
			scanner.close();
			
			// Start receiving packets
			this.receivePackets.start();
			
			// At a random interval send a packet to all nodes in the list
			Random randomInterval = new Random();
			while (true)
			{
				for (int index = 0; index < P2PNode.getTotalNodeCount(); index ++) {
					P2PNode.updateNode(P2PNode.getNodeAddress(index), P2PNode.getNodePort(index));
				}
				
				Thread.sleep(randomInterval.nextInt(MAXUPDATEINTERVAL));
			}
			
		}
		catch (InterruptedException | FileNotFoundException ie)
		{
			ie.printStackTrace();
		}
	}
	
	/**
	 * Supporting thread to continuously receive packets from
	 * all other active P2P nodes on the network
	 */
	private Thread receivePackets = new Thread()
	{
		public void run()
		{
			while (true)
			{
				P2PNode.receiveUpdatePacket();
			}
		}
	};
}
package hac_p2p;

/**
 * Driver class to demonstrate the abilities of P2P nodes.
 * Generates three threads to display one P2P node. In
 * order to make all nodes function separately (not to
 * call all at once), multiple mains must be utilized
 * to generate each node.
 * @author Colby Bratton and Paul Ramberg
 *
 */
public class HACP2PDriver {

	public static void main(String[] args)
	{		
		p2.start();
		p3.start();
		HACP2P peer1 = new HACP2P();
		peer1.begin("192.168.0.39", 9876);
	}
	
	public static Thread p2 = new Thread()
	{
		public void run()
		{
			HACP2P peer2 = new HACP2P();
			peer2.begin("192.168.0.39", 9875);
		}
	};
	
	public static Thread p3 = new Thread()
	{
		public void run()
		{
			HACP2P peer3 = new HACP2P();
			peer3.begin("192.168.0.39", 9874);
		}
	};
}

package src.com.ringoram;

public class Configs {
	// thread fixed number
	public static int THREAD_FIXED = 4;

	// server host name and port
	public static String SERVER_HOSTNAME = "localhost";
	public static int SERVER_PORT = 12340;
	
	//block data length
	public static int BLOCK_DATA_LEN = 64;
	//the max real block count in the bucket
	public static int REAL_BLOCK_COUNT = 4;
	//the min dummy block count in the bucket
	public static int DUMMY_BLOCK_COUNT = 6;
	//total bucket count in the tree, must be full binary tree
	public static int BUCKET_COUNT =4;
	
	//total block count in bucket
	public static int Z = REAL_BLOCK_COUNT + DUMMY_BLOCK_COUNT;
	//total block count in the tree
	public static int BLOCK_COUNT = BUCKET_COUNT * REAL_BLOCK_COUNT;
	//tree height
	public static int HEIGHT = (int) (Math.log(BUCKET_COUNT)/Math.log(2) + 1);
	//total leaf count in the tree
	public static int LEAF_COUNT = (BUCKET_COUNT+1)/2;
	//leaf start index in tree node(root is 0)
	public static int LEAF_START = BUCKET_COUNT - LEAF_COUNT;
	
	//read_counter, meta_buf, valid_bits
	public static int METADATA_BYTES_LEN = 4+4*(Configs.REAL_BLOCK_COUNT + Configs.Z)+Configs.Z;
	
	//shuffle rate
	public static int SHUFFLE_RATE = 4;
	//public static final int BLOCK_DATA_LEN = 64; // for example; must be a multiple of 16
	//request operation: read or write
	public enum OPERATION{ORAM_ACCESS_READ,ORAM_ACCESS_WRITE};
	
	//bucket store source
	public static String STORAGE_PATH = "/Users/rajendrathalluru/Documents";
	
	// AES key (16 bytes for AES-128)
	public static final byte[] KEY = new byte[] {
	    0x00, 0x01, 0x02, 0x03,
	    0x04, 0x05, 0x06, 0x07,
	    0x08, 0x09, 0x0A, 0x0B,
	    0x0C, 0x0D, 0x0E, 0x0F
	};

}

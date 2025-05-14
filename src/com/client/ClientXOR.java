// XOR-based ORAM Client with AES Encryption
package src.com.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;

import src.com.ringoram.*;
import src.com.ringoram.Configs.OPERATION;

public class ClientXOR implements ClientInterface {

    private static int requestID = 0;

    protected InetSocketAddress serverAddress;
    protected AsynchronousChannelGroup mThreadGroup;
    protected AsynchronousSocketChannel mChannel;

    private int evict_count;
    private int evict_g;
    private int[] position_map;

    Stash stash;
    ByteSerialize seria;
    MathUtility math;

    private final Cipher cipherEncrypt;
    private final Cipher cipherDecrypt;

    public ClientXOR() {
        this.evict_count = 0;
        this.evict_g = 0;
        this.position_map = new int[Configs.BLOCK_COUNT];
        this.stash = new Stash();
        this.seria = new ByteSerialize();
        this.math = new MathUtility();

        try {
            SecretKeySpec keySpec = new SecretKeySpec(Configs.KEY, "AES");
            cipherEncrypt = Cipher.getInstance("AES/ECB/NoPadding");
            cipherEncrypt.init(Cipher.ENCRYPT_MODE, keySpec);
            cipherDecrypt = Cipher.getInstance("AES/ECB/NoPadding");
            cipherDecrypt.init(Cipher.DECRYPT_MODE, keySpec);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Failed to initialize AES cipher", e);
        }

        for (int i = 0; i < Configs.BLOCK_COUNT; i++) {
            this.position_map[i] = math.getRandomLeaf() + Configs.LEAF_START;
        }

        try {
            serverAddress = new InetSocketAddress(Configs.SERVER_HOSTNAME, Configs.SERVER_PORT);
            mThreadGroup = AsynchronousChannelGroup.withFixedThreadPool(Configs.THREAD_FIXED,
                    Executors.defaultThreadFactory());
            mChannel = AsynchronousSocketChannel.open(mThreadGroup);
            Future<?> connection = mChannel.connect(serverAddress);
            connection.get();
            System.out.println("ClientXOR connected to server.");
            initServer();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private byte[] encrypt(byte[] data) {
        try {
            return cipherEncrypt.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    private byte[] decrypt(byte[] data) {
        try {
            return cipherDecrypt.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new RuntimeException("Decryption failed", e);
        }
    }

    public byte[] sendAndGetMessage(ByteBuffer requestBuffer, int messageType) {
        byte[] responseBytes = null;
        try {
            while (requestBuffer.remaining() > 0) {
                Future<?> requestBufferRead = mChannel.write(requestBuffer);
                requestBufferRead.get();
            }

            ByteBuffer typeAndSize = ByteBuffer.allocate(8);
            Future<?> typeAndSizeRead = mChannel.read(typeAndSize);
            typeAndSizeRead.get();
            typeAndSize.flip();
            int[] typeAndSizeInt = MessageUtility.parseTypeAndLength(typeAndSize);
            int type = typeAndSizeInt[0];
            int size = typeAndSizeInt[1];

            ByteBuffer responseBuffer = ByteBuffer.allocate(size);
            while (responseBuffer.remaining() > 0) {
                Future<?> responseBufferRead = mChannel.read(responseBuffer);
                responseBufferRead.get();
            }
            responseBuffer.flip();

            if (type == messageType) {
                responseBytes = new byte[size];
                responseBuffer.get(responseBytes);
            } else {
                System.out.println("ClientXOR received unexpected message type.");
            }
        } catch (Exception e) {
            try {
                mChannel.close();
            } catch (IOException e1) {
                e1.printStackTrace();
            }
            e.printStackTrace();
        }
        return responseBytes;
    }

    public void initServer() {
        ByteBuffer header = MessageUtility.createMessageHeaderBuffer(MessageUtility.ORAM_INIT, 0);
        byte[] responseBytes = sendAndGetMessage(header, MessageUtility.ORAM_INIT);
        System.out.println("client INIT server successful!" + responseBytes[0]);
    }

    public byte[] oblivious_access(int blockIndex, OPERATION op, byte[] newdata) {
        requestID++;
        System.out.println("\n[Request #" + requestID + "] Operation: " + op + ", Block: " + blockIndex);

        byte[] readData = null;

        int position = position_map[blockIndex];
        System.out.println("Current position map for block " + blockIndex + ": " + position);

        int position_new = math.getRandomLeaf() + Configs.LEAF_START;
        System.out.println("New position assigned to block " + blockIndex + ": " + position_new);

        position_map[blockIndex] = position_new;

        read_path(position, blockIndex);
        System.out.println("Finished reading path " + position + " into stash.");
        System.out.println("Stash content after read: " + stash);

        Block block = stash.find_by_blockIndex(blockIndex);

        if (op == OPERATION.ORAM_ACCESS_WRITE) {
            if (block == null) {
                block = new Block(blockIndex, position_new, newdata);
                stash.add(block);
            } else {
                block.setData(newdata);
                block.setLeaf_id(position_new);
            }
            System.out.println("Block " + blockIndex + " updated in stash with new data.");
            readData = block.getData();
        }

        if (op == OPERATION.ORAM_ACCESS_READ) {
            if (block != null) {
                System.out.println("when read block " + blockIndex + " find block in the stash.");
                readData = block.getData();
            }
            System.out.println("Reading data for block " + blockIndex + " from stash.");
        }

        evict_count = (evict_count + 1) % Configs.SHUFFLE_RATE;
        if (evict_count == 0) {
            evict_path(math.gen_reverse_lexicographic(evict_g, Configs.BUCKET_COUNT, Configs.HEIGHT));
            evict_g = (evict_g + 1) % Configs.LEAF_COUNT;
        }
        System.out.println("Eviction count: " + evict_count + ", next eviction index: " + evict_g);

        BucketMetadata[] meta_list = get_metadata(position);
        early_reshuffle(position, meta_list);

        System.out.println("Path from root to leaf " + position + ":");
        int node = position;
        while (node >= 0) {
            System.out.print(" -> " + node);
            node = (node - 1) >> 1;
        }
        System.out.println();

        return readData;
    }

    @Override
    public void read_path(int pathID, int blockIndex) {
        BucketMetadata[] meta_list = get_metadata(pathID);
        read_block(pathID, blockIndex, meta_list);
    }

    public BucketMetadata[] get_metadata(int pathID) {
        byte[] pos = Ints.toByteArray(pathID);
        byte[] header = MessageUtility.createMessageHeaderBytes(MessageUtility.ORAM_GETMETA, pos.length);
        ByteBuffer requestBuffer = ByteBuffer.wrap(Bytes.concat(header, pos));
        byte[] responseBytes = sendAndGetMessage(requestBuffer, MessageUtility.ORAM_GETMETA);

        BucketMetadata[] meta_list = new BucketMetadata[Configs.HEIGHT];
        int startIndex = 0;
        int index = 0;
        for (int pos_run = pathID; pos_run >= 0; pos_run = (pos_run - 1) >> 1) {
            byte[] meta_bytes = Arrays.copyOfRange(responseBytes, startIndex, startIndex + Configs.METADATA_BYTES_LEN);
            meta_list[index] = seria.metadataFromSerialize(meta_bytes);
            startIndex += Configs.METADATA_BYTES_LEN;
            index++;
            if (pos_run == 0) break;
        }
        return meta_list;
    }

    public void read_block(int pathID, int blockIndex, BucketMetadata[] meta_list) {
        boolean found = false;
        int[] read_offset = new int[Configs.HEIGHT];

        for (int i = 0, pos_run = pathID; pos_run >= 0; pos_run = (pos_run - 1) >> 1, i++) {
            if (found) {
                read_offset[i] = math.get_random_dummy(meta_list[i].getValid_bits(), meta_list[i].get_offset());
            } else {
                for (int j = 0; j < Configs.REAL_BLOCK_COUNT; j++) {
                    int offset = meta_list[i].get_offset()[j];
                    if ((meta_list[i].get_block_index()[j] == blockIndex) && (meta_list[i].getValid_bits()[offset] == 1)) {
                        read_offset[i] = offset;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    read_offset[i] = math.get_random_dummy(meta_list[i].getValid_bits(), meta_list[i].get_offset());
                }
            }
            if (pos_run == 0) break;
        }

        byte[] read_offset_bytes = new byte[Configs.HEIGHT * 4];
        for (int i = 0; i < Configs.HEIGHT; i++) {
            System.arraycopy(Ints.toByteArray(read_offset[i]), 0, read_offset_bytes, i * 4, 4);
        }

        byte[] pos_bytes = Ints.toByteArray(pathID);
        byte[] requestBytes = Bytes.concat(pos_bytes, read_offset_bytes);
        byte[] header = MessageUtility.createMessageHeaderBytes(MessageUtility.ORAM_READBLOCK, requestBytes.length);
        ByteBuffer requestBuffer = ByteBuffer.wrap(Bytes.concat(header, requestBytes));
        byte[] responseBytes = sendAndGetMessage(requestBuffer, MessageUtility.ORAM_READBLOCK);

        if (found) {
            byte[] decrypted = decrypt(responseBytes);
            Block blk = new Block(blockIndex, pathID, decrypted);
            stash.add(blk);
        }
    }

    public void evict_path(int pathID) {
        for (int pos_run = pathID; pos_run >= 0; pos_run = (pos_run - 1) >> 1) {
            read_bucket(pos_run);
            if (pos_run == 0) break;
        }
        for (int pos_run = pathID; pos_run >= 0; pos_run = (pos_run - 1) >> 1) {
            write_bucket(pos_run);
            if (pos_run == 0) break;
        }
    }

    public void read_bucket(int bucket_id) {
        byte[] bucket_id_bytes = Ints.toByteArray(bucket_id);
        byte[] header = MessageUtility.createMessageHeaderBytes(
                MessageUtility.ORAM_READBUCKET, bucket_id_bytes.length);
        ByteBuffer requestBuffer = ByteBuffer.wrap(Bytes.concat(header, bucket_id_bytes));
        byte[] responseBytes = sendAndGetMessage(requestBuffer, MessageUtility.ORAM_READBUCKET);

        Bucket bucket = seria.bucketFromSerialize(responseBytes);
        BucketMetadata meta = bucket.getBucket_meta();

        int[] block_index = meta.get_block_index();
        int[] offset = meta.get_offset();
        byte[] valid_bits = meta.getValid_bits();
        for (int i = 0; i < Configs.REAL_BLOCK_COUNT; i++) {
            if ((block_index[i] >= 0) && (valid_bits[offset[i]] == 1)) {
                byte[] enc_data = bucket.getBlock(offset[i]);
                byte[] data = decrypt(enc_data);
                stash.add(new Block(block_index[i], position_map[block_index[i]], data));
            }
        }
    }

    public void write_bucket(int bucket_id) {
        BucketMetadata meta = new BucketMetadata();
        Block[] block_list = new Block[Configs.REAL_BLOCK_COUNT];
        int count = stash.remove_by_bucket(bucket_id, Configs.REAL_BLOCK_COUNT, block_list);

        meta.set_offset(math.get_random_permutation(Configs.Z));
        int[] offset = meta.get_offset();
        byte[] bucket_data = new byte[Configs.Z * Configs.BLOCK_DATA_LEN];

        for (int i = 0; i < count; i++) {
            int offset_i = offset[i] * Configs.BLOCK_DATA_LEN;
            byte[] block_data = encrypt(block_list[i].getData());
            System.arraycopy(block_data, 0, bucket_data, offset_i, Configs.BLOCK_DATA_LEN);
            if (i < Configs.REAL_BLOCK_COUNT) {
                meta.set_blockIndex_bit(i, block_list[i].getBlockIndex());
            }
        }

        for (int i = count; i < Configs.Z; i++) {
            int offset_i = offset[i] * Configs.BLOCK_DATA_LEN;
            Arrays.fill(bucket_data, offset_i, offset_i + Configs.BLOCK_DATA_LEN, (byte) 0);
            if (i < Configs.REAL_BLOCK_COUNT) {
                meta.set_blockIndex_bit(i, -1);
            }
        }

        Bucket bucket = new Bucket(bucket_id, bucket_data, meta);
        byte[] bucket_bytes = seria.bucketSerialize(bucket);
        byte[] header = MessageUtility.createMessageHeaderBytes(
                MessageUtility.ORAM_WRITEBUCKET, bucket_bytes.length);
        ByteBuffer requestBuffer = ByteBuffer.wrap(Bytes.concat(header, bucket_bytes));
        sendAndGetMessage(requestBuffer, MessageUtility.ORAM_WRITEBUCKET);
    }

    @Override
    public void early_reshuffle(int pathID, BucketMetadata[] meta_list) {
        for (int pos_run = pathID, i = 0; pos_run >= 0; pos_run = (pos_run - 1) >> 1, i++) {
            if (meta_list[i].getRead_counter() >= (Configs.DUMMY_BLOCK_COUNT - 2)) {
                read_bucket(pos_run);
                write_bucket(pos_run);
            }
            if (pos_run == 0) break;
        }
    }

    public void printPositionMap() {
        for (int i = 0; i < position_map.length; i++) {
            System.out.println("  Block " + i + " -> Leaf " + position_map[i]);
        }
    }

    public static void main(String[] args) {
        ClientXOR client = new ClientXOR();
        client.initServer();

        client.printPositionMap();

        for (int i = 0; i < 4; i++) {
            byte[] data = new byte[Configs.BLOCK_DATA_LEN];
            Arrays.fill(data, (byte) i);
            client.oblivious_access(i, OPERATION.ORAM_ACCESS_WRITE, data);
        }
        client.printPositionMap();

        byte[] newdata = new byte[Configs.BLOCK_DATA_LEN];
        Arrays.fill(newdata, (byte) 12);
        client.oblivious_access(3, OPERATION.ORAM_ACCESS_WRITE, newdata);
        client.printPositionMap();

        for (int i = 0; i < 4; i++) {
            byte[] data = client.oblivious_access(i, OPERATION.ORAM_ACCESS_READ, new byte[Configs.BLOCK_DATA_LEN]);
            if (data != null) {
                System.out.println("block " + i + " data:");
                for (byte b : data) System.out.print(b + " ");
                System.out.println();
            } else {
                System.out.println("can't find block " + i + " in server storage");
            }
        }
        client.printPositionMap();
    }
}

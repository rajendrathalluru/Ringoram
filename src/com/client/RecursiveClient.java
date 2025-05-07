// RecursiveClient.java (Self-contained Recursive ORAM without dependency on Client)
package src.com.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.Arrays;
import java.util.concurrent.*;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;

import src.com.ringoram.*;
import src.com.ringoram.Configs.OPERATION;

public class RecursiveClient implements ClientInterface {

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

    public RecursiveClient() {
        this.evict_count = 0;
        this.evict_g = 0;
        this.position_map = new int[Configs.BLOCK_COUNT];
        this.stash = new Stash();
        this.seria = new ByteSerialize();
        this.math = new MathUtility();

        for (int i = 0; i < Configs.BLOCK_COUNT; i++) {
            this.position_map[i] = math.getRandomLeaf() + Configs.LEAF_START;
        }

        try {
            serverAddress = new InetSocketAddress(Configs.SERVER_HOSTNAME, Configs.SERVER_PORT);
            mThreadGroup = AsynchronousChannelGroup.withFixedThreadPool(Configs.THREAD_FIXED, Executors.defaultThreadFactory());
            mChannel = AsynchronousSocketChannel.open(mThreadGroup);
            mChannel.connect(serverAddress).get();
            System.out.println("RecursiveClient connected to server.");
            initServer();
        } catch (Exception e) {
            System.err.println("[Fatal] Broken pipe or IO error during communication.");
        }
    }

    public void initServer() {
        ByteBuffer header = MessageUtility.createMessageHeaderBuffer(MessageUtility.ORAM_INIT, 0);
        byte[] response = sendAndGetMessage(header, MessageUtility.ORAM_INIT);
        System.out.println("INIT server response: " + Arrays.toString(response));
    }

    public byte[] sendAndGetMessage(ByteBuffer buffer, int expectType) {
        try {
            while (buffer.remaining() > 0) mChannel.write(buffer).get();
            ByteBuffer header = ByteBuffer.allocate(8);
            mChannel.read(header).get();
            header.flip();
            if (header.remaining() < 8) return new byte[0];
            int[] typeLen = MessageUtility.parseTypeAndLength(header);
            ByteBuffer body = ByteBuffer.allocate(typeLen[1]);
            mChannel.read(body).get();
            return body.array();
        } catch (Exception e) {
            return new byte[0];
        }
    }

    public byte[] oblivious_access(int blockIndex, OPERATION op, byte[] newdata) {
        requestID++;
        System.out.println("[RecursiveClient] Processing request " + requestID);

        byte[] readData = null;

        int position = position_map[blockIndex];
        int newPos = math.getRandomLeaf() + Configs.LEAF_START;
        position_map[blockIndex] = newPos;

        read_path(position, blockIndex);
        Block block = stash.find_by_blockIndex(blockIndex);

        if (op == OPERATION.ORAM_ACCESS_WRITE) {
            if (block == null) {
                block = new Block(blockIndex, newPos, newdata);
                stash.add(block);
            } else {
                block.setData(newdata);
                block.setLeaf_id(newPos);
            }
            readData = block.getData();
        }

        if (op == OPERATION.ORAM_ACCESS_READ && block != null) {
            readData = block.getData();
        }

        evict_count = (evict_count + 1) % Configs.SHUFFLE_RATE;
        if (evict_count == 0) {
            evict_path(math.gen_reverse_lexicographic(evict_g, Configs.BUCKET_COUNT, Configs.HEIGHT));
            evict_g = (evict_g + 1) % Configs.LEAF_COUNT;
        }

        BucketMetadata[] meta_list = get_metadata(position);
        early_reshuffle(position, meta_list);

        return readData;
    }

    public void read_path(int pathID, int blockIndex) {
        BucketMetadata[] meta_list = get_metadata(pathID);
        read_block(pathID, blockIndex, meta_list);
    }

    public BucketMetadata[] get_metadata(int pathID) {
        byte[] pos = Ints.toByteArray(pathID);
        byte[] header = MessageUtility.createMessageHeaderBytes(MessageUtility.ORAM_GETMETA, pos.length);
        ByteBuffer buffer = ByteBuffer.wrap(Bytes.concat(header, pos));
        byte[] response = sendAndGetMessage(buffer, MessageUtility.ORAM_GETMETA);

        BucketMetadata[] meta_list = new BucketMetadata[Configs.HEIGHT];
        int start = 0, index = 0;
        for (int pos_run = pathID; pos_run >= 0; pos_run = (pos_run - 1) >> 1) {
            byte[] meta_bytes = Arrays.copyOfRange(response, start, start + Configs.METADATA_BYTES_LEN);
            meta_list[index] = seria.metadataFromSerialize(meta_bytes);
            start += Configs.METADATA_BYTES_LEN;
            index++;
            if (pos_run == 0) break;
        }
        return meta_list;
    }

    public void read_block(int pathID, int blockIndex, BucketMetadata[] meta_list) {
        boolean found = false;
        int[] read_offset = new int[Configs.HEIGHT];

        for (int i = 0, pos_run = pathID; pos_run >= 0; pos_run = (pos_run - 1) >> 1, i++) {
            if (!found) {
                for (int j = 0; j < Configs.REAL_BLOCK_COUNT; j++) {
                    int offset = meta_list[i].get_offset()[j];
                    if (meta_list[i].get_block_index()[j] == blockIndex &&
                        meta_list[i].getValid_bits()[offset] == 1) {
                        read_offset[i] = offset;
                        found = true;
                        break;
                    }
                }
            }
            if (!found) {
                read_offset[i] = math.get_random_dummy(meta_list[i].getValid_bits(), meta_list[i].get_offset());
            }
            if (pos_run == 0) break;
        }

        byte[] offsets = new byte[Configs.HEIGHT * 4];
        for (int i = 0; i < Configs.HEIGHT; i++) {
            System.arraycopy(Ints.toByteArray(read_offset[i]), 0, offsets, i * 4, 4);
        }

        byte[] pos_bytes = Ints.toByteArray(pathID);
        byte[] request = Bytes.concat(pos_bytes, offsets);
        byte[] header = MessageUtility.createMessageHeaderBytes(MessageUtility.ORAM_READBLOCK, request.length);
        ByteBuffer buffer = ByteBuffer.wrap(Bytes.concat(header, request));
        byte[] response = sendAndGetMessage(buffer, MessageUtility.ORAM_READBLOCK);

        if (found) {
            Block blk = new Block(blockIndex, pathID, response);
            if (stash.find_by_blockIndex(blockIndex) == null) {
                stash.add(blk);
            }
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

    public void read_bucket(int bucketID) {
        byte[] bucketIDBytes = Ints.toByteArray(bucketID);
        byte[] header = MessageUtility.createMessageHeaderBytes(MessageUtility.ORAM_READBUCKET, bucketIDBytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(Bytes.concat(header, bucketIDBytes));
        byte[] resp = sendAndGetMessage(buffer, MessageUtility.ORAM_READBUCKET);
        Bucket bucket = seria.bucketFromSerialize(resp);

        BucketMetadata meta = bucket.getBucket_meta();
        int[] block_index = meta.get_block_index();
        int[] offset = meta.get_offset();
        byte[] valid_bits = meta.getValid_bits();

        for (int i = 0; i < Configs.REAL_BLOCK_COUNT; i++) {
            if (block_index[i] >= 0 && valid_bits[offset[i]] == 1) {
                byte[] blockData = bucket.getBlock(offset[i]);
                stash.add(new Block(block_index[i], 0, blockData));
            }
        }
    }

    public void write_bucket(int bucketID) {
        BucketMetadata meta = new BucketMetadata();
        Block[] block_list = new Block[Configs.REAL_BLOCK_COUNT];
        int count = stash.remove_by_bucket(bucketID, Configs.REAL_BLOCK_COUNT, block_list);

        meta.set_offset(math.get_random_permutation(Configs.Z));
        int[] offset = meta.get_offset();
        byte[] bucket_data = new byte[Configs.Z * Configs.BLOCK_DATA_LEN];

        for (int i = 0; i < count && i < Configs.REAL_BLOCK_COUNT; i++) {
            int off = offset[i] * Configs.BLOCK_DATA_LEN;
            byte[] data = block_list[i].getData();
            System.arraycopy(data, 0, bucket_data, off, data.length);
            meta.set_blockIndex_bit(i, block_list[i].getBlockIndex());
        }

        for (int i = count; i < Configs.Z; i++) {
            int off = offset[i] * Configs.BLOCK_DATA_LEN;
            Arrays.fill(bucket_data, off, off + Configs.BLOCK_DATA_LEN, (byte) 0);
            if (i < Configs.REAL_BLOCK_COUNT) meta.set_blockIndex_bit(i, -1);
        }

        Bucket bucket = new Bucket(bucketID, bucket_data, meta);
        byte[] bucket_bytes = seria.bucketSerialize(bucket);
        byte[] header = MessageUtility.createMessageHeaderBytes(MessageUtility.ORAM_WRITEBUCKET, bucket_bytes.length);
        ByteBuffer buffer = ByteBuffer.wrap(Bytes.concat(header, bucket_bytes));
        sendAndGetMessage(buffer, MessageUtility.ORAM_WRITEBUCKET);
    }

    public void early_reshuffle(int pathID, BucketMetadata[] meta_list) {
        for (int pos_run = pathID, i = 0; pos_run >= 0; pos_run = (pos_run - 1) >> 1, i++) {
            if (meta_list[i].getRead_counter() >= Configs.DUMMY_BLOCK_COUNT - 2) {
                System.out.println("early reshuffle in pos " + pos_run);
                read_bucket(pos_run);
                write_bucket(pos_run);
            }
            if (pos_run == 0) break;
        }
    }

    public void printPositionMap() {
        System.out.println("Position Map:");
        for (int i = 0; i < position_map.length; i++) {
            System.out.println("  Block " + i + " -> Leaf " + position_map[i]);
        }
    }

    public static void main(String[] args) {
        RecursiveClient client = new RecursiveClient();
        client.initServer();

        client.printPositionMap();

        for (int i = 0; i < 4; i++) {
            byte[] data = new byte[Configs.BLOCK_DATA_LEN];
            Arrays.fill(data, (byte) i);
            client.oblivious_access(i, OPERATION.ORAM_ACCESS_WRITE, data);
        }

        byte[] newdata = new byte[Configs.BLOCK_DATA_LEN];
        Arrays.fill(newdata, (byte) 12);
        client.oblivious_access(3, OPERATION.ORAM_ACCESS_WRITE, newdata);

        for (int i = 0; i < 4; i++) {
            byte[] data = client.oblivious_access(i, OPERATION.ORAM_ACCESS_READ, new byte[Configs.BLOCK_DATA_LEN]);
            if (data != null) {
                System.out.println("block " + i + " data:");
                for (int j = 0; j < Configs.BLOCK_DATA_LEN; j++) {
                    System.out.print(data[j] + " ");
                }
                System.out.println();
            } else {
                System.out.println("can't find block " + i + " in server storage");
            }
        }

        client.printPositionMap();
    }
}

package src.com.server;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousChannelGroup;
import java.nio.channels.AsynchronousServerSocketChannel;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.primitives.Bytes;
import com.google.common.primitives.Ints;

import src.com.ringoram.*;

public class Server {

    MathUtility math;
    ServerStorage storage;
    ByteSerialize seria;

    boolean initedServer = false;

    public Server() {
        this.math = new MathUtility();
        this.storage = new ServerStorage();
        this.seria = new ByteSerialize();
    }

    public void run() {
        try {
            AsynchronousChannelGroup threadGroup = AsynchronousChannelGroup.withFixedThreadPool(Configs.THREAD_FIXED,
                    Executors.defaultThreadFactory());
            AsynchronousServerSocketChannel channel = AsynchronousServerSocketChannel.open(threadGroup)
                    .bind(new InetSocketAddress(Configs.SERVER_PORT));
            System.out.println("server wait for connection.");
            channel.accept(null, new CompletionHandler<AsynchronousSocketChannel, Void>() {
                @Override
                public void completed(AsynchronousSocketChannel serveClientChannel, Void att) {
                    channel.accept(null, this);
                    Runnable serializeProcedure = () -> serveClient(serveClientChannel);
                    new Thread(serializeProcedure).start();
                }

                @Override
                public void failed(Throwable exc, Void att) {
                    exc.printStackTrace();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public void serveClient(AsynchronousSocketChannel mChannel) {
        try {
            while (true) {
                ByteBuffer messageTypeAndSize = ByteBuffer.allocate(8);
                Future<Integer> messageRead = mChannel.read(messageTypeAndSize);
                messageRead.get();
                messageTypeAndSize.flip();
                int[] messageTypeAndSizeInt = MessageUtility.parseTypeAndLength(messageTypeAndSize);
                int type = messageTypeAndSizeInt[0];
                int size = messageTypeAndSizeInt[1];

                ByteBuffer message = null;
                if (size != 0) {
                    message = ByteBuffer.allocate(size);
                    while (message.remaining() > 0) {
                        Future<Integer> entireRead = mChannel.read(message);
                        entireRead.get();
                    }
                    message.flip();
                }

                byte[] serializedResponse = null;
                byte[] responseHeader = null;

                if (type == MessageUtility.ORAM_INIT) {
                    Lock lock = new ReentrantLock();
                    lock.lock();
                    try {
                        if (!initedServer) {
                            initServer();
                            initedServer = true;
                            System.out.println("server INIT successful!");
                        } else {
                            System.out.println("server had been initialized!");
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    } finally {
                        lock.unlock();
                    }
                    byte[] responseBytes = {1};
                    serializedResponse = responseBytes;
                    responseHeader = MessageUtility.createMessageHeaderBytes(MessageUtility.ORAM_INIT, serializedResponse.length);
                }

                if (type == MessageUtility.ORAM_GETMETA) {
                    System.out.println("server processes GETMETA request.");
                    byte[] pos_bytes = new byte[4];
                    message.get(pos_bytes);
                    int pos = Ints.fromByteArray(pos_bytes);

                    BucketMetadata[] meta_list = new BucketMetadata[Configs.HEIGHT];
                    byte[][] meta_2d_bytes = new byte[Configs.HEIGHT][];
                    int index = 0;
                    for (int pos_run = pos; pos_run >= 0; pos_run = (pos_run - 1) >> 1) {
                        meta_list[index] = storage.get_bucket(pos_run).getBucket_meta();
                        meta_2d_bytes[index] = seria.metadataSerialize(meta_list[index]);
                        index++;
                        if (pos_run == 0) break;
                    }
                    byte[] meta_bytes = new byte[0];
                    for (int i = 0; i < Configs.HEIGHT; i++) {
                        if (i < index) {
                            meta_bytes = Bytes.concat(meta_bytes, meta_2d_bytes[i]);
                        } else {
                            meta_bytes = Bytes.concat(meta_bytes, new byte[Configs.METADATA_BYTES_LEN]);
                        }
                    }
                    serializedResponse = meta_bytes;
                    responseHeader = MessageUtility.createMessageHeaderBytes(MessageUtility.ORAM_GETMETA, serializedResponse.length);
                }

                if (type == MessageUtility.ORAM_READBLOCK) {
                    System.out.println("server processes READBLOCK request.");
                    byte[] pos_bytes = new byte[4];
                    message.get(pos_bytes);
                    int position = Ints.fromByteArray(pos_bytes);
                    byte[] read_offset_bytes = new byte[Configs.HEIGHT * 4];
                    message.get(read_offset_bytes);
                    int[] read_offset = new int[Configs.HEIGHT];
                    for (int i = 0; i < Configs.HEIGHT; i++) {
                        read_offset[i] = Ints.fromByteArray(Arrays.copyOfRange(read_offset_bytes, i * 4, (i + 1) * 4));
                    }
                    byte[] responseBytes = new byte[Configs.BLOCK_DATA_LEN];
                    for (int pos = position, i = 0; pos >= 0; pos = (pos - 1) >> 1, i++) {
                        Bucket bucket = storage.get_bucket(pos);
                        byte[] block_data = bucket.getBlock(read_offset[i]);
                        bucket.reset_valid_bits(read_offset[i]);
                        bucket.add_read_counter();
                        storage.set_bucket(pos, bucket);
                        for (int j = 0; j < Configs.BLOCK_DATA_LEN; j++) {
                            responseBytes[j] ^= block_data[j];
                        }
                        if (pos == 0) break;
                    }
                    serializedResponse = responseBytes;
                    responseHeader = MessageUtility.createMessageHeaderBytes(MessageUtility.ORAM_READBLOCK, serializedResponse.length);
                }

                if (type == MessageUtility.ORAM_READBUCKET) {
                    byte[] bucket_id_bytes = new byte[4];
                    message.get(bucket_id_bytes);
                    int bucket_id = Ints.fromByteArray(bucket_id_bytes);
                    Bucket bucket = storage.get_bucket(bucket_id);
                    byte[] bucket_bytes = seria.bucketSerialize(bucket);
                    serializedResponse = bucket_bytes;
                    responseHeader = MessageUtility.createMessageHeaderBytes(MessageUtility.ORAM_READBUCKET, serializedResponse.length);
                }

                if (type == MessageUtility.ORAM_WRITEBUCKET) {
                    int bucket_bytes_len = 4 + Configs.Z * Configs.BLOCK_DATA_LEN + Configs.METADATA_BYTES_LEN;
                    byte[] bucket_bytes = new byte[bucket_bytes_len];
                    message.get(bucket_bytes);
                    Bucket bucket = seria.bucketFromSerialize(bucket_bytes);
                    storage.set_bucket(bucket.getId(), bucket);
                    byte[] responseBytes = {1};
                    serializedResponse = responseBytes;
                    responseHeader = MessageUtility.createMessageHeaderBytes(MessageUtility.ORAM_WRITEBUCKET, serializedResponse.length);
                }

                ByteBuffer responseMessage = ByteBuffer.wrap(Bytes.concat(responseHeader, serializedResponse));
                while (responseMessage.remaining() > 0) {
                    Future<Integer> responseWrite = mChannel.write(responseMessage);
                    responseWrite.get();
                }
            }
        } catch (Exception e) {
            try {
                mChannel.close();
            } catch (IOException ignored) {
            }
        }
    }

    public void initServer() {
        for (int i = 0; i < Configs.BUCKET_COUNT; i++) {
            Bucket bucket = new Bucket();
            bucket.getBucket_meta().init_block_index();
            bucket.getBucket_meta().set_offset(math.get_random_permutation(Configs.Z));
            try {
                storage.set_bucket(i, bucket);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        Server server = new Server();
        server.run();
    }
}